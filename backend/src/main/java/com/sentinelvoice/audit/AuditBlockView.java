package com.sentinelvoice.audit;

import java.util.Map;

/**
 * Frozen contract {@code sentinelvoice.AuditBlock/1} as returned by the compliance API.
 */
public record AuditBlockView(
        String schema,
        String sessionId,
        int blockIndex,
        long tsEpochMs,
        String eventType,
        Map<String, Object> payload,
        double smoothedRisk,
        String level,
        String previousHash,
        String currentHash
) {
    public static final String SCHEMA = "sentinelvoice.AuditBlock/1";
}
