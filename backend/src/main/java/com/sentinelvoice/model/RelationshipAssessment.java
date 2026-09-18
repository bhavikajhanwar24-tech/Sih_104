package com.sentinelvoice.model;

import java.util.List;

public record RelationshipAssessment(
        int interactionCount365d,
        boolean firstContact,
        int hierarchyDistance,
        double score,
        List<String> reasonCodes
) {
}
