package com.sentinelvoice.fusion.engine;

import java.util.List;

/**
 * Pure-function output of one fusion tick (F8).
 */
public record RiskAssessment(
        double score,
        int level,
        List<FamilyContribution> families,
        List<String> corroboratingFamilies,
        List<String> missingFamilies,
        List<Reason> reasons,
        Integer policyVersion,
        Integer fusionConfigVersion,
        boolean ruleFloorApplied,
        boolean emergencyFired,
        double instantaneous,
        Trend trend,
        RiskState state
) {
    public RiskAssessment {
        families = families == null ? List.of() : List.copyOf(families);
        corroboratingFamilies = corroboratingFamilies == null ? List.of() : List.copyOf(corroboratingFamilies);
        missingFamilies = missingFamilies == null ? List.of() : List.copyOf(missingFamilies);
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        score = clamp01(score);
        instantaneous = clamp01(instantaneous);
        if (level < 1) {
            level = 1;
        }
        if (level > 5) {
            level = 5;
        }
        trend = trend == null ? Trend.STABLE : trend;
        state = state == null ? RiskState.SCORED : state;
    }

    public enum Trend {
        RISING,
        FALLING,
        STABLE
    }

    public enum RiskState {
        SCORED,
        INSUFFICIENT_EVIDENCE,
        DEGRADED
    }

    public record FamilyContribution(
            String family,
            double score,
            double weight,
            double confidence,
            double contribution,
            boolean available
    ) {
    }

    public record Reason(String code, String severity, String text) {
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, v));
    }
}
