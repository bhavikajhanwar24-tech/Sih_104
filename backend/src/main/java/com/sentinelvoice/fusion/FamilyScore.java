package com.sentinelvoice.fusion;

/**
 * Per-family contribution to a {@link FusionResult} (TelemetryFrame.families shape).
 */
public record FamilyScore(
        EvidenceFamily family,
        double score,
        double weight,
        double confidence,
        double contribution,
        boolean available
) {
}
