package com.sentinelvoice.intervention;

import com.sentinelvoice.model.InterventionLevel;

import java.util.List;

/**
 * Result of one FSM evaluation or manual override.
 */
public record InterventionDecision(
        InterventionLevel level,
        boolean changed,
        long dwellRemainingMs,
        List<String> actionsToFire,
        String rationale,
        String suppressedIntent
) {
    public static InterventionDecision unchanged(
            InterventionLevel level,
            long dwellRemainingMs,
            String rationale
    ) {
        return new InterventionDecision(level, false, dwellRemainingMs, List.of(), rationale, null);
    }
}
