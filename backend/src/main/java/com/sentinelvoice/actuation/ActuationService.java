package com.sentinelvoice.actuation;

import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.audit.AuditWriteDispatcher;
import com.sentinelvoice.config.SentinelProperties;
import com.sentinelvoice.forensics.ForensicDossierService;
import com.sentinelvoice.model.InterventionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;

/**
 * Maps intervention levels to physical / logical actuation with per-session idempotency (Context §11.6).
 * ARI / webhook work runs on a dedicated executor so a hung telephony call never stalls fusion.
 */
@Service
public class ActuationService {

    private static final Logger log = LoggerFactory.getLogger(ActuationService.class);

    public enum ActionResult {
        SUCCESS,
        FAILURE,
        UNSUPPORTED,
        SKIPPED_IDEMPOTENT
    }

    private final CallControlPort callControl;
    private final OobMfaService oobMfaService;
    private final CoreBankingWebhookService coreBankingWebhookService;
    private final AuditWriteDispatcher auditWriteDispatcher;
    private final Executor actuationExecutor;
    private final Clock clock;
    private final String supervisorEndpoint;
    private final ObjectProvider<ForensicDossierService> forensicDossierService;
    private final ConcurrentMap<String, Set<ActuationAction>> firedBySession = new ConcurrentHashMap<>();

    public ActuationService(
            CallControlPort callControl,
            OobMfaService oobMfaService,
            CoreBankingWebhookService coreBankingWebhookService,
            AuditWriteDispatcher auditWriteDispatcher,
            @Qualifier("actuationExecutor") Executor actuationExecutor,
            Clock clock,
            SentinelProperties properties,
            ObjectProvider<ForensicDossierService> forensicDossierService
    ) {
        this.callControl = callControl;
        this.oobMfaService = oobMfaService;
        this.coreBankingWebhookService = coreBankingWebhookService;
        this.auditWriteDispatcher = auditWriteDispatcher;
        this.actuationExecutor = actuationExecutor;
        this.clock = clock;
        this.supervisorEndpoint = properties.actuation().supervisorEndpoint();
        this.forensicDossierService = forensicDossierService;
    }

    /**
     * Diff desired actions for {@code level} against already-fired actions and execute the delta.
     * Never throws — failures are audited and logged.
     */
    public void onLevelChanged(String sessionId, InterventionLevel previous, InterventionLevel level) {
        if (sessionId == null || sessionId.isBlank() || level == null) {
            return;
        }
        try {
            Set<ActuationAction> desired = actionsForLevel(level);
            Set<ActuationAction> already = firedBySession.computeIfAbsent(
                    sessionId,
                    id -> EnumSet.noneOf(ActuationAction.class)
            );
            Set<ActuationAction> toFire = EnumSet.noneOf(ActuationAction.class);
            synchronized (already) {
                for (ActuationAction action : desired) {
                    if (!already.contains(action)) {
                        toFire.add(action);
                    }
                }
            }
            if (toFire.isEmpty()) {
                log.debug(
                        "actuation_noop sessionId={} level={} previous={} (all actions already active)",
                        sessionId,
                        level,
                        previous
                );
                return;
            }
            // Snapshot outside the lock — execute async so fusion/STOMP stay unblocked.
            Set<ActuationAction> batch = EnumSet.copyOf(toFire);
            actuationExecutor.execute(() -> fireBatch(sessionId, previous, level, batch));
        } catch (Exception ex) {
            log.error("actuation_schedule_failed sessionId={} level={} err={}", sessionId, level, ex.toString(), ex);
        }
    }

    public Set<ActuationAction> firedActions(String sessionId) {
        Set<ActuationAction> set = firedBySession.get(sessionId);
        return set == null ? Set.of() : Set.copyOf(set);
    }

    public void clearSession(String sessionId) {
        firedBySession.remove(sessionId);
        oobMfaService.clear(sessionId);
        if (callControl instanceof AsteriskAriAdapter ari) {
            ari.unbind(sessionId);
        }
    }

    static Set<ActuationAction> actionsForLevel(InterventionLevel level) {
        return switch (level) {
            case LEVEL_1_SILENT -> EnumSet.noneOf(ActuationAction.class);
            case LEVEL_2_SOFT_NUDGE -> EnumSet.of(ActuationAction.UI_BANNER);
            case LEVEL_3_STEP_UP_MFA -> EnumSet.of(
                    ActuationAction.TXN_APPROVE_LOCKED,
                    ActuationAction.OOB_MFA_SENT
            );
            case LEVEL_4_AUTO_HOLD -> EnumSet.of(
                    ActuationAction.CALL_HELD,
                    ActuationAction.SUPERVISOR_BRIDGED,
                    ActuationAction.TXN_APPROVE_LOCKED
            );
            case LEVEL_5_TERMINATE -> EnumSet.of(
                    ActuationAction.CALL_TERMINATED,
                    ActuationAction.BENEFICIARY_FROZEN,
                    ActuationAction.DOSSIER_GENERATED
            );
        };
    }

    private void fireBatch(
            String sessionId,
            InterventionLevel previous,
            InterventionLevel level,
            Set<ActuationAction> batch
    ) {
        for (ActuationAction action : batch) {
            ActionResult result = ActionResult.FAILURE;
            long started = clock.millis();
            try {
                result = execute(sessionId, action);
                if (result == ActionResult.SUCCESS || result == ActionResult.SKIPPED_IDEMPOTENT) {
                    markFired(sessionId, action);
                }
            } catch (Exception ex) {
                result = ActionResult.FAILURE;
                log.warn(
                        "actuation_failed sessionId={} action={} adapter={} err={}",
                        sessionId,
                        action,
                        callControl.adapterName(),
                        ex.toString()
                );
            } finally {
                long latencyMs = Math.max(0L, clock.millis() - started);
                auditAction(sessionId, previous, level, action, result, latencyMs);
            }
        }
    }

    private ActionResult execute(String sessionId, ActuationAction action) {
        Set<ActuationAction> caps = callControl.capabilities();
        return switch (action) {
            case UI_BANNER -> {
                log.info("actuation UI_BANNER sessionId={} adapter={}", sessionId, callControl.adapterName());
                yield ActionResult.SUCCESS;
            }
            case TXN_APPROVE_LOCKED -> {
                // Enforced by TransactionLockService on approve; mark as armed.
                log.info("actuation TXN_APPROVE_LOCKED sessionId={}", sessionId);
                yield ActionResult.SUCCESS;
            }
            case OOB_MFA_SENT -> {
                oobMfaService.sendChallenge(sessionId);
                yield ActionResult.SUCCESS;
            }
            case CALL_HELD -> {
                if (!caps.contains(ActuationAction.HOLD)) {
                    yield ActionResult.UNSUPPORTED;
                }
                callControl.hold(sessionId);
                // Agent-only caution after hold (best-effort; unsupported adapters skip quietly).
                if (caps.contains(ActuationAction.WHISPER)) {
                    try {
                        callControl.whisperToAgent(sessionId, AsteriskAriAdapter.SOUND_WHISPER);
                    } catch (Exception whisperEx) {
                        log.warn("actuation_whisper_after_hold failed sessionId={} err={}", sessionId, whisperEx.toString());
                    }
                }
                yield ActionResult.SUCCESS;
            }
            case SUPERVISOR_BRIDGED -> {
                if (!caps.contains(ActuationAction.BRIDGE_SUPERVISOR)) {
                    yield ActionResult.UNSUPPORTED;
                }
                callControl.bridgeSupervisor(sessionId, supervisorEndpoint);
                yield ActionResult.SUCCESS;
            }
            case CALL_TERMINATED -> {
                if (!caps.contains(ActuationAction.TERMINATE)) {
                    yield ActionResult.UNSUPPORTED;
                }
                callControl.terminate(sessionId, "LEVEL_5_TERMINATE");
                yield ActionResult.SUCCESS;
            }
            case BENEFICIARY_FROZEN -> {
                boolean ok = coreBankingWebhookService.freezeBeneficiary(
                        sessionId,
                        "demo-beneficiary",
                        "LEVEL_5_TERMINATE"
                );
                yield ok ? ActionResult.SUCCESS : ActionResult.FAILURE;
            }
            case DOSSIER_GENERATED -> {
                ForensicDossierService dossier = forensicDossierService.getIfAvailable();
                if (dossier != null) {
                    dossier.renderPdf(sessionId, "actuation-L5");
                } else {
                    log.info("actuation DOSSIER_GENERATED sessionId={} (dossier service unavailable)", sessionId);
                }
                yield ActionResult.SUCCESS;
            }
            case HOLD, UNHOLD, WHISPER, ANNOUNCE, BRIDGE_SUPERVISOR, TERMINATE -> ActionResult.UNSUPPORTED;
        };
    }

    private void markFired(String sessionId, ActuationAction action) {
        Set<ActuationAction> set = firedBySession.computeIfAbsent(
                sessionId,
                id -> EnumSet.noneOf(ActuationAction.class)
        );
        synchronized (set) {
            set.add(action);
        }
    }

    private void auditAction(
            String sessionId,
            InterventionLevel previous,
            InterventionLevel level,
            ActuationAction action,
            ActionResult result,
            long latencyMs
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", action.name());
        payload.put("adapter", callControl.adapterName());
        payload.put("result", result.name());
        payload.put("latencyMs", latencyMs);
        payload.put("level", level == null ? null : level.name());
        payload.put("previousLevel", previous == null ? null : previous.name());
        payload.put("capabilities", callControl.capabilities().stream().map(Enum::name).toList());
        auditWriteDispatcher.submit(sessionId, AuditEventType.INTERVENTION_ACTION_FIRED, payload);
    }

    /** Test helper: run synchronously without the executor. */
    void applySync(String sessionId, InterventionLevel level) {
        Set<ActuationAction> desired = actionsForLevel(level);
        Set<ActuationAction> already = firedBySession.computeIfAbsent(
                sessionId,
                id -> EnumSet.noneOf(ActuationAction.class)
        );
        List<ActuationAction> toFire;
        synchronized (already) {
            toFire = desired.stream().filter(a -> !already.contains(a)).toList();
        }
        fireBatch(sessionId, null, level, new LinkedHashSet<>(toFire));
    }
}
