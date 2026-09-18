package com.sentinelvoice.service;

import com.sentinelvoice.model.RelationshipAssessment;
import com.sentinelvoice.model.RelationshipQuery;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class RelationshipGraphService {

    // TODO(P8.3): replace this in-memory seed with a persisted interaction graph.
    private static final Map<String, RelationshipAssessment> SEED = Map.of(
            key("EMP-10492", "treasury-desk"),
            new RelationshipAssessment(28, false, 1, 0.11, List.of("KNOWN_PAIR", "SAME_DIVISION")),
            key("+91-22-4000-1234", "EMP-10492"),
            new RelationshipAssessment(0, true, 4, 0.87, List.of("FIRST_CONTACT", "HIERARCHY_DISTANCE"))
    );

    public RelationshipAssessment assess(RelationshipQuery query) {
        if (query == null || query.callerId() == null || query.recipientId() == null) {
            return new RelationshipAssessment(0, true, Integer.MAX_VALUE, 0.50, List.of("INSUFFICIENT_IDENTITY"));
        }
        RelationshipAssessment seeded = SEED.get(key(query.callerId(), query.recipientId()));
        if (seeded != null) {
            return seeded;
        }
        return new RelationshipAssessment(0, true, Integer.MAX_VALUE, 0.80, List.of("FIRST_CONTACT"));
    }

    private static String key(String callerId, String recipientId) {
        return callerId + "|" + recipientId;
    }
}
