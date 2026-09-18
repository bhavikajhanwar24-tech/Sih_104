package com.sentinelvoice.actuation;

import com.sentinelvoice.config.SentinelProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Mock out-of-band MFA (push/SMS) to the genuine executive's registered device.
 * Demo respond endpoint: {@code POST /api/v1/mfa/{sessionId}/respond}.
 */
@Service
public class OobMfaService {

    private static final Logger log = LoggerFactory.getLogger(OobMfaService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

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

    private final ConcurrentMap<String, MfaChallenge> challenges = new ConcurrentHashMap<>();
    private final long timeoutMs;
    private final Clock clock;

    public OobMfaService(SentinelProperties properties, Clock clock) {
        this.timeoutMs = Math.max(1L, properties.actuation().mfaTimeoutMs());
        this.clock = clock;
    }

    /**
     * Generate a 6-digit code, mock-deliver it, and track the challenge.
     */
    public ActuationResult sendChallenge(String sessionId) {
        try {
            String code = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
            long now = clock.millis();
            MfaChallenge challenge = new MfaChallenge(
                    sessionId,
                    code,
                    now,
                    now + timeoutMs,
                    MfaStatus.PENDING
            );
            challenges.put(sessionId, challenge);
            // Mock SMS / push — never send real messages in lab mode.
            log.info(
                    "MOCK_OOB_MFA sessionId={} code={} expiresInMs={} delivery=sms+push",
                    sessionId,
                    code,
                    timeoutMs
            );
            return ActuationResult.success("mfa-sent");
        } catch (Exception ex) {
            log.warn("oob_mfa_send_failed sessionId={} cause={}", sessionId, ex.toString());
            return ActuationResult.failure(ex.getMessage());
        }
    }

    public Optional<MfaChallenge> getChallenge(String sessionId) {
        refreshTimeout(sessionId);
        return Optional.ofNullable(challenges.get(sessionId));
    }

    /**
     * @param decision {@code approve} or {@code deny}
     */
    public Map<String, Object> respond(String sessionId, String decision) {
        refreshTimeout(sessionId);
        MfaChallenge existing = challenges.get(sessionId);
        if (existing == null) {
            return Map.of(
                    "sessionId", sessionId,
                    "status", "NOT_FOUND",
                    "accepted", false
            );
        }
        if (existing.status() != MfaStatus.PENDING) {
            return Map.of(
                    "sessionId", sessionId,
                    "status", existing.status().name(),
                    "accepted", existing.status() == MfaStatus.APPROVED
            );
        }
        String normalized = decision == null ? "" : decision.trim().toLowerCase(Locale.ROOT);
        MfaStatus next = switch (normalized) {
            case "approve", "approved", "yes" -> MfaStatus.APPROVED;
            case "deny", "denied", "no" -> MfaStatus.DENIED;
            default -> null;
        };
        if (next == null) {
            return Map.of(
                    "sessionId", sessionId,
                    "status", "INVALID_DECISION",
                    "accepted", false,
                    "hint", "use approve|deny"
            );
        }
        MfaChallenge updated = new MfaChallenge(
                existing.sessionId(),
                existing.code(),
                existing.issuedAtMs(),
                existing.expiresAtMs(),
                next
        );
        challenges.put(sessionId, updated);
        log.info("oob_mfa_respond sessionId={} status={}", sessionId, next);
        return Map.of(
                "sessionId", sessionId,
                "status", next.name(),
                "accepted", next == MfaStatus.APPROVED
        );
    }

    public boolean isDeniedOrTimedOut(String sessionId) {
        refreshTimeout(sessionId);
        MfaChallenge c = challenges.get(sessionId);
        return c != null && (c.status() == MfaStatus.DENIED || c.status() == MfaStatus.TIMED_OUT);
    }

    private void refreshTimeout(String sessionId) {
        MfaChallenge c = challenges.get(sessionId);
        if (c == null || c.status() != MfaStatus.PENDING) {
            return;
        }
        if (clock.millis() >= c.expiresAtMs()) {
            challenges.put(
                    sessionId,
                    new MfaChallenge(c.sessionId(), c.code(), c.issuedAtMs(), c.expiresAtMs(), MfaStatus.TIMED_OUT)
            );
            log.info("oob_mfa_timeout sessionId={} — treated as denial", sessionId);
        }
    }
}
