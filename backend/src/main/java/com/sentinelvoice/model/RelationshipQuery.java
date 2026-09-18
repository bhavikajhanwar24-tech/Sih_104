package com.sentinelvoice.model;

public record RelationshipQuery(
        String callerId,
        String recipientId,
        String claimedRole
) {
}
