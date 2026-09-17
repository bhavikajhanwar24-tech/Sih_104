package com.sentinelvoice.model;

public record ChallengeRequest(
        String sessionId,
        String challengeText,
        long issuedAt
) {
}
