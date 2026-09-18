package com.sentinelvoice.context.model;

import java.util.List;

/**
 * Relationship-graph evidence for the RELATIONSHIP fusion family (Context §12).
 */
public record RelationshipAssessment(
        int interactionCount365d,
        boolean firstContact,
        int hierarchyDistance,
        boolean offHoursCall,
        boolean durationAnomaly,
        double score,
        List<String> reasonCodes
) {
}
