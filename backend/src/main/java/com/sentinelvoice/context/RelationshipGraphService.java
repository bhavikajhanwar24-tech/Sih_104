package com.sentinelvoice.context;

import com.sentinelvoice.context.model.RelationshipAssessment;
import com.sentinelvoice.model.RelationshipQuery;
import org.springframework.stereotype.Service;

/**
 * Relationship graph — persistence re-implemented in F4.
 */
@Service
public class RelationshipGraphService {

    private static final String MSG = "re-implemented in F4";

    public RelationshipAssessment assess(RelationshipQuery query) {
        throw new UnsupportedOperationException(MSG);
    }

    public RelationshipAssessment assessEmployees(
            String callerEmployeeId,
            String calleeEmployeeId,
            Integer callDurationSec
    ) {
        throw new UnsupportedOperationException(MSG);
    }
}
