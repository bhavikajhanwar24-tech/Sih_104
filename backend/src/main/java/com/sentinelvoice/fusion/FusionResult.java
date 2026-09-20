package com.sentinelvoice.fusion;

import com.sentinelvoice.fusion.engine.RiskAssessment;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Compatibility adapter over {@link RiskAssessment} for TelemetryFrameBuilder and legacy callers.
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

    public static FusionResult from(RiskAssessment assessment) {
        Map<EvidenceFamily, FamilyScore> familyMap = new EnumMap<>(EvidenceFamily.class);
        for (RiskAssessment.FamilyContribution f : assessment.families()) {
            EvidenceFamily family = EvidenceFamily.fromConfigKey(f.family());
            familyMap.put(family, new FamilyScore(
                    family, f.score(), f.weight(), f.confidence(), f.contribution(), f.available()
            ));
        }
        List<EvidenceFamily> corr = new ArrayList<>();
        for (String key : assessment.corroboratingFamilies()) {
            corr.add(EvidenceFamily.fromConfigKey(key));
        }
        boolean satisfied = corr.size() >= 2;
        Trend trend = switch (assessment.trend()) {
            case RISING -> Trend.RISING;
            case FALLING -> Trend.FALLING;
            case STABLE -> Trend.STABLE;
        };
        RiskState state = switch (assessment.state()) {
            case SCORED -> RiskState.SCORED;
            case INSUFFICIENT_EVIDENCE -> RiskState.INSUFFICIENT_EVIDENCE;
            case DEGRADED -> RiskState.DEGRADED;
        };
        String emergency = assessment.emergencyFired()
                ? assessment.reasons().stream()
                .filter(r -> r.code() != null && r.code().startsWith("EMERGENCY"))
                .map(RiskAssessment.Reason::code)
                .findFirst()
                .orElse("EMERGENCY")
                : null;
        return new FusionResult(
                assessment.instantaneous(),
                assessment.score(),
                trend,
                state,
                Map.copyOf(familyMap),
                new CorroborationDetail(satisfied, List.copyOf(corr), 2),
                emergency
        );
    }
}
