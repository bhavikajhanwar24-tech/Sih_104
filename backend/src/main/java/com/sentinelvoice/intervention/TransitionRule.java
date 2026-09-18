package com.sentinelvoice.intervention;

import com.sentinelvoice.model.InterventionLevel;

/**
 * One directed edge of the intervention ladder (Context §9.5). Thresholds and dwell come from
 * {@link com.sentinelvoice.config.SentinelProperties} — never hardcoded at call sites.
 */
public record TransitionRule(
        InterventionLevel from,
        InterventionLevel to,
        Direction direction,
        double threshold,
        long dwellMs,
        boolean requiresCorroboration,
        boolean requiresAnalystConfirm
) {
    public enum Direction {
        UP,
        DOWN
    }
}
