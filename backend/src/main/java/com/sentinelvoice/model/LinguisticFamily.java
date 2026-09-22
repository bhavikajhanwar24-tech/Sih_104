package com.sentinelvoice.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Frozen FeatureFrame.linguistic block (F11).
 *
 * <p>Live sessions must not carry transcript text ({@code redactedSnippet}/{@code redactedDelta}
 * empty). Structured extraction is numbers/enums only — plus matched keyword terms from the
 * ACTIVE tenant lexicon (not a transcript).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
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
        String redactedDelta,
        String source,
        Long observedAt,
        Double confidence,
        Map<String, Double> categories,
        List<String> matchedRuleIds,
        List<String> matchedKeywords,
        Boolean injectionAttempt,
        Boolean llmPending
) {
    /** Back-compat when only pre-F11 fields are present. */
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
                redactedSnippet, null, null, null, null, null, null, null, null, null
        );
    }

    /** Back-compat with redactedDelta. */
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
            String redactedSnippet,
            String redactedDelta
    ) {
        this(
                available, ageMs, language, urgency, secrecy, authorityInvocation,
                emotionalCoercion, askDetected, ask, claimedIdentity, claimedRole,
                redactedSnippet, redactedDelta, null, null, null, null, null, null, null, null
        );
    }

    /** Back-compat before matchedKeywords (F12 keyword UI). */
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
            String redactedSnippet,
            String redactedDelta,
            String source,
            Long observedAt,
            Double confidence,
            Map<String, Double> categories,
            List<String> matchedRuleIds,
            Boolean injectionAttempt,
            Boolean llmPending
    ) {
        this(
                available, ageMs, language, urgency, secrecy, authorityInvocation,
                emotionalCoercion, askDetected, ask, claimedIdentity, claimedRole,
                redactedSnippet, redactedDelta, source, observedAt, confidence,
                categories, matchedRuleIds, null, injectionAttempt, llmPending
        );
    }

    public boolean llmUnavailable() {
        if (!available) {
            return true;
        }
        if (Boolean.TRUE.equals(llmPending)) {
            return false;
        }
        return "STAGE_A".equalsIgnoreCase(source == null ? "" : source)
                && (confidence == null || confidence < 0.6);
    }
}
