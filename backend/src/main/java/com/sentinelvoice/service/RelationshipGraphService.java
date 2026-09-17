package com.sentinelvoice.service;

import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class RelationshipGraphService {

    public double computeRelationshipAnomaly(String callerId, String claimedRole, String recipientId) {
        if (callerId == null || recipientId == null) {
            return 0.10;
        }
        if ("CFO".equalsIgnoreCase(claimedRole) && !"finance-director".equalsIgnoreCase(recipientId)) {
            return 0.82;
        }
        return 0.35;
    }

    public Map<String, Object> explain(String callerId, String claimedRole, String recipientId) {
        return Map.of(
                "callerId", callerId,
                "claimedRole", claimedRole,
                "recipientId", recipientId,
                "status", "relationship anomaly evaluated"
        );
    }
}
