package com.sentinelvoice.telephony;

import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.audit.AuditLedgerService;
import com.sentinelvoice.directory.RelationshipService;
import com.sentinelvoice.model.CallSession;
import com.sentinelvoice.model.ChannelProfile;
import com.sentinelvoice.model.SessionStartRequest;
import com.sentinelvoice.security.TenantContext;
import com.sentinelvoice.service.CallSessionManager;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Bridges Asterisk AGI answer/hangup into persisted {@code call_sessions} metadata
 * and in-memory {@link CallSessionManager} so ML can open the feature stream.
 */
@Service
public class CallLifecycleService {

    private final CallSessionRepository callSessionRepository;
    private final CallSessionManager callSessionManager;
    private final TelephonyResolveService resolveService;
    private final RelationshipService relationshipService;
    private final AuditLedgerService auditLedgerService;
    private final LiveCallsBroadcaster liveCallsBroadcaster;

    public CallLifecycleService(
            CallSessionRepository callSessionRepository,
            CallSessionManager callSessionManager,
            TelephonyResolveService resolveService,
            RelationshipService relationshipService,
            AuditLedgerService auditLedgerService,
            @Lazy LiveCallsBroadcaster liveCallsBroadcaster
    ) {
        this.callSessionRepository = callSessionRepository;
        this.callSessionManager = callSessionManager;
        this.resolveService = resolveService;
        this.relationshipService = relationshipService;
        this.auditLedgerService = auditLedgerService;
        this.liveCallsBroadcaster = liveCallsBroadcaster;
    }

    /**
     * Dial-time RINGING audit (before ConfBridge answer). Does not open an ML session.
     */
    @Transactional
    public Map<String, Object> onRinging(StartRequest req) {
        if (req == null || req.calleeExtension() == null || req.calleeExtension().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "calleeExtension required");
        }
        Optional<TelephonyModels.ExtensionResolveResult> caller = resolveCaller(req.callerUsername());
        TelephonyModels.ExtensionResolveResult callee = resolveCallee(req.calleeExtension(), caller);
        UUID tenantId = callee.tenantId();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "RINGING");
        payload.put("calleeExtension", callee.extension());
        payload.put("callerNumber", req.callerNumber() == null ? "" : req.callerNumber());
        payload.put("callerUsername", req.callerUsername() == null ? "" : req.callerUsername());
        payload.put("sipCallId", req.sipCallId() == null ? "" : req.sipCallId());
        payload.put("pai", req.pAssertedIdentity() == null ? "" : req.pAssertedIdentity());
        payload.put("trunkHint", req.trunkHint() == null ? "" : req.trunkHint());
        TenantContext.runAs(tenantId, () ->
                auditLedgerService.append(
                        tenantId,
                        null,
                        AuditEventType.CALL_RINGING,
                        "SYSTEM",
                        null,
                        payload
                )
        );
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenantId", tenantId.toString());
        out.put("event", "RINGING");
        out.put("destEndpoint", callee.username());
        return out;
    }

    /**
     * On answer: resolve parties, persist call_sessions row, register CallSessionManager
     * BEFORE returning so ML can open /ws/features.
     */
    @Transactional
    public Map<String, Object> onAnswer(StartRequest req) {
        if (req == null || req.calleeExtension() == null || req.calleeExtension().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "calleeExtension required");
        }

        Optional<TelephonyModels.ExtensionResolveResult> callerEp = resolveCaller(req.callerUsername());
        TelephonyModels.ExtensionResolveResult callee = resolveCallee(req.calleeExtension(), callerEp);

        final UUID tenantId = callee.tenantId();
        final UUID svSession = req.svSessionUuid() == null ? UUID.randomUUID() : req.svSessionUuid();

        UUID resolvedCallerEmployeeId = null;
        String resolvedCallerNumber = req.callerNumber() == null ? "" : req.callerNumber().trim();
        if (callerEp.isPresent()) {
            resolvedCallerEmployeeId = callerEp.get().employeeId();
            if (resolvedCallerNumber.isBlank()) {
                resolvedCallerNumber = callerEp.get().extension();
            }
        }
        if (resolvedCallerEmployeeId == null && !resolvedCallerNumber.isBlank()) {
            final String classifyCli = resolvedCallerNumber;
            TelephonyModels.NumberClassifyResult cls =
                    TenantContext.runAs(tenantId, () -> resolveService.classifyNumber(tenantId, classifyCli));
            resolvedCallerEmployeeId = cls.employeeId();
        }

        final UUID callerEmployeeId = resolvedCallerEmployeeId;
        final String callerNumber = resolvedCallerNumber;
        final String direction = resolveDirection(callerEmployeeId, callee.employeeId(), req.direction());
        final String calleeNumber = callee.extension();
        final String destEndpoint = callee.username();
        final UUID calleeEmployeeId = callee.employeeId();
        final String sipCallId = req.sipCallId();

        return TenantContext.runAs(tenantId, () -> {
            CallSession memory = callSessionManager.createSession(new SessionStartRequest(
                    "sentinelvoice.SessionStartRequest/1",
                    svSession.toString(),
                    callerNumber.isBlank() ? "unknown" : callerNumber,
                    calleeNumber,
                    ChannelProfile.PSTN_NARROWBAND,
                    null
            ));

            TelephonyModels.CallSessionView persisted = callSessionRepository.insert(
                    tenantId,
                    svSession,
                    callerNumber.isBlank() ? null : callerNumber,
                    calleeNumber,
                    callerEmployeeId,
                    calleeEmployeeId,
                    direction,
                    null,
                    memory.getPolicyVersion(),
                    memory.getFusionConfigVersion(),
                    memory.getResponsePlanVersion(),
                    sipCallId
            );

            Map<String, Object> auditPayload = new LinkedHashMap<>();
            auditPayload.put("callSessionId", persisted.id().toString());
            auditPayload.put("callerNumber", callerNumber);
            auditPayload.put("calleeNumber", calleeNumber);
            auditPayload.put("direction", direction);
            auditPayload.put("sipCallId", sipCallId == null ? "" : sipCallId);
            auditPayload.put("event", "ANSWERED");
            auditLedgerService.append(
                    tenantId,
                    svSession.toString(),
                    AuditEventType.CALL_ANSWERED,
                    "SYSTEM",
                    null,
                    auditPayload
            );

            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("svSessionUuid", svSession.toString());
            out.put("tenantId", tenantId.toString());
            out.put("callSessionId", persisted.id().toString());
            out.put("destEndpoint", destEndpoint);
            out.put("callerEmployeeId", callerEmployeeId == null ? "" : callerEmployeeId.toString());
            out.put("calleeEmployeeId", calleeEmployeeId == null ? "" : calleeEmployeeId.toString());
            out.put("direction", direction);
            out.put("conf", "sv" + svSession.toString().replace("-", ""));
            return out;
        });
    }

    public Map<String, Object> onEnd(UUID svSessionUuid, String outcome) {
        if (svSessionUuid == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "svSessionUuid required");
        }
        Optional<CallSession> memory = callSessionManager.getSession(svSessionUuid.toString());
        Double peakScore = memory.map(CallSession::getSmoothedRisk).orElse(null);
        String peakLevel = memory.map(s -> s.getCurrentLevel().name()).orElse(null);
        String finalOutcome = outcome == null || outcome.isBlank() ? "ENDED" : outcome;

        // Not @Transactional: SECURITY DEFINER finalize must commit even if later
        // audit/relationship steps mark a Spring TX rollback-only.
        CallSessionRepository.FinalizeResult fin = callSessionRepository.finalizeSessionDefiner(
                svSessionUuid, peakScore, peakLevel, finalOutcome
        );
        if (fin.tenantId() == null) {
            return Map.of("svSessionUuid", svSessionUuid.toString(), "finalized", false);
        }

        UUID tenantId = fin.tenantId();
        return TenantContext.runAs(tenantId, () -> {
            if (fin.finalized()
                    && fin.callerEmployeeId() != null
                    && fin.calleeEmployeeId() != null
                    && !fin.callerEmployeeId().equals(fin.calleeEmployeeId())) {
                try {
                    relationshipService.recordContact(
                            tenantId,
                            fin.callerEmployeeId(),
                            fin.calleeEmployeeId(),
                            null,
                            List.of("VOICE")
                    );
                } catch (RuntimeException ignored) {
                    // Lightweight update — do not fail hangup path.
                }
            }

            Map<String, Object> auditPayload = new LinkedHashMap<>();
            auditPayload.put("peakScore", peakScore == null ? 0.0 : peakScore);
            auditPayload.put("peakLevel", peakLevel == null ? "" : peakLevel);
            auditPayload.put("outcome", finalOutcome);
            auditPayload.put("event", "ENDED");
            try {
                auditLedgerService.append(
                        tenantId,
                        svSessionUuid.toString(),
                        AuditEventType.CALL_ENDED,
                        "SYSTEM",
                        null,
                        auditPayload
                );
            } catch (RuntimeException ignored) {
                // Session row already finalized; do not fail AGI hangup on audit blip.
            }

            callSessionManager.closeSession(svSessionUuid.toString());
            try {
                liveCallsBroadcaster.publishEnded(tenantId, svSessionUuid.toString());
            } catch (RuntimeException ignored) {
                // UI delta is best-effort
            }
            return Map.of(
                    "svSessionUuid", svSessionUuid.toString(),
                    "finalized", fin.finalized(),
                    "peakScore", peakScore == null ? 0.0 : peakScore,
                    "peakLevel", peakLevel == null ? "" : peakLevel
            );
        });
    }

    /**
     * End every stuck active call (DB via SECURITY DEFINER + all in-memory sessions).
     * Used when hangup finalize was missed and Live Calls still shows ghost actives.
     */
    public Map<String, Object> clearAllActive(String outcome) {
        int closedDb = callSessionRepository.finalizeAllActiveGlobal(outcome);
        int closedMemory = 0;
        for (CallSession mem : List.copyOf(callSessionManager.listAllSessions())) {
            try {
                callSessionManager.closeSession(mem.getSessionId());
                closedMemory++;
            } catch (RuntimeException ignored) {
                // best-effort
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("clearedDb", closedDb);
        out.put("clearedMemory", closedMemory);
        out.put("outcome", outcome == null || outcome.isBlank() ? "OPERATOR_CLEAR" : outcome);
        return out;
    }

    private static String resolveDirection(UUID callerEmp, UUID calleeEmp, String requested) {
        if (requested != null && !requested.isBlank()) {
            String d = requested.trim().toUpperCase();
            if (List.of("INBOUND", "OUTBOUND", "INTERNAL").contains(d)) {
                return d;
            }
        }
        if (callerEmp != null && calleeEmp != null) {
            return "INTERNAL";
        }
        if (callerEmp == null && calleeEmp != null) {
            return "INBOUND";
        }
        return "OUTBOUND";
    }

    private Optional<TelephonyModels.ExtensionResolveResult> resolveCaller(String callerUsername) {
        if (callerUsername == null || callerUsername.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(resolveService.resolveUsername(callerUsername)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "cross-tenant dial denied")));
    }

    private TelephonyModels.ExtensionResolveResult resolveCallee(
            String calleeExtension,
            Optional<TelephonyModels.ExtensionResolveResult> caller
    ) {
        Optional<TelephonyModels.ExtensionResolveResult> callee = caller.isPresent()
                ? resolveService.resolveExtensionInTenant(caller.get().tenantId(), calleeExtension)
                : resolveService.resolveExtension(calleeExtension);
        return callee.orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "callee extension not found"));
    }

    public record StartRequest(
            UUID svSessionUuid,
            String callerNumber,
            String callerUsername,
            String calleeExtension,
            String sipCallId,
            String direction,
            String pAssertedIdentity,
            String trunkHint
    ) {
        public StartRequest(
                UUID svSessionUuid,
                String callerNumber,
                String callerUsername,
                String calleeExtension,
                String sipCallId,
                String direction
        ) {
            this(svSessionUuid, callerNumber, callerUsername, calleeExtension, sipCallId, direction, null, null);
        }
    }
}
