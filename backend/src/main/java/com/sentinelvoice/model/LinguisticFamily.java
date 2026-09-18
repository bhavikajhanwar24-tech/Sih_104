package com.sentinelvoice.model;

/**
 * Frozen FeatureFrame.linguistic block. Context §8.1. Mapped, never keyword-scored, in Java.
 *
 * <p>{@code redactedDelta} is optional (null when absent) — redacted new text since the
 * previous slow-path ASR emission. {@code redactedSnippet} remains the rolling window.
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
        String redactedSnippet,
        String redactedDelta
) {
    /** Back-compat constructor when delta is unavailable. */
    public LinguisticFamily(
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
        this(
                available, ageMs, language, urgency, secrecy, authorityInvocation,
                emotionalCoercion, askDetected, ask, claimedIdentity, claimedRole,
                redactedSnippet, null
        );
    }
}
