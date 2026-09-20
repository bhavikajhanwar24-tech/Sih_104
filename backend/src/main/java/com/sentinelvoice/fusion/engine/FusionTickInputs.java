package com.sentinelvoice.fusion.engine;

import java.util.List;

/**
 * Per-tick inputs to {@link FusionEngine} (F8). All family scores are raw [0,1] before
 * staleness / renormalisation inside the engine.
 */
public record FusionTickInputs(
        boolean channelNarrowband,
        long cumulativeSpeechMs,
        boolean speechPresent,
        long nowMs,
        FamilyRaw voice,
        FamilyRaw channel,
        FamilyRaw prosody,
        FamilyRaw linguistic,
        FamilyRaw transaction,
        FamilyRaw relationship,
        long linguisticAgeMs,
        double cosineMismatch,
        double secrecy,
        double authority,
        int policyMinLevel,
        List<PolicyFiredRule> policyFiredRules,
        boolean challengeEmergency
) {
    public FusionTickInputs {
        policyFiredRules = policyFiredRules == null ? List.of() : List.copyOf(policyFiredRules);
        voice = voice == null ? FamilyRaw.unavailable() : voice;
        channel = channel == null ? FamilyRaw.unavailable() : channel;
        prosody = prosody == null ? FamilyRaw.unavailable() : prosody;
        linguistic = linguistic == null ? FamilyRaw.unavailable() : linguistic;
        transaction = transaction == null ? FamilyRaw.unavailable() : transaction;
        relationship = relationship == null ? FamilyRaw.unavailable() : relationship;
        if (policyMinLevel < 0) {
            policyMinLevel = 0;
        }
        if (policyMinLevel > 4) {
            policyMinLevel = 4;
        }
    }

    public record FamilyRaw(boolean available, double score) {
        public FamilyRaw {
            if (available) {
                score = clamp01(score);
            } else {
                score = 0.0;
            }
        }

        public static FamilyRaw available(double score) {
            return new FamilyRaw(true, score);
        }

        public static FamilyRaw unavailable() {
            return new FamilyRaw(false, 0.0);
        }
    }

    public record PolicyFiredRule(String ruleId, String title, String clauseRef, int minLevel) {
        public PolicyFiredRule {
            ruleId = ruleId == null ? "" : ruleId;
            title = title == null ? "" : title;
            clauseRef = clauseRef == null ? "" : clauseRef;
        }
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, v));
    }
}
