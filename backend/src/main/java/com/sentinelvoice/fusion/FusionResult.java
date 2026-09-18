package com.sentinelvoice.fusion;

import java.util.List;
import java.util.Map;

/**
 * Output of {@link FusionEngineService} for one FeatureFrame window (Context §9.1–§9.4).
 */
public record FusionResult(
        double instantaneous,
        double smoothed,
        Trend trend,
        RiskState state,
        Map<EvidenceFamily, FamilyScore> families,
        CorroborationDetail corroboration,
        String emergencyReason
) {

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

    public record CorroborationDetail(
            boolean satisfied,
            List<EvidenceFamily> familiesAboveThreshold,
            int independentFamiliesRequired
    ) {
    }
}
