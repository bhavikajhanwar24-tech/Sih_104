package com.sentinelvoice.model;

/**
 * Decision-plane view of FeatureFrame.linguistic. Scores are passed through from Python.
 */
public record LinguisticAssessment(
        boolean available,
        double urgency,
        double secrecy,
        double authorityInvocation,
        double emotionalCoercion,
        boolean askDetected,
        String claimedIdentity,
        String claimedRole,
        String language,
        long ageMs,
        double composite
) {
    public static LinguisticAssessment unavailable() {
        return new LinguisticAssessment(
                false, 0, 0, 0, 0, false, null, null, null, 0, 0
        );
    }
}
