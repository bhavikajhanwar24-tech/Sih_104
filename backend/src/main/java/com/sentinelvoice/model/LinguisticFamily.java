package com.sentinelvoice.model;

/**
 * Frozen FeatureFrame.linguistic block. Context §8.1. Mapped, never keyword-scored, in Java.
 */
public record LinguisticFamily(
        boolean available,
        Long ageMs,
        String language,
        Double urgency,
        Double secrecy,
        Double authorityInvocation,
        Double emotionalCoercion,
        Boolean askDetected,
        Ask ask,
        String claimedIdentity,
        String claimedRole,
        String redactedSnippet
) {
}
