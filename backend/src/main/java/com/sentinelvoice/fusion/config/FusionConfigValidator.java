package com.sentinelvoice.fusion.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Server-side guardrails for fusion config documents (F8).
 * Empty list means valid.
 */
public final class FusionConfigValidator {

    private static final double WEIGHT_SUM_TOLERANCE = 0.001;
    private static final double MAX_WEIGHT = 0.6;
    /**
     * Acoustic detectors (voice / channel / prosody) are unreliable in the wild —
     * their combined weight must stay ≤ 0.55 so contextual evidence can still outweigh them.
     */
    private static final double MAX_ACOUSTIC_TOTAL = 0.55;
    private static final long MIN_L3_L4_DWELL_MS = 1000L;

    private FusionConfigValidator() {
    }

    public static List<String> validate(FusionConfigDocument doc) {
        List<String> violations = new ArrayList<>();
        if (doc == null) {
            violations.add("config is required");
            return violations;
        }
        validateWeightProfile(doc.weights().wideband(), "weights.wideband", violations);
        validateWeightProfile(doc.weights().narrowband(), "weights.narrowband", violations);

        FusionConfigDocument.Smoothing sm = doc.smoothing();
        if (!(sm.lambdaUp() > 0.0 && sm.lambdaUp() <= 1.0)) {
            violations.add("smoothing.lambdaUp must be in (0, 1]");
        }
        if (!(sm.lambdaDown() > 0.0 && sm.lambdaDown() <= 1.0)) {
            violations.add("smoothing.lambdaDown must be in (0, 1]");
        }
        if (sm.linguisticStalenessTauMs() < 1L) {
            violations.add("smoothing.linguisticStalenessTauMs must be >= 1");
        }

        for (String key : FusionConfigDocument.FAMILY_KEYS) {
            if (!doc.familyThresholds().containsKey(key)) {
                violations.add("familyThresholds." + key + " is required");
            } else {
                double t = doc.familyThresholds().get(key);
                if (t < 0.0 || t > 1.0) {
                    violations.add("familyThresholds." + key + " must be in [0, 1]");
                }
            }
        }

        FusionConfigDocument.LevelBand l1 = doc.levels().get("L1");
        FusionConfigDocument.LevelBand l2 = doc.levels().get("L2");
        FusionConfigDocument.LevelBand l3 = doc.levels().get("L3");
        FusionConfigDocument.LevelBand l4 = doc.levels().get("L4");
        if (l1 == null || l2 == null || l3 == null || l4 == null) {
            violations.add("levels L1–L4 are required");
        } else {
            if (!(l1.enter() < l2.enter() && l2.enter() < l3.enter() && l3.enter() < l4.enter())) {
                violations.add("level enter thresholds must be strictly increasing L1 < L2 < L3 < L4");
            }
            validateBand(l1, "L1", violations);
            validateBand(l2, "L2", violations);
            validateBand(l3, "L3", violations);
            validateBand(l4, "L4", violations);
            if (l3.minDwellMs() < MIN_L3_L4_DWELL_MS) {
                violations.add("levels.L3.minDwellMs must be >= 1000");
            }
            if (l4.minDwellMs() < MIN_L3_L4_DWELL_MS) {
                violations.add("levels.L4.minDwellMs must be >= 1000");
            }
        }

        if (doc.corroboration().minForL4() < 2) {
            violations.add("corroboration.minForL4 must be >= 2 (L4 cannot be a single-family trigger)");
        }
        if (doc.corroboration().minIndependentFamiliesForL3() < 1) {
            violations.add("corroboration.minIndependentFamiliesForL3 must be >= 1");
        }

        int acousticCap = doc.hardFloors().acousticAloneMaxLevel();
        if (acousticCap < 1 || acousticCap > 4) {
            violations.add("hardFloors.acousticAloneMaxLevel must be in [1, 4]");
        }
        if (doc.overridePinDurationMs() < 1L) {
            violations.add("overridePinDurationMs must be >= 1");
        }
        if (doc.insufficientEvidence().minSpeechMs() < 0L) {
            violations.add("insufficientEvidence.minSpeechMs must be >= 0");
        }

        for (FusionConfigDocument.EmergencyRule rule : doc.emergency().rules()) {
            if (rule.id() == null || rule.id().isBlank()) {
                violations.add("emergency.rules[].id is required");
            }
            if (rule.targetLevel() < 1 || rule.targetLevel() > 4) {
                violations.add("emergency.rules[].targetLevel must be in [1, 4]");
            }
        }
        return List.copyOf(violations);
    }

    private static void validateWeightProfile(Map<String, Double> weights, String path, List<String> violations) {
        if (weights == null || weights.isEmpty()) {
            violations.add(path + " is required");
            return;
        }
        for (String key : FusionConfigDocument.FAMILY_KEYS) {
            if (!weights.containsKey(key)) {
                violations.add(path + "." + key + " is required");
                continue;
            }
            double w = weights.get(key);
            if (w < 0.0 || w > MAX_WEIGHT) {
                violations.add(path + "." + key + " must be in [0, 0.6]");
            }
        }
        double sum = 0.0;
        for (Double v : weights.values()) {
            if (v != null) {
                sum += v;
            }
        }
        if (Math.abs(sum - 1.0) > WEIGHT_SUM_TOLERANCE) {
            violations.add(path + " must sum to 1.0 ± 0.001 (got " + sum + ")");
        }
        double acoustic = nz(weights.get("voice")) + nz(weights.get("channel")) + nz(weights.get("prosody"));
        if (acoustic > MAX_ACOUSTIC_TOTAL + WEIGHT_SUM_TOLERANCE) {
            violations.add(path
                    + " acoustic total (voice+channel+prosody) must be <= 0.55 "
                    + "(detectors are unreliable in the wild); got " + acoustic);
        }
    }

    private static void validateBand(FusionConfigDocument.LevelBand band, String key, List<String> violations) {
        if (band.exit() >= band.enter()) {
            violations.add("levels." + key + ".exit must be < enter (hysteresis)");
        }
        if (band.enter() < 0.0 || band.enter() > 1.0 || band.exit() < 0.0 || band.exit() > 1.0) {
            violations.add("levels." + key + " enter/exit must be in [0, 1]");
        }
        if (band.minDwellMs() < 0L) {
            violations.add("levels." + key + ".minDwellMs must be >= 0");
        }
    }

    private static double nz(Double v) {
        return v == null ? 0.0 : v;
    }
}
