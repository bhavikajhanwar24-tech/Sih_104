package com.sentinelvoice.model;

public record ChallengeVerification(
        String sessionId,
        String challengeText,
        String submittedText,
        long issuedAt,
        long respondedAt
) {
}
