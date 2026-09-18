package com.sentinelvoice.context.model;

import java.util.List;
import java.util.Map;

/**
 * Result of correlating SIEM precursors to the active call's callee (Context §7.3).
 */
public record CorrelationResult(
        String targetEmployeeId,
        int windowHours,
        double correlationScore,
        boolean matchingCampaign,
        List<Map<String, Object>> events
) {
    public static CorrelationResult empty(int windowHours) {
        return new CorrelationResult(null, windowHours, 0.0, false, List.of());
    }

    public int eventCount() {
        return events == null ? 0 : events.size();
    }

    public boolean hasPrecursors() {
        return eventCount() > 0;
    }
}
