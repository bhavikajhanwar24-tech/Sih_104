package com.sentinelvoice.context;

import com.sentinelvoice.context.model.RelationshipAssessment;
import com.sentinelvoice.directory.DirectoryMatch;
import com.sentinelvoice.directory.DirectoryService;
import com.sentinelvoice.directory.RelationshipService;
import com.sentinelvoice.directory.RelationshipView;
import com.sentinelvoice.model.RelationshipQuery;
import com.sentinelvoice.security.TenantContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Relationship evidence for fusion — backed by F4 known_relationships.
 */
@Service
public class RelationshipGraphService {

    private final RelationshipService relationshipService;
    private final DirectoryService directoryService;

    public RelationshipGraphService(
            RelationshipService relationshipService,
            DirectoryService directoryService
    ) {
        this.relationshipService = relationshipService;
        this.directoryService = directoryService;
    }

    public RelationshipAssessment assess(RelationshipQuery query) {
        UUID tenantId = TenantContext.get() == null ? null : TenantContext.require().tenantId();
        if (tenantId == null || query == null) {
            return firstContactAssessment();
        }
        UUID fromId = resolveEmployee(tenantId, query.callerId());
        UUID toId = resolveEmployee(tenantId, query.recipientId());
        return assessEmployees(
                fromId == null ? null : fromId.toString(),
                toId == null ? null : toId.toString(),
                null
        );
    }

    public RelationshipAssessment assessEmployees(
            String callerEmployeeId,
            String calleeEmployeeId,
            Integer callDurationSec
    ) {
        UUID tenantId = TenantContext.get() == null ? null : TenantContext.require().tenantId();
        if (tenantId == null || callerEmployeeId == null || callerEmployeeId.isBlank()) {
            return firstContactAssessment();
        }
        UUID from;
        UUID to = null;
        try {
            from = UUID.fromString(callerEmployeeId);
            if (calleeEmployeeId != null && !calleeEmployeeId.isBlank()) {
                to = UUID.fromString(calleeEmployeeId);
            }
        } catch (IllegalArgumentException e) {
            return firstContactAssessment();
        }
        RelationshipView view = relationshipService.query(tenantId, from, to, null);
        List<String> reasons = new ArrayList<>();
        double score;
        if (view.isFirstContact()) {
            score = 0.2;
            reasons.add("FIRST_CONTACT");
        } else {
            score = 0.05;
            reasons.add("KNOWN_RELATIONSHIP");
        }
        if (callDurationSec != null && callDurationSec > 0 && callDurationSec < 20) {
            score += 0.05;
            reasons.add("SHORT_DURATION");
        }
        return new RelationshipAssessment(
                view.previousContacts(),
                view.isFirstContact(),
                0,
                false,
                false,
                score,
                reasons
        );
    }

    private UUID resolveEmployee(UUID tenantId, String numberOrId) {
        if (numberOrId == null || numberOrId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(numberOrId);
        } catch (IllegalArgumentException ignored) {
            // treat as CLI / extension
        }
        DirectoryMatch match = directoryService.resolve(tenantId, numberOrId, null, null);
        return match.matchedEmployeeId();
    }

    private static RelationshipAssessment firstContactAssessment() {
        return new RelationshipAssessment(0, true, 0, false, false, 0.2, List.of("FIRST_CONTACT"));
    }
}
