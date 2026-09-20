package com.sentinelvoice.fusion.engine;

import com.sentinelvoice.fusion.config.FusionConfigDocument;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure fusion engine (F8). No mutable fields — {@link #tick} is a pure function of
 * (config, state, inputs) → (state′, assessment).
 */
public final class FusionEngine {

    private static final Set<String> ACOUSTIC = Set.of("voice", "channel", "prosody");
    private static final Set<String> CONTEXTUAL = Set.of("linguistic", "transaction", "relationship");

    private FusionEngine() {
    }

    public record TickResult(FusionTickState state, RiskAssessment assessment) {
    }

    public static TickResult tick(
            FusionConfigDocument config,
            FusionTickState state,
            FusionTickInputs inputs,
            Integer fusionConfigVersion,
            Integer policyVersion
    ) {
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        if (state == null) {
            state = FusionTickState.initial(inputs == null ? 0L : inputs.nowMs());
        }
        if (inputs == null) {
            throw new IllegalArgumentException("inputs are required");
        }

        // (a)+(d) per-family scores with linguistic staleness confidence; unavailable excluded
        Map<String, WorkingFamily> working = extractFamilies(config, inputs);
        List<String> missing = new ArrayList<>();
        for (String key : FusionConfigDocument.FAMILY_KEYS) {
            if (!working.get(key).available()) {
                missing.add(key);
            }
        }

        // (c) weighted sum with renormalisation → instantaneous / baseRisk
        double numerator = 0.0;
        double denominator = 0.0;
        int availableCount = 0;
        for (String key : FusionConfigDocument.FAMILY_KEYS) {
            WorkingFamily wf = working.get(key);
            double weight = config.weight(key, inputs.channelNarrowband());
            if (wf.available()) {
                availableCount++;
                double term = weight * wf.confidence();
                numerator += term * wf.score();
                denominator += term;
            }
            working.put(key, wf.withWeight(weight));
        }
        double instantaneous = denominator > 0.0 ? numerator / denominator : 0.0;
        List<RiskAssessment.FamilyContribution> families = new ArrayList<>();
        for (String key : FusionConfigDocument.FAMILY_KEYS) {
            WorkingFamily wf = working.get(key);
            double contribution = wf.available() && denominator > 0.0
                    ? (wf.weight() * wf.confidence() * wf.score()) / denominator
                    : 0.0;
            families.add(new RiskAssessment.FamilyContribution(
                    key, wf.score(), wf.weight(), wf.confidence(), contribution, wf.available()
            ));
        }

        // (g) emergency first so EMA can snap (lambda=0)
        EmergencyHit emergency = evaluateEmergency(config, inputs, instantaneous);
        boolean emergencyFired = emergency != null || inputs.challengeEmergency();
        int emergencyTarget = emergency != null
                ? emergency.targetLevel()
                : (inputs.challengeEmergency() ? 3 : 0);

        // (b) asymmetric EMA; freeze on silence; snap on emergency
        double previousSmoothed = state.smoothed();
        double smoothed = smooth(config, state, instantaneous, inputs.speechPresent(), emergencyFired);
        RiskAssessment.Trend trend = trendOf(previousSmoothed, smoothed, state.level() == 1 && previousSmoothed == 0.0
                && instantaneous == smoothed);

        // (e) corroboration + acoustic-alone hard floor
        List<String> corroborating = new ArrayList<>();
        boolean acousticHit = false;
        boolean contextualHit = false;
        for (RiskAssessment.FamilyContribution f : families) {
            if (!f.available()) {
                continue;
            }
            double threshold = config.familyThreshold(f.family());
            if (f.score() > threshold) {
                corroborating.add(f.family());
                if (ACOUSTIC.contains(f.family())) {
                    acousticHit = true;
                }
                if (CONTEXTUAL.contains(f.family())) {
                    contextualHit = true;
                }
            }
        }
        int corroborationCount = corroborating.size();

        int levelFromScore = desiredFromScore(
                config, smoothed, corroborationCount, acousticHit, contextualHit
        );

        // (f) policy rule floor
        int policyFloor = Math.max(0, Math.min(4, inputs.policyMinLevel()));
        boolean ruleFloorApplied = policyFloor > levelFromScore;
        int desired = Math.max(levelFromScore, policyFloor);

        List<RiskAssessment.Reason> reasons = new ArrayList<>();
        if (ruleFloorApplied) {
            for (FusionTickInputs.PolicyFiredRule rule : inputs.policyFiredRules()) {
                if (rule.minLevel() >= policyFloor && policyFloor > 0) {
                    String clause = rule.clauseRef().isBlank() ? "policy clause" : rule.clauseRef();
                    reasons.add(new RiskAssessment.Reason(
                            "POLICY_FLOOR",
                            "HIGH",
                            "Level raised to L" + policyFloor + " by policy rule "
                                    + rule.ruleId()
                                    + (rule.title().isBlank() ? "" : " (" + rule.title() + ")")
                                    + " citing " + clause + "."
                    ));
                    break;
                }
            }
            if (reasons.isEmpty()) {
                reasons.add(new RiskAssessment.Reason(
                        "POLICY_FLOOR",
                        "HIGH",
                        "Level raised to L" + policyFloor + " by approved policy rule floor."
                ));
            }
        }
        if (emergency != null) {
            reasons.add(new RiskAssessment.Reason(
                    "EMERGENCY_" + emergency.ruleId().toUpperCase(Locale.ROOT),
                    "CRITICAL",
                    "Emergency rule '" + emergency.ruleId() + "' fired → L" + emergency.targetLevel() + "."
            ));
        } else if (inputs.challengeEmergency()) {
            reasons.add(new RiskAssessment.Reason(
                    "EMERGENCY_CHALLENGE",
                    "CRITICAL",
                    "Challenge failure triggered emergency escalation."
            ));
        }

        if (emergencyFired && emergencyTarget > desired) {
            desired = Math.min(4, emergencyTarget);
        }

        // (h) hysteresis + dwell + override; L5 only via analyst override path
        FusionTickState nextState = applyLadder(config, state, inputs, desired, smoothed, emergencyFired);
        int finalLevel = nextState.level();

        RiskAssessment.RiskState riskState = resolveState(
                config, inputs.cumulativeSpeechMs(), availableCount
        );

        // (i) output
        RiskAssessment assessment = new RiskAssessment(
                smoothed,
                finalLevel,
                families,
                List.copyOf(corroborating),
                List.copyOf(missing),
                List.copyOf(reasons),
                policyVersion,
                fusionConfigVersion,
                ruleFloorApplied,
                emergencyFired,
                instantaneous,
                trend,
                riskState
        );
        return new TickResult(nextState, assessment);
    }

    private static Map<String, WorkingFamily> extractFamilies(
            FusionConfigDocument config,
            FusionTickInputs inputs
    ) {
        Map<String, WorkingFamily> map = new LinkedHashMap<>();
        map.put("voice", fromRaw(inputs.voice(), 1.0));
        map.put("channel", fromRaw(inputs.channel(), 1.0));
        map.put("prosody", fromRaw(inputs.prosody(), 1.0));

        FusionTickInputs.FamilyRaw ling = inputs.linguistic();
        if (ling.available()) {
            double tau = Math.max(1.0, config.smoothing().linguisticStalenessTauMs());
            double confidence = Math.exp(-((double) Math.max(0L, inputs.linguisticAgeMs())) / tau);
            map.put("linguistic", new WorkingFamily(true, ling.score(), clamp01(confidence), 0.0));
        } else {
            map.put("linguistic", WorkingFamily.unavailable());
        }
        map.put("transaction", fromRaw(inputs.transaction(), 1.0));
        map.put("relationship", fromRaw(inputs.relationship(), 1.0));
        return map;
    }

    private static WorkingFamily fromRaw(FusionTickInputs.FamilyRaw raw, double confidence) {
        if (raw == null || !raw.available()) {
            return WorkingFamily.unavailable();
        }
        return new WorkingFamily(true, raw.score(), clamp01(confidence), 0.0);
    }

    private static double smooth(
            FusionConfigDocument config,
            FusionTickState state,
            double instantaneous,
            boolean speechPresent,
            boolean emergency
    ) {
        // First real sample (initial state)
        if (state.level() == 1 && state.smoothed() == 0.0 && state.levelEnteredAtMs() > 0
                && instantaneous >= 0.0 && !Double.isNaN(instantaneous)) {
            // Still apply EMA after first tick; first adoption is instantaneous
        }
        double previous = state.smoothed();
        // Detect "fresh" session: never updated — adopt instantaneous
        // Callers start with smoothed=0; first speech tick should seed.
        if (!speechPresent && previous == 0.0 && instantaneous == 0.0) {
            return 0.0;
        }
        if (!speechPresent) {
            return previous;
        }
        if (emergency) {
            return instantaneous;
        }
        // Seed on first speech
        if (previous == 0.0 && instantaneous > 0.0 && state.level() == 1) {
            // still use EMA for consistency after first non-zero; use instantaneous seed
            return instantaneous;
        }
        double lambda = instantaneous > previous
                ? config.smoothing().lambdaUp()
                : config.smoothing().lambdaDown();
        return clamp01(lambda * previous + (1.0 - lambda) * instantaneous);
    }

    private static int desiredFromScore(
            FusionConfigDocument config,
            double smoothed,
            int corroborationCount,
            boolean acousticHit,
            boolean contextualHit
    ) {
        FusionConfigDocument.LevelBand l2 = config.level("L2");
        FusionConfigDocument.LevelBand l3 = config.level("L3");
        FusionConfigDocument.LevelBand l4 = config.level("L4");

        int desired = 1;
        if (smoothed >= l2.enter()) {
            desired = 2;
        }
        if (smoothed >= l3.enter()
                && corroborationCount >= config.corroboration().minIndependentFamiliesForL3()) {
            desired = 3;
        }
        if (smoothed >= l4.enter()
                && corroborationCount >= config.corroboration().minForL4()) {
            desired = 4;
        }
        // Acoustic-only hard floor: without a contextual corroborator, cap level
        if (!contextualHit) {
            desired = Math.min(desired, config.hardFloors().acousticAloneMaxLevel());
        }
        return desired;
    }

    private static FusionTickState applyLadder(
            FusionConfigDocument config,
            FusionTickState state,
            FusionTickInputs inputs,
            int desired,
            double smoothed,
            boolean emergency
    ) {
        long now = inputs.nowMs();
        int current = state.level();

        // L5 is terminal for automatic paths
        if (current == 5) {
            return new FusionTickState(smoothed, 5, state.levelEnteredAtMs(),
                    state.overrideExpiresAtMs(), state.overrideLevel());
        }

        Long overrideExp = state.overrideExpiresAtMs();
        if (overrideExp != null && now >= overrideExp) {
            overrideExp = null;
        }
        boolean overrideActive = overrideExp != null;

        if (overrideActive) {
            // Pin level; still update smoothed score
            return new FusionTickState(
                    smoothed,
                    state.overrideLevel() > 0 ? state.overrideLevel() : current,
                    state.levelEnteredAtMs(),
                    overrideExp,
                    state.overrideLevel()
            );
        }

        desired = Math.max(1, Math.min(4, desired));

        if (desired == current) {
            return new FusionTickState(smoothed, current, state.levelEnteredAtMs(), null, state.overrideLevel());
        }

        if (desired > current) {
            int target = emergency ? desired : Math.min(desired, current + 1);
            // Dwell before escalation (except emergency)
            if (!emergency) {
                long dwell = dwellForLevel(config, current);
                long elapsed = now - state.levelEnteredAtMs();
                if (elapsed < dwell) {
                    return new FusionTickState(smoothed, current, state.levelEnteredAtMs(), null, state.overrideLevel());
                }
            }
            return new FusionTickState(smoothed, target, now, null, state.overrideLevel());
        }

        // De-escalate: one step down per dwell; hysteresis exit threshold
        long dwell = dwellForLevel(config, current);
        long elapsed = now - state.levelEnteredAtMs();
        if (elapsed < dwell) {
            return new FusionTickState(smoothed, current, state.levelEnteredAtMs(), null, state.overrideLevel());
        }
        double exit = exitThreshold(config, current);
        if (smoothed > exit) {
            return new FusionTickState(smoothed, current, state.levelEnteredAtMs(), null, state.overrideLevel());
        }
        int oneDown = Math.max(1, current - 1);
        return new FusionTickState(smoothed, oneDown, now, null, state.overrideLevel());
    }

    private static long dwellForLevel(FusionConfigDocument config, int level) {
        return switch (level) {
            case 2 -> config.level("L2").minDwellMs();
            case 3 -> config.level("L3").minDwellMs();
            case 4 -> config.level("L4").minDwellMs();
            default -> config.level("L1").minDwellMs();
        };
    }

    private static double exitThreshold(FusionConfigDocument config, int level) {
        return switch (level) {
            case 2 -> config.level("L2").exit();
            case 3 -> config.level("L3").exit();
            case 4 -> config.level("L4").exit();
            default -> config.level("L1").exit();
        };
    }

    private static EmergencyHit evaluateEmergency(
            FusionConfigDocument config,
            FusionTickInputs inputs,
            double transactionFallback
    ) {
        if (!config.emergency().enabled()) {
            return null;
        }
        double txn = inputs.transaction().available()
                ? inputs.transaction().score()
                : transactionFallback;
        for (FusionConfigDocument.EmergencyRule rule : config.emergency().rules()) {
            if (inputs.cosineMismatch() >= rule.cosineMismatchMin()
                    && inputs.secrecy() >= rule.secrecyMin()
                    && inputs.authority() >= rule.authorityMin()
                    && txn >= rule.transactionScoreMin()) {
                return new EmergencyHit(rule.id(), Math.max(1, Math.min(4, rule.targetLevel())));
            }
        }
        return null;
    }

    private static RiskAssessment.RiskState resolveState(
            FusionConfigDocument config,
            long cumulativeSpeechMs,
            int availableCount
    ) {
        if (cumulativeSpeechMs < config.insufficientEvidence().minSpeechMs()) {
            return RiskAssessment.RiskState.INSUFFICIENT_EVIDENCE;
        }
        if (availableCount < 3) {
            return RiskAssessment.RiskState.DEGRADED;
        }
        return RiskAssessment.RiskState.SCORED;
    }

    private static RiskAssessment.Trend trendOf(double previous, double current, boolean firstSeed) {
        if (firstSeed) {
            return RiskAssessment.Trend.STABLE;
        }
        double delta = current - previous;
        if (Math.abs(delta) < 1e-12) {
            return RiskAssessment.Trend.STABLE;
        }
        return delta > 0 ? RiskAssessment.Trend.RISING : RiskAssessment.Trend.FALLING;
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, v));
    }

    private record EmergencyHit(String ruleId, int targetLevel) {
    }

    private record WorkingFamily(boolean available, double score, double confidence, double weight) {
        WorkingFamily withWeight(double w) {
            return new WorkingFamily(available, score, confidence, w);
        }

        static WorkingFamily unavailable() {
            return new WorkingFamily(false, 0.0, 0.0, 0.0);
        }
    }
}
