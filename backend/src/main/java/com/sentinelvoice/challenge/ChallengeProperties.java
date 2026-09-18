package com.sentinelvoice.challenge;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Liveness challenge thresholds (Context §7.3 / P8.4).
 */
@Validated
@ConfigurationProperties(prefix = "sentinelvoice.challenge")
public record ChallengeProperties(
        @Min(1) long humanLatencyMs,
        @Min(1) long suspiciousLatencyMs,
        @Min(1) long expiryMs,
        @DecimalMin("0.0") @DecimalMax("1.0") double contentOverlapMin,
        @DecimalMin("0.0") @DecimalMax("1.0") double acousticCosineMin,
        @Min(500) long speechCaptureMs
) {
    public ChallengeProperties {
        if (humanLatencyMs <= 0) {
            humanLatencyMs = 1800L;
        }
        if (suspiciousLatencyMs <= 0) {
            suspiciousLatencyMs = 3500L;
        }
        if (expiryMs <= 0) {
            expiryMs = 15_000L;
        }
        if (contentOverlapMin <= 0) {
            contentOverlapMin = 0.60;
        }
        if (acousticCosineMin <= 0) {
            acousticCosineMin = 0.55;
        }
        if (speechCaptureMs <= 0) {
            speechCaptureMs = 3000L;
        }
    }

    public static ChallengeProperties defaults() {
        return new ChallengeProperties(1800L, 3500L, 15_000L, 0.60, 0.55, 3000L);
    }
}
