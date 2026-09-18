package com.sentinelvoice.actuation;

import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.audit.AuditWriteDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Mock out-of-band MFA push/SMS to the genuine executive's registered device (Context §11.6).
 * Demo endpoint: {@code POST /api/v1/mfa/{sessionId}/respond}.
 */
@Service
public class OobMfaService {

    private static final Logger log = LoggerFactory.getLogger(OobMfaService.class);
    public static final long TIMEOUT_MS = 90_000L;

    public enum MfaStatus {
        PENDING,
        APPROVED,
        DENIED,
        TIMED_OUT
    }

    public record MfaChallenge(
            String sessionId,
            String code,
            long issuedAtMs,
            long expiresAtMs,
            MfaStatus status
    ) {
    }

    private final AuditWriteDispatcher auditWriteDispatcher;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentMap<String, MfaChallenge> challenges = new ConcurrentHashMap<>();

    public OobMfaService(AuditWriteDispatcher auditWriteDispatcher, Clock clock) {
        this.auditWriteDispatcher = auditWriteDispatcher;
        this.clock = clock;
    }

    /**
     * Issue a new 6-digit challenge (or return the existing pending one). Mock "push/SMS".
     */
    public MfaChallenge sendChallenge(String sessionId) {
        long now = clock.millis();
        MfaChallenge existing = challenges.get(sessionId);
        if (existing != null && existing.status() == MfaStatus.PENDING && now < existing.expiresAtMs()) {
            return existing;
        }

        String code = String.format("%06d", random.nextInt(1_000_000));
        MfaChallenge challenge = new MfaChallenge(
                sessionId,
                code,
                now,
                now + TIMEOUT_MS,
                MfaStatus.PENDING
        );
        challenges.put(sessionId, challenge);

        // MOCK: would push/SMS to the enrolled executive device.
        log.info(
                "MOCK_OOB_MFA sessionId={} code={} expiresInMs={} — demo only, not a real SMS",
                sessionId,
                code,
                TIMEOUT_MS
        );
        return challenge;
    }

    public Optional<MfaChallenge> current(String sessionId) {
        MfaChallenge c = challenges.get(sessionId);
        if (c == null) {
            return Optional.empty();
        }
        return Optional.of(refreshTimeout(c));
    }

    public MfaChallenge respond(String sessionId, boolean approve, String code) {
        MfaChallenge current = challenges.get(sessionId);
        if (current == null) {
            throw new IllegalArgumentException("No MFA challenge for session " + sessionId);
        }
        current = refreshTimeout(current);
        if (current.status() != MfaStatus.PENDING) {
            return current;
        }
        if (code != null && !code.isBlank() && !code.trim().equals(current.code())) {
            MfaChallenge denied = withStatus(current, MfaStatus.DENIED);
            challenges.put(sessionId, denied);
            auditResponse(sessionId, denied, "CODE_MISMATCH");
            return denied;
        }
        MfaStatus next = approve ? MfaStatus.APPROVED : MfaStatus.DENIED;
        MfaChallenge updated = withStatus(current, next);
        challenges.put(sessionId, updated);
        auditResponse(sessionId, updated, approve ? "APPROVED" : "DENIED");
        return updated;
    }

    public void clear(String sessionId) {
        challenges.remove(sessionId);
    }

    private MfaChallenge refreshTimeout(MfaChallenge challenge) {
        if (challenge.status() != MfaStatus.PENDING) {
            return challenge;
        }
        if (clock.millis() >= challenge.expiresAtMs()) {
            MfaChallenge timedOut = withStatus(challenge, MfaStatus.TIMED_OUT);
            challenges.put(challenge.sessionId(), timedOut);
            auditResponse(challenge.sessionId(), timedOut, "TIMEOUT_DENIAL");
            return timedOut;
        }
        return challenge;
    }

    private void auditResponse(String sessionId, MfaChallenge challenge, String outcome) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "OOB_MFA_RESPOND");
        payload.put("status", challenge.status().name());
        payload.put("outcome", outcome);
        auditWriteDispatcher.submit(sessionId, AuditEventType.INTERVENTION_ACTION_FIRED, payload);
    }

    private static MfaChallenge withStatus(MfaChallenge c, MfaStatus status) {
        return new MfaChallenge(c.sessionId(), c.code(), c.issuedAtMs(), c.expiresAtMs(), status);
    }
}
