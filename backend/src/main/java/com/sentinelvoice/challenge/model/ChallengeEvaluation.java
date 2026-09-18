package com.sentinelvoice.challenge.model;

/**
 * Immutable evaluation breakdown for audit + UI.
 */
public record ChallengeEvaluation(
        ChallengeVerdict verdict,
        long latencyMs,
        double contentOverlap,
        double acousticCosine,
        boolean latencyPass,
        boolean contentPass,
        boolean acousticPass,
        String expectedPhrase,
        String transcript,
        long humanLatencyMs,
        long suspiciousLatencyMs
) {
}
