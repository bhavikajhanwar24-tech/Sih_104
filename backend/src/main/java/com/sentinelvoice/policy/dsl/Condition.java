package com.sentinelvoice.policy.dsl;

import java.util.List;
import java.util.Map;

/**
 * Closed condition grammar — no free-form code.
 */
public sealed interface Condition permits Condition.All, Condition.Any, Condition.Not, Condition.FactPred {

    record All(List<Condition> all) implements Condition {
    }

    record Any(List<Condition> any) implements Condition {
    }

    record Not(Condition not) implements Condition {
    }

    record FactPred(String fact, String op, Object value) implements Condition {
    }

    @SuppressWarnings("unchecked")
    static Condition fromMap(Map<String, Object> m) {
        if (m == null || m.isEmpty()) {
            throw new IllegalArgumentException("empty condition");
        }
        if (m.containsKey("all")) {
            List<Map<String, Object>> kids = (List<Map<String, Object>>) m.get("all");
            return new All(kids.stream().map(Condition::fromMap).toList());
        }
        if (m.containsKey("any")) {
            List<Map<String, Object>> kids = (List<Map<String, Object>>) m.get("any");
            return new Any(kids.stream().map(Condition::fromMap).toList());
        }
        if (m.containsKey("not")) {
            return new Not(fromMap((Map<String, Object>) m.get("not")));
        }
        if (m.containsKey("fact")) {
            return new FactPred(
                    String.valueOf(m.get("fact")),
                    String.valueOf(m.get("op")),
                    m.get("value")
            );
        }
        throw new IllegalArgumentException("unknown condition shape: " + m.keySet());
    }

    default Map<String, Object> toMap() {
        if (this instanceof All a) {
            return Map.of("all", a.all().stream().map(Condition::toMap).toList());
        }
        if (this instanceof Any a) {
            return Map.of("any", a.any().stream().map(Condition::toMap).toList());
        }
        if (this instanceof Not n) {
            return Map.of("not", n.not().toMap());
        }
        FactPred f = (FactPred) this;
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("fact", f.fact());
        m.put("op", f.op());
        if (f.value() != null || !"EXISTS".equals(f.op())) {
            m.put("value", f.value());
        }
        return m;
    }
}
