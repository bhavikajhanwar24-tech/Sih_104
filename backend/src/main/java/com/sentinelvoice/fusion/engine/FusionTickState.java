package com.sentinelvoice.fusion.engine;

/**
 * Mutable-across-ticks (but immutable per tick) fusion FSM state held per session.
 */
public record FusionTickState(
        double smoothed,
        int level,
        long levelEnteredAtMs,
        Long overrideExpiresAtMs,
        int overrideLevel
) {
    public FusionTickState {
        if (level < 1) {
            level = 1;
        }
        if (level > 5) {
            level = 5;
        }
        if (Double.isNaN(smoothed)) {
            smoothed = 0.0;
        }
        smoothed = Math.max(0.0, Math.min(1.0, smoothed));
    }

    public static FusionTickState initial(long nowMs) {
        return new FusionTickState(0.0, 1, nowMs, null, 1);
    }

    public boolean overrideActive(long nowMs) {
        return overrideExpiresAtMs != null && nowMs < overrideExpiresAtMs;
    }
}
