package com.sentinelvoice.policy.engine;

import java.util.List;
import java.util.Map;

/**
 * Output of {@link RuleEngine#evaluate} (F7).
 */
public record RuleEvaluation(
        String state,
        List<FiredRule> firedRules,
        List<UndeterminedRule> undeterminedRules,
        int minLevel,
        double policyScore,
        List<String> unknownFacts,
        long evaluationMicros,
        Integer policyVersion,
        String policySha,
        boolean simulation
) {
    public static final String STATE_OK = "OK";
    public static final String STATE_NO_POLICY = "NO_POLICY";

    public record FiredRule(
            String ruleId,
            String title,
            String severity,
            String reasonCode,
            Map<String, Object> sourceRef,
            int minLevel,
            double scoreBoost
    ) {
    }

    public record UndeterminedRule(
            String ruleId,
            String title,
            List<String> missingFacts
    ) {
    }

    public static RuleEvaluation noPolicy(long micros, boolean simulation) {
        return new RuleEvaluation(
                STATE_NO_POLICY,
                List.of(),
                List.of(),
                0,
                0.0,
                List.of(),
                micros,
                null,
                null,
                simulation
        );
    }

    public Map<String, Object> toApiMap() {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("schemaVersion", "2");
        m.put("state", state);
        m.put("firedRules", firedRules.stream().map(f -> {
            java.util.LinkedHashMap<String, Object> r = new java.util.LinkedHashMap<>();
            r.put("ruleId", f.ruleId());
            r.put("title", f.title());
            r.put("severity", f.severity());
            r.put("reasonCode", f.reasonCode());
            r.put("sourceRef", f.sourceRef() == null ? Map.of() : f.sourceRef());
            r.put("minLevel", f.minLevel());
            r.put("scoreBoost", f.scoreBoost());
            return r;
        }).toList());
        m.put("undeterminedRules", undeterminedRules.stream().map(u -> {
            java.util.LinkedHashMap<String, Object> r = new java.util.LinkedHashMap<>();
            r.put("ruleId", u.ruleId());
            r.put("title", u.title());
            r.put("missingFacts", u.missingFacts());
            return r;
        }).toList());
        m.put("minLevel", minLevel);
        m.put("policyScore", policyScore);
        m.put("unknownFacts", unknownFacts);
        m.put("evaluationMicros", evaluationMicros);
        m.put("policyVersion", policyVersion);
        m.put("policySha", policySha);
        m.put("simulation", simulation);
        return m;
    }
}
