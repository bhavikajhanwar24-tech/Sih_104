package com.sentinelvoice.directory;

import java.time.Instant;
import java.util.List;

/**
 * Known relationship query result (replaces v1 InteractionEdge).
 */
public record RelationshipView(
        int previousContacts,
        Instant lastContactAt,
        List<String> typicalTopics,
        boolean isFirstContact
) {
    public static RelationshipView firstContact() {
        return new RelationshipView(0, null, List.of(), true);
    }
}
