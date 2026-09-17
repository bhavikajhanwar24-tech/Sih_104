package com.sentinelvoice.model;

import java.util.Map;

public record RiskAssessmentResult(
        String sessionId,
        double totalRisk,
        Map<String, Double> factorBreakdown,
        String explanation
) {
}
