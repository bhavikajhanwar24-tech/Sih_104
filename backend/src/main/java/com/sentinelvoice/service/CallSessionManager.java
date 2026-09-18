package com.sentinelvoice.service;

import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.audit.AuditLedgerService;
import com.sentinelvoice.config.SentinelProperties;
import com.sentinelvoice.model.CallSession;
import com.sentinelvoice.model.SessionStartRequest;
import com.sentinelvoice.model.TelemetryEntry;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CallSessionManager {

    private final ConcurrentHashMap<String, CallSession> sessions = new ConcurrentHashMap<>();
    private final SentinelProperties properties;
    private final AuditLedgerService auditLedgerService;

    public CallSessionManager(SentinelProperties properties, AuditLedgerService auditLedgerService) {
        this.properties = properties;
        this.auditLedgerService = auditLedgerService;
    }

    public CallSession createSession(SessionStartRequest request) {
        int maxConcurrent = properties.session().maxConcurrent();
        synchronized (this) {
            if (activeSessionCount() >= maxConcurrent) {
                throw new IllegalStateException(
                        "session limit reached: maxConcurrent=" + maxConcurrent
                );
            }
            String sessionId = request.sessionId();
            if (sessionId == null || sessionId.isBlank()) {
                sessionId = UUID.randomUUID().toString();
            }
            if (sessions.containsKey(sessionId)) {
                throw new IllegalArgumentException("session already exists: " + sessionId);
            }
            CallSession session = new CallSession(
                    sessionId,
                    request.callerId(),
                    request.calleeId(),
                    request.channelProfile(),
                    request.scenarioId()
            );
            sessions.put(sessionId, session);
            try {
                auditLedgerService.append(sessionId, AuditEventType.SESSION_OPENED, openPayload(session));
            } catch (RuntimeException ex) {
                sessions.remove(sessionId);
                throw ex;
            }
            return session;
        }
    }

    public Optional<CallSession> getSession(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    /** Active Decision Plane sessions (newest first) — used by Analyst SIP attach. */
    public List<CallSession> listSessions() {
        List<CallSession> copy = new ArrayList<>(sessions.values());
        copy.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));
        return List.copyOf(copy);
    }

    public CallSession requireSession(String sessionId) {
        return getSession(sessionId).orElseThrow(
                () -> new NoSuchElementException("session not found: " + sessionId)
        );
    }

    public void recordTelemetry(String sessionId, TelemetryEntry entry) {
        requireSession(sessionId).recordTelemetry(entry);
    }

    public void closeSession(String sessionId) {
        closeSession(sessionId, "closed");
    }

    public int activeSessionCount() {
        return sessions.size();
    }

    /**
     * Closes sessions whose last telemetry (or creation) is older than {@code sentinelvoice.session.ttlMinutes}.
     *
     * @return ids of sessions that were closed
     */
    public List<String> evictIdleSessions() {
        Instant cutoff = Instant.now().minus(properties.session().ttlMinutes(), ChronoUnit.MINUTES);
        List<String> idle = new ArrayList<>();
        for (CallSession session : sessions.values()) {
            if (session.getLastFrameAt().isBefore(cutoff)) {
                idle.add(session.getSessionId());
            }
        }
        for (String sessionId : idle) {
            closeSession(sessionId, "idle_ttl_exceeded");
        }
        return List.copyOf(idle);
    }

    private void closeSession(String sessionId, String reason) {
        CallSession session = sessions.remove(sessionId);
        if (session == null) {
            return;
        }
        session.close();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", reason);
        payload.put("smoothedRisk", session.getSmoothedRisk());
        payload.put("level", session.getCurrentLevel().name());
        auditLedgerService.append(sessionId, AuditEventType.SESSION_CLOSED, payload);
    }

    private static Map<String, Object> openPayload(CallSession session) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("callerId", session.getCallerId());
        payload.put("calleeId", session.getCalleeId());
        payload.put("channelProfile", session.getChannelProfile().name());
        payload.put("smoothedRisk", 0.0);
        payload.put("level", session.getCurrentLevel().name());
        if (session.getScenarioId() != null) {
            payload.put("scenarioId", session.getScenarioId());
        }
        return payload;
    }
}
