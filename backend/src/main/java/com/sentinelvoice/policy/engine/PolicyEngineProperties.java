package com.sentinelvoice.policy.engine;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Infrastructure tunables for the F7 runtime rule engine (not tenant-editable risk values).
 */
@ConfigurationProperties(prefix = "sentinelvoice.policy-engine")
public record PolicyEngineProperties(
        /** MAX | SUM_CAP — how scoreBoosts combine into policyScore ∈ [0,1]. */
        String scoreMode,
        /** Cap when scoreMode=SUM_CAP. */
        double scoreCap,
        /** Caffeine TTL safety refresh (seconds). */
        long cacheTtlSeconds,
        /** Business-hour start (inclusive) used when assembling time.isBusinessHours. */
        int businessHourStart,
        /** Business-hour end (exclusive). */
        int businessHourEnd,
        /** Cross-channel lookback window for fact producers. */
        int crossChannelWindowHours
) {
    public PolicyEngineProperties {
        if (scoreMode == null || scoreMode.isBlank()) {
            scoreMode = "MAX";
        }
        if (scoreCap <= 0 || scoreCap > 1.0) {
            scoreCap = 1.0;
        }
        if (cacheTtlSeconds <= 0) {
            cacheTtlSeconds = 300;
        }
        if (businessHourStart < 0 || businessHourStart > 23) {
            businessHourStart = 9;
        }
        if (businessHourEnd < 1 || businessHourEnd > 24) {
            businessHourEnd = 18;
        }
        if (crossChannelWindowHours <= 0) {
            crossChannelWindowHours = 48;
        }
    }

    public boolean useSumCap() {
        return "SUM_CAP".equalsIgnoreCase(scoreMode);
    }
}
