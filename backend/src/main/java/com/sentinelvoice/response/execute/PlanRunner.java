package com.sentinelvoice.response.execute;

import com.sentinelvoice.actuation.CallControlPort;
import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.audit.AuditWriteDispatcher;
import com.sentinelvoice.integrations.WebhookService;
import com.sentinelvoice.governance.EmergencyModeService;
import com.sentinelvoice.model.CallSession;
import com.sentinelvoice.model.InterventionLevel;
import com.sentinelvoice.policy.engine.ConditionEvaluator;
import com.sentinelvoice.policy.engine.FactSet;
import com.sentinelvoice.policy.engine.TriBool;
import com.sentinelvoice.response.ResponseActionKey;
import com.sentinelvoice.response.ResponsePlanDocument;
import com.sentinelvoice.security.TenantContext;
import com.sentinelvoice.service.CallSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;

/**
 * Plan-driven actuation. The only mapping from level → actions is the session-snapshotted response plan.
 */
@Service
public class PlanRunner {

    private static final Logger log = LoggerFactory.getLogger(PlanRunner.class);

    /** Fallback chain when a critical action fails. */
    private static final Map<String, List<String>> FALLBACKS = Map.of(
            "HOLD_CALL", List.of("BRIDGE_SUPERVISOR", "NOTIFY_SUPERVISOR", "OPERATOR_ADVISORY"),
            "TERMINATE_CALL", List.of("HOLD_CALL", "NOTIFY_SUPERVISOR", "OPERATOR_ADVISORY"),
            "BRIDGE_SUPERVISOR", List.of("NOTIFY_SUPERVISOR", "OPERATOR_ADVISORY"),
            "LOCK_APPROVAL", List.of("REQUIRE_CALLBACK_VERIFICATION", "OPERATOR_ADVISORY"),
            "SEND_OOB_MFA", List.of("REQUIRE_CALLBACK_VERIFICATION", "OPERATOR_ADVISORY"),
            "FREEZE_BENEFICIARY", List.of("NOTIFY_SUPERVISOR", "OPERATOR_ADVISORY")
    );

    public enum ActionResult {
        SUCCESS,
        FAILURE,
        UNSUPPORTED,
        SKIPPED_IDEMPOTENT
    }

    private final CallSessionManager callSessionManager;
    private final ActionExecutorRegistry executors;
    private final SessionActionRepository sessionActions;
    private final AuditWriteDispatcher auditWriteDispatcher;
    private final CallControlPort callControl;
    private final Executor actuationExecutor;
    private final Clock clock;
    private final EmergencyModeService emergencyModeService;
    private final ObjectProvider<WebhookService> webhookService;
    private final ConcurrentMap<String, String> entryTokenBySessionLevel = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, InterventionLevel> lastLevelBySession = new ConcurrentHashMap<>();

    public PlanRunner(
            CallSessionManager callSessionManager,
            ActionExecutorRegistry executors,
            SessionActionRepository sessionActions,
            AuditWriteDispatcher auditWriteDispatcher,
            CallControlPort callControl,
            @Qualifier("actuationExecutor") Executor actuationExecutor,
            Clock clock,
            EmergencyModeService emergencyModeService,
            ObjectProvider<WebhookService> webhookService
    ) {
        this.callSessionManager = callSessionManager;
        this.executors = executors;
        this.sessionActions = sessionActions;
        this.auditWriteDispatcher = auditWriteDispatcher;
        this.callControl = callControl;
        this.actuationExecutor = actuationExecutor;
        this.clock = clock;
        this.emergencyModeService = emergencyModeService;
        this.webhookService = webhookService;
    }

    public void onLevelChanged(String sessionId, InterventionLevel previous, InterventionLevel level) {
        if (sessionId == null || sessionId.isBlank() || level == null) {
            return;
        }
        try {
            CallSession session = callSessionManager.requireSession(sessionId);
            UUID tenantId = session.getTenantId();
            if (tenantId != null && emergencyModeService.isSuspended(tenantId)) {
                log.info("plan_runner_skip_suspended sessionId={}", sessionId);
                return;
            }
            InterventionLevel from = previous != null
                    ? previous
                    : lastLevelBySession.getOrDefault(sessionId, InterventionLevel.LEVEL_1_SILENT);
            lastLevelBySession.put(sessionId, level);
            if (from == level) {
                return;
            }
            try {
                WebhookService wh = webhookService.getIfAvailable();
                if (wh != null && tenantId != null) {
                    wh.enqueue(tenantId, "risk.level_changed", Map.of(
                            "sessionId", sessionId,
                            "previousLevel", from.name(),
                            "level", level.name()
                    ));
                }
            } catch (Exception ex) {
                log.debug("webhook_enqueue_skipped sessionId={} err={}", sessionId, ex.toString());
            }
            String levelKey = ResponsePlanDocument.levelKeyFor(level);
            String entryToken = sessionId + ":" + levelKey + ":" + clock.millis();
            entryTokenBySessionLevel.put(sessionId + "|" + levelKey, entryToken);
            boolean monitorOnly = tenantId != null && emergencyModeService.isMonitorOnly(tenantId);
            // Async actuation must re-bind tenant for RLS on session_actions.
            actuationExecutor.execute(() -> TenantContext.runAs(
                    tenantId,
                    () -> runLevelEntry(sessionId, from, level, levelKey, entryToken, monitorOnly)
            ));
        } catch (Exception ex) {
            log.error("plan_runner_schedule_failed sessionId={} level={} err={}", sessionId, level, ex.toString(), ex);
        }
    }

    public void clearSession(String sessionId) {
        if (sessionId == null) {
            return;
        }
        lastLevelBySession.remove(sessionId);
        entryTokenBySessionLevel.keySet().removeIf(k -> k.startsWith(sessionId + "|"));
    }

    public ActionResult forceHold(String sessionId) {
        if (!callControl.capabilities().contains("HOLD")) {
            return ActionResult.UNSUPPORTED;
        }
        try {
            callControl.hold(sessionId);
            auditFired(sessionId, "HOLD_CALL", "forceHold", true, false, null);
            return ActionResult.SUCCESS;
        } catch (Exception e) {
            log.warn("force_hold_failed sessionId={} err={}", sessionId, e.toString());
            return ActionResult.FAILURE;
        }
    }

    public ActionResult forceBridge(String sessionId, String supervisorEndpoint) {
        if (!callControl.capabilities().contains("BRIDGE_SUPERVISOR")) {
            return ActionResult.UNSUPPORTED;
        }
        try {
            callControl.bridgeSupervisor(sessionId, supervisorEndpoint);
            auditFired(sessionId, "BRIDGE_SUPERVISOR", "forceBridge", true, false, null);
            return ActionResult.SUCCESS;
        } catch (Exception e) {
            log.warn("force_bridge_failed sessionId={} err={}", sessionId, e.toString());
            return ActionResult.FAILURE;
        }
    }

    public ActionResult forceUnhold(String sessionId) {
        if (!callControl.capabilities().contains("UNHOLD") && !callControl.capabilities().contains("HOLD")) {
            return ActionResult.UNSUPPORTED;
        }
        try {
            callControl.unhold(sessionId);
            auditFired(sessionId, "UNHOLD", "forceUnhold", true, false, null);
            return ActionResult.SUCCESS;
        } catch (Exception e) {
            return ActionResult.FAILURE;
        }
    }

    public void overrideAction(
            String sessionId,
            UUID actionId,
            String reason,
            String actorId
    ) {
        if (reason == null || reason.trim().length() < 10) {
            throw new IllegalArgumentException("override reason must be at least 10 characters");
        }
        CallSession session = callSessionManager.requireSession(sessionId);
        String levelKey = ResponsePlanDocument.levelKeyFor(session.getCurrentLevel());
        ResponsePlanDocument plan = resolvePlan(session);
        ResponsePlanDocument.LevelPlan lp = plan.level(levelKey);
        if (lp != null && !lp.operatorOverridePermitted()) {
            throw new IllegalStateException("operator override not permitted at " + levelKey);
        }
        if (lp != null && lp.overrideRequiresSupervisor()) {
            // Soft gate: require reason (supervisor co-approval is audited; hard gate lands with RBAC later).
            log.info("override_requires_supervisor sessionId={} level={}", sessionId, levelKey);
        }
        sessionActions.markOverridden(session.getTenantId(), sessionId, actionId, reason.trim(), actorId);
        auditWriteDispatcher.submit(sessionId, AuditEventType.ACTION_OVERRIDDEN, Map.of(
                "actionId", actionId.toString(),
                "reason", reason.trim(),
                "actorId", actorId == null ? "" : actorId,
                "level", levelKey
        ));
    }

    public List<String> actionsForLevel(InterventionLevel level, CallSession session) {
        ResponsePlanDocument plan = resolvePlan(session);
        String key = ResponsePlanDocument.levelKeyFor(level);
        ResponsePlanDocument.LevelPlan lp = plan.level(key);
        if (lp == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (ResponsePlanDocument.PlanStep s : lp.steps()) {
            out.add(s.action());
        }
        return List.copyOf(out);
    }

    private void runLevelEntry(
            String sessionId,
            InterventionLevel previous,
            InterventionLevel level,
            String levelKey,
            String entryToken,
            boolean monitorOnly
    ) {
        CallSession session;
        try {
            session = callSessionManager.requireSession(sessionId);
        } catch (Exception e) {
            log.warn("plan_runner_no_session sessionId={}", sessionId);
            return;
        }
        ResponsePlanDocument plan = resolvePlan(session);
        boolean emergency = session.getResponsePlanVersion() == null;
        if (emergency) {
            auditWriteDispatcher.submit(sessionId, AuditEventType.RESPONSE_PLAN_EMERGENCY, Map.of(
                    "level", levelKey,
                    "reason", "no ACTIVE response plan at session open"
            ));
        }
        ResponsePlanDocument.LevelPlan lp = plan.level(levelKey);
        if (lp == null || lp.steps().isEmpty()) {
            plan = ResponsePlanDocument.emergencyPlan();
            lp = plan.level(levelKey);
            auditWriteDispatcher.submit(sessionId, AuditEventType.RESPONSE_PLAN_EMERGENCY, Map.of(
                    "level", levelKey,
                    "reason", "empty level in plan"
            ));
        }
        FactSet facts = FactSet.empty();
        int idx = 0;
        for (ResponsePlanDocument.PlanStep step : lp.steps()) {
            final int stepIndex = idx++;
            if (!"ON_ENTER".equals(step.trigger())) {
                continue;
            }
            if (monitorOnly && !isAdvisoryAction(step.action())) {
                auditFired(sessionId, step.action(), "monitor_only_suppressed", true, false, null);
                continue;
            }
            if (step.operatorConfirm() && !step.autoExecute()) {
                UUID pendingId = sessionActions.tryInsertPending(
                        session.getTenantId(), sessionId, levelKey, stepIndex, step.action(), entryToken
                ).orElse(null);
                if (pendingId != null) {
                    sessionActions.finish(pendingId, "AWAITING_OPERATOR", Map.of("reason", "operatorConfirm"));
                    auditFired(sessionId, step.action(), "awaiting_operator", true, false, null);
                }
                continue;
            }
            if (step.delayMs() > 0) {
                try {
                    Thread.sleep(Math.min(step.delayMs(), 30_000L));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (step.condition() != null && !step.condition().isEmpty()) {
                try {
                    var er = ConditionEvaluator.evaluate(step.parsedCondition(), facts);
                    if (er.value() != TriBool.TRUE) {
                        UUID skipId = sessionActions.tryInsertPending(
                                session.getTenantId(), sessionId, levelKey, stepIndex, step.action(), entryToken
                        ).orElse(null);
                        if (skipId != null) {
                            sessionActions.finish(skipId, "SKIPPED", Map.of("reason", "condition_not_met"));
                        }
                        continue;
                    }
                } catch (Exception e) {
                    log.warn("condition_eval_failed action={} err={}", step.action(), e.toString());
                }
            }
            executeStep(session, sessionId, previous, level, levelKey, stepIndex, step, entryToken);
        }
    }

    private void executeStep(
            CallSession session,
            String sessionId,
            InterventionLevel previous,
            InterventionLevel level,
            String levelKey,
            int stepIndex,
            ResponsePlanDocument.PlanStep step,
            String entryToken
    ) {
        var inserted = sessionActions.tryInsertPending(
                session.getTenantId(), sessionId, levelKey, stepIndex, step.action(), entryToken
        );
        if (inserted.isEmpty()) {
            log.debug("plan_step_idempotent sessionId={} level={} step={} action={}",
                    sessionId, levelKey, stepIndex, step.action());
            return;
        }
        UUID rowId = inserted.get();
        ActionExecutor executor = executors.get(step.action());
        ActionExecutor.ActionOutcome outcome;
        if (executor == null) {
            outcome = ActionExecutor.ActionOutcome.unsupported("no executor for " + step.action());
        } else {
            try {
                outcome = executor.execute(new ActionExecutor.ActionContext(
                        session.getTenantId(),
                        sessionId,
                        levelKey,
                        stepIndex,
                        step.params() == null ? Map.of() : step.params(),
                        Map.of()
                ));
            } catch (Exception e) {
                outcome = ActionExecutor.ActionOutcome.failed(e.getMessage());
            }
        }

        if (outcome.status() == ActionExecutor.ActionOutcome.Status.FAILED
                || outcome.status() == ActionExecutor.ActionOutcome.Status.UNSUPPORTED) {
            ActionExecutor.ActionOutcome fallback = tryFallback(session, sessionId, levelKey, step.action());
            if (fallback != null) {
                auditWriteDispatcher.submit(sessionId, AuditEventType.ACTION_DEGRADED, Map.of(
                        "action", step.action(),
                        "level", levelKey,
                        "fallback", fallback.degradedTo() == null ? fallback.detail() : fallback.degradedTo(),
                        "reason", outcome.detail() == null ? "" : outcome.detail()
                ));
                outcome = fallback;
            }
        }

        String status = switch (outcome.status()) {
            case SUCCESS -> "EXECUTED";
            case FAILED, UNSUPPORTED -> "FAILED";
            case SKIPPED -> "SKIPPED";
            case AWAITING_OPERATOR -> "AWAITING_OPERATOR";
        };
        Map<String, Object> result = new LinkedHashMap<>(outcome.result() == null ? Map.of() : outcome.result());
        if (outcome.degraded()) {
            result.put("degraded", true);
            result.put("degradedTo", outcome.degradedTo());
        }
        sessionActions.finish(rowId, status, result);
        auditFired(sessionId, step.action(), outcome.detail(),
                outcome.status() == ActionExecutor.ActionOutcome.Status.SUCCESS
                        || outcome.status() == ActionExecutor.ActionOutcome.Status.AWAITING_OPERATOR,
                outcome.degraded(),
                outcome.degradedTo());
        if (step.requiresAck() && outcome.status() == ActionExecutor.ActionOutcome.Status.SUCCESS) {
            sessionActions.finish(rowId, "AWAITING_OPERATOR", result);
        }
    }

    private ActionExecutor.ActionOutcome tryFallback(
            CallSession session,
            String sessionId,
            String levelKey,
            String failedAction
    ) {
        List<String> chain = FALLBACKS.getOrDefault(failedAction, List.of("OPERATOR_ADVISORY"));
        for (String next : chain) {
            ActionExecutor ex = executors.get(next);
            if (ex == null) {
                continue;
            }
            try {
                ActionExecutor.ActionOutcome o = ex.execute(new ActionExecutor.ActionContext(
                        session.getTenantId(), sessionId, levelKey, -1, Map.of(), Map.of()
                ));
                if (o.status() == ActionExecutor.ActionOutcome.Status.SUCCESS
                        || o.status() == ActionExecutor.ActionOutcome.Status.AWAITING_OPERATOR) {
                    return ActionExecutor.ActionOutcome.degraded(
                            "fell back from " + failedAction + " to " + next, next);
                }
            } catch (Exception ignored) {
                // try next
            }
        }
        // Absolute fail-safe
        ActionExecutor advisory = executors.get(ResponseActionKey.OPERATOR_ADVISORY.name());
        if (advisory != null) {
            advisory.execute(new ActionExecutor.ActionContext(
                    session.getTenantId(), sessionId, levelKey, -1,
                    Map.of("text", "Critical action failed — advisory only"), Map.of()
            ));
            return ActionExecutor.ActionOutcome.degraded("absolute fail-safe advisory", "OPERATOR_ADVISORY");
        }
        return null;
    }

    private static boolean isAdvisoryAction(String action) {
        if (action == null) return true;
        return "OPERATOR_ADVISORY".equals(action)
                || "NOTIFY_SUPERVISOR".equals(action)
                || "LOG_ONLY".equals(action)
                || action.startsWith("NOTIFY_");
    }

    private ResponsePlanDocument resolvePlan(CallSession session) {
        if (session.getResponsePlanSnapshot() != null) {
            return session.getResponsePlanSnapshot();
        }
        return ResponsePlanDocument.emergencyPlan();
    }

    private void auditFired(
            String sessionId,
            String action,
            String detail,
            boolean ok,
            boolean degraded,
            String degradedTo
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", action);
        payload.put("detail", detail == null ? "" : detail);
        payload.put("ok", ok);
        payload.put("degraded", degraded);
        if (degradedTo != null) {
            payload.put("degradedTo", degradedTo);
        }
        payload.put("adapter", callControl.adapterName());
        auditWriteDispatcher.submit(sessionId, AuditEventType.INTERVENTION_ACTION_FIRED, payload);
    }
}
