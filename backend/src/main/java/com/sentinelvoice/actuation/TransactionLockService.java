package com.sentinelvoice.actuation;

import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.audit.AuditLedgerService;
import com.sentinelvoice.intervention.InterventionLadderService;
import com.sentinelvoice.model.CallSession;
import com.sentinelvoice.model.InterventionLevel;
import com.sentinelvoice.service.CallSessionManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-authoritative transaction approve lock (Context §9.5 / §11.6).
 * Lock when intervention level ≥ L3 — not a CSS-only UI gate.
 * Operator callback verification can clear the lock without dropping the level.
 */
@Service
public class TransactionLockService {

    public static final String LOCK_REASON =
            "Locked by SentinelVoice — step-up authentication required";

    private final CallSessionManager callSessionManager;
    private final InterventionLadderService interventionLadderService;
    private final AuditLedgerService auditLedgerService;
    /** Explicit lock flags (sessionId → locked). Derived from level when absent. */
    private final ConcurrentHashMap<String, Boolean> locks = new ConcurrentHashMap<>();
    /** Sessions where the operator confirmed callback verification (unlocks approve at L3+). */
    private final ConcurrentHashMap<String, Boolean> callbackVerified = new ConcurrentHashMap<>();

    public TransactionLockService(
            CallSessionManager callSessionManager,
            InterventionLadderService interventionLadderService,
            AuditLedgerService auditLedgerService
    ) {
        this.callSessionManager = callSessionManager;
        this.interventionLadderService = interventionLadderService;
        this.auditLedgerService = auditLedgerService;
    }

    public boolean isLocked(String sessionId) {
        CallSession session = callSessionManager.requireSession(sessionId);
        InterventionLevel level = effectiveLevel(sessionId, session);
        boolean locked = levelLocked(level) && !isCallbackVerified(sessionId);
        locks.put(sessionId, locked);
        return locked;
    }

    public boolean isCallbackVerified(String sessionId) {
        return Boolean.TRUE.equals(callbackVerified.get(sessionId));
    }

    /**
     * Operator confirmed hang-up-and-call-back checklist — unlock Approve while remaining at L3.
     */
    public void markCallbackVerified(String sessionId, String actorId) {
        CallSession session = callSessionManager.requireSession(sessionId);
        callbackVerified.put(sessionId, Boolean.TRUE);
        locks.put(sessionId, Boolean.FALSE);
        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("action", "CALLBACK_VERIFIED");
        auditPayload.put("actorId", actorId == null || actorId.isBlank() ? "unknown" : actorId.trim());
        auditPayload.put("level", effectiveLevel(sessionId, session).name());
        auditLedgerService.append(
                session.getTenantId(),
                sessionId,
                AuditEventType.INTERVENTION_ACTION_FIRED,
                "USER",
                actorId == null || actorId.isBlank() ? null : actorId.trim(),
                auditPayload
        );
    }

    public InterventionLevel effectiveLevel(String sessionId) {
        CallSession session = callSessionManager.requireSession(sessionId);
        return effectiveLevel(sessionId, session);
    }

    /**
     * Attempt to approve a transfer. Returns a result body on success;
     * throws {@link ResponseStatusException} 423 when locked.
     */
    public Map<String, Object> approve(String sessionId, String actorId) {
        CallSession session = callSessionManager.requireSession(sessionId);
        InterventionLevel level = effectiveLevel(sessionId, session);
        boolean locked = levelLocked(level) && !isCallbackVerified(sessionId);
        locks.put(sessionId, locked);

        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("action", "TXN_APPROVE");
        auditPayload.put("actorId", actorId == null || actorId.isBlank() ? "unknown" : actorId.trim());
        auditPayload.put("level", level.name());
        auditPayload.put("locked", locked);
        auditPayload.put("callbackVerified", isCallbackVerified(sessionId));
        auditPayload.put("amountInr", 5_000_000);
        auditPayload.put("smoothedRisk", session.getSmoothedRisk());

        if (locked) {
            auditPayload.put("outcome", "BLOCKED");
            auditPayload.put("reason", LOCK_REASON);
            auditLedgerService.append(
                    session.getTenantId(),
                    sessionId,
                    AuditEventType.INTERVENTION_ACTION_FIRED,
                    "USER",
                    actorId == null || actorId.isBlank() ? null : actorId.trim(),
                    auditPayload
            );
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "LOCKED");
            body.put("httpStatus", 423);
            body.put("reason", LOCK_REASON);
            body.put("level", level.name());
            body.put("sessionId", sessionId);
            throw new ResponseStatusException(HttpStatus.LOCKED, LOCK_REASON, null);
        }

        auditPayload.put("outcome", "APPROVED");
        auditLedgerService.append(
                session.getTenantId(),
                sessionId,
                AuditEventType.INTERVENTION_ACTION_FIRED,
                "USER",
                actorId == null || actorId.isBlank() ? null : actorId.trim(),
                auditPayload
        );

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "APPROVED");
        body.put("sessionId", sessionId);
        body.put("level", level.name());
        body.put("message", "Transfer approved (mock banking panel).");
        return body;
    }

    public void clear(String sessionId) {
        locks.remove(sessionId);
        callbackVerified.remove(sessionId);
    }

    private static boolean levelLocked(InterventionLevel level) {
        return level != null && level.ordinal() >= InterventionLevel.LEVEL_3_STEP_UP_MFA.ordinal();
    }

    private InterventionLevel effectiveLevel(String sessionId, CallSession session) {
        InterventionLevel fsm = interventionLadderService.currentLevel(sessionId);
        InterventionLevel sessionLevel = session.getCurrentLevel();
        // Prefer the higher of FSM vs session so a missed sync never under-locks.
        return fsm.ordinal() >= sessionLevel.ordinal() ? fsm : sessionLevel;
    }
}
