package com.sentinelvoice.context;

import com.sentinelvoice.context.model.CorrelationResult;
import com.sentinelvoice.context.model.CrossChannelEvent;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Cross-channel correlation — persistence re-implemented in F15.
 */
@Service
public class CrossChannelCorrelationService {

    public static final String DEMO_CALLEE_EMPLOYEE_ID = "EMP-50040";
    public static final String DEMO_CAMPAIGN_ID = "BEC-CFO-2026-09";

    private static final String MSG = "re-implemented in F15";

    public Map<String, Object> ingest(CrossChannelEvent event) {
        throw new UnsupportedOperationException(MSG);
    }

    public CorrelationResult correlateSession(String sessionId, Integer windowHoursOverride) {
        throw new UnsupportedOperationException(MSG);
    }

    public CorrelationResult correlateEmployee(String targetEmployeeId, int windowHours) {
        throw new UnsupportedOperationException(MSG);
    }

    public double blendRelationshipScore(double graphScore, CorrelationResult crossChannel) {
        throw new UnsupportedOperationException(MSG);
    }

    public void seedDemoCampaignIfEmpty() {
        throw new UnsupportedOperationException(MSG);
    }
}
