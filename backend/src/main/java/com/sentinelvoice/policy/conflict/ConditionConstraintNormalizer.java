package com.sentinelvoice.policy.conflict;

import com.sentinelvoice.policy.dsl.ConditionEnglish;
import com.sentinelvoice.policy.dsl.FactCatalogue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Normalises a rule's {@code when} tree into per-fact constraints for deterministic conflict detection.
 * Uses registry keys only — no domain prose in the constraint model.
 */
public final class ConditionConstraintNormalizer {

    private ConditionConstraintNormalizer() {
    }

    public record NormalizedRule(
            String ruleId,
            String uuid,
            Set<String> actionTypes,
            Map<String, FactConstraint> facts,
            int minLevel,
            ModalityClass modality,
            Set<String> factKeys,
            String contentSha
    ) {
        public boolean sharesScope(NormalizedRule other) {
            if (other == null) {
                return false;
            }
            if (!actionTypes.isEmpty() && !other.actionTypes.isEmpty()) {
                Set<String> inter = new HashSet<>(actionTypes);
                inter.retainAll(other.actionTypes);
                if (!inter.isEmpty()) {
                    return true;
                }
            }
            Set<String> shared = new HashSet<>(factKeys);
            shared.retainAll(other.factKeys);
            shared.remove("ask.type");
            return !shared.isEmpty();
        }
    }

    public sealed interface FactConstraint permits NumericInterval, EnumSetConstraint, BoolConstraint {
        String fact();

        boolean overlaps(FactConstraint other);

        boolean equivalent(FactConstraint other);

        /** True if this constraint's satisfying set contains {@code other}'s. */
        boolean subsumes(FactConstraint other);

        boolean contradicts(FactConstraint other);

        String plainOverlap(FactConstraint other);
    }

    public record NumericInterval(String fact, double lo, double hi, boolean loInc, boolean hiInc)
            implements FactConstraint {
        public static NumericInterval unbounded(String fact) {
            return new NumericInterval(fact, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, false, false);
        }

        @Override
        public boolean overlaps(FactConstraint other) {
            if (!(other instanceof NumericInterval n) || !fact.equals(n.fact)) {
                return false;
            }
            if (hi < n.lo || n.hi < lo) {
                return false;
            }
            if (hi == n.lo) {
                return hiInc && n.loInc;
            }
            if (n.hi == lo) {
                return n.hiInc && loInc;
            }
            return true;
        }

        @Override
        public boolean equivalent(FactConstraint other) {
            if (!(other instanceof NumericInterval n) || !fact.equals(n.fact)) {
                return false;
            }
            return Double.compare(lo, n.lo) == 0
                    && Double.compare(hi, n.hi) == 0
                    && loInc == n.loInc
                    && hiInc == n.hiInc;
        }

        @Override
        public boolean subsumes(FactConstraint other) {
            if (!(other instanceof NumericInterval n) || !fact.equals(n.fact)) {
                return false;
            }
            boolean loOk = lo < n.lo || (lo == n.lo && (!loInc || n.loInc));
            boolean hiOk = hi > n.hi || (hi == n.hi && (!hiInc || n.hiInc));
            // this interval contains other: this.lo <= other.lo and this.hi >= other.hi
            loOk = lo < n.lo || (lo == n.lo && (loInc || !n.loInc));
            hiOk = hi > n.hi || (hi == n.hi && (hiInc || !n.hiInc));
            return loOk && hiOk && !equivalent(n);
        }

        @Override
        public boolean contradicts(FactConstraint other) {
            return other instanceof NumericInterval n && fact.equals(n.fact) && !overlaps(n);
        }

        @Override
        public String plainOverlap(FactConstraint other) {
            if (!(other instanceof NumericInterval n)) {
                return fact + " numeric region";
            }
            double a = Math.max(lo, n.lo);
            double b = Math.min(hi, n.hi);
            if (a == Double.NEGATIVE_INFINITY && b == Double.POSITIVE_INFINITY) {
                return fact + " any value";
            }
            if (a == b) {
                return fact + " = " + formatNum(a);
            }
            return fact + " in [" + formatNum(a) + ", " + formatNum(b) + "]";
        }

        private static String formatNum(double v) {
            if (Double.isInfinite(v)) {
                return v > 0 ? "∞" : "-∞";
            }
            long round = Math.round(v);
            if (Math.abs(v - round) < 1e-9) {
                // Indian grouping via ConditionEnglish amount phrasing
                return "INR " + formatIndian(round);
            }
            return String.valueOf(v);
        }

        private static String formatIndian(long n) {
            if (n < 0) {
                return "-" + formatIndian(-n);
            }
            String s = Long.toString(n);
            if (s.length() <= 3) {
                return s;
            }
            StringBuilder sb = new StringBuilder();
            int last = s.length();
            sb.insert(0, s.substring(Math.max(0, last - 3), last));
            last -= 3;
            while (last > 0) {
                int start = Math.max(0, last - 2);
                sb.insert(0, s.substring(start, last) + ",");
                last = start;
            }
            return sb.toString();
        }
    }

    public record EnumSetConstraint(String fact, Set<String> values, boolean negated)
            implements FactConstraint {
        @Override
        public boolean overlaps(FactConstraint other) {
            if (!(other instanceof EnumSetConstraint e) || !fact.equals(e.fact)) {
                return false;
            }
            Set<String> a = effective(values, negated);
            Set<String> b = effective(e.values, e.negated);
            if (a == null || b == null) {
                return true; // unbounded / unknown universe
            }
            Set<String> inter = new HashSet<>(a);
            inter.retainAll(b);
            return !inter.isEmpty();
        }

        @Override
        public boolean equivalent(FactConstraint other) {
            if (!(other instanceof EnumSetConstraint e) || !fact.equals(e.fact)) {
                return false;
            }
            return negated == e.negated && values.equals(e.values);
        }

        @Override
        public boolean subsumes(FactConstraint other) {
            if (!(other instanceof EnumSetConstraint e) || !fact.equals(e.fact)) {
                return false;
            }
            Set<String> a = effective(values, negated);
            Set<String> b = effective(e.values, e.negated);
            if (a == null || b == null) {
                return false;
            }
            return a.containsAll(b) && !a.equals(b);
        }

        @Override
        public boolean contradicts(FactConstraint other) {
            return other instanceof EnumSetConstraint e && fact.equals(e.fact) && !overlaps(e);
        }

        @Override
        public String plainOverlap(FactConstraint other) {
            if (!(other instanceof EnumSetConstraint e)) {
                return fact;
            }
            Set<String> a = effective(values, negated);
            Set<String> b = effective(e.values, e.negated);
            if (a == null || b == null) {
                return fact + " overlap";
            }
            Set<String> inter = new TreeSet<>(a);
            inter.retainAll(b);
            return fact + " ∈ " + inter;
        }

        private static Set<String> effective(Set<String> values, boolean negated) {
            if (negated) {
                return null; // complement — treat as open for overlap conservatism
            }
            return values;
        }
    }

    public record BoolConstraint(String fact, boolean expected) implements FactConstraint {
        @Override
        public boolean overlaps(FactConstraint other) {
            return other instanceof BoolConstraint b && fact.equals(b.fact) && expected == b.expected;
        }

        @Override
        public boolean equivalent(FactConstraint other) {
            return overlaps(other);
        }

        @Override
        public boolean subsumes(FactConstraint other) {
            return false;
        }

        @Override
        public boolean contradicts(FactConstraint other) {
            return other instanceof BoolConstraint b && fact.equals(b.fact) && expected != b.expected;
        }

        @Override
        public String plainOverlap(FactConstraint other) {
            return fact + " = " + expected;
        }
    }

    public enum ModalityClass {
        REQUIRES,
        NOT_REQUIRED,
        REQUIRES_APPROVAL,
        THRESHOLD,
        UNKNOWN;

        public static ModalityClass from(Object modality) {
            if (modality == null) {
                return UNKNOWN;
            }
            String m = String.valueOf(modality).toLowerCase(Locale.ROOT).replace('-', '_');
            if (m.contains("must_not") || m.contains("mustnot") || m.contains("never")
                    || m.equals("not_required") || m.contains("shall_not")) {
                return NOT_REQUIRED;
            }
            if (m.contains("approval") || m.contains("requires_approval")) {
                return REQUIRES_APPROVAL;
            }
            if (m.contains("threshold")) {
                return THRESHOLD;
            }
            if (m.contains("must") || m.contains("shall") || m.contains("require") || m.equals("requires")) {
                return REQUIRES;
            }
            return UNKNOWN;
        }

        public boolean opposes(ModalityClass other) {
            return (this == REQUIRES && other == NOT_REQUIRED)
                    || (this == NOT_REQUIRED && other == REQUIRES);
        }
    }

    @SuppressWarnings("unchecked")
    public static NormalizedRule normalize(Map<String, Object> rule) {
        String ruleId = String.valueOf(rule.getOrDefault("ruleId", rule.get("id")));
        String uuid = rule.get("id") == null ? null : String.valueOf(rule.get("id"));
        Map<String, Object> when = asMap(rule.get("when"));
        Map<String, Object> then = asMap(rule.get("then"));
        int minLevel = then.get("minLevel") instanceof Number n ? n.intValue() : 0;
        ModalityClass modality = ModalityClass.from(rule.get("modality"));

        Map<String, FactConstraint> facts = new LinkedHashMap<>();
        Set<String> actionTypes = new LinkedHashSet<>();
        List<Map<String, Object>> leaves = ConditionEnglish.collectLeaves(when);
        for (Map<String, Object> leaf : leaves) {
            String fact = String.valueOf(leaf.get("fact"));
            String op = String.valueOf(leaf.get("op")).toUpperCase(Locale.ROOT);
            Object value = leaf.get("value");
            if ("ask.type".equals(fact)) {
                actionTypes.addAll(enumValues(op, value));
            }
            FactConstraint c = toConstraint(fact, op, value);
            if (c == null) {
                continue;
            }
            FactConstraint prior = facts.get(fact);
            if (prior == null) {
                facts.put(fact, c);
            } else {
                facts.put(fact, tighten(prior, c));
            }
        }
        Set<String> keys = new LinkedHashSet<>(facts.keySet());
        String sha = contentFingerprint(facts, minLevel, modality, actionTypes);
        return new NormalizedRule(ruleId, uuid, actionTypes, facts, minLevel, modality, keys, sha);
    }

    private static FactConstraint toConstraint(String fact, String op, Object value) {
        String type = FactCatalogue.find(fact)
                .map(d -> d.type().toLowerCase(Locale.ROOT))
                .orElse(guessType(value));
        return switch (type) {
            case "number", "integer" -> numericFrom(fact, op, value);
            case "boolean" -> boolFrom(fact, op, value);
            default -> enumFrom(fact, op, value);
        };
    }

    private static NumericInterval numericFrom(String fact, String op, Object value) {
        double v = toDouble(value);
        return switch (op) {
            case "GT" -> new NumericInterval(fact, v, Double.POSITIVE_INFINITY, false, false);
            case "GTE" -> new NumericInterval(fact, v, Double.POSITIVE_INFINITY, true, false);
            case "LT" -> new NumericInterval(fact, Double.NEGATIVE_INFINITY, v, false, false);
            case "LTE" -> new NumericInterval(fact, Double.NEGATIVE_INFINITY, v, false, true);
            case "EQ" -> new NumericInterval(fact, v, v, true, true);
            case "NE" -> NumericInterval.unbounded(fact); // NE alone does not constrain well
            default -> NumericInterval.unbounded(fact);
        };
    }

    private static BoolConstraint boolFrom(String fact, String op, Object value) {
        boolean expected = Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
        if ("NE".equals(op)) {
            expected = !expected;
        }
        return new BoolConstraint(fact, expected);
    }

    private static EnumSetConstraint enumFrom(String fact, String op, Object value) {
        Set<String> vals = enumValues(op, value);
        boolean negated = "NE".equals(op) || "NOT_IN".equals(op);
        return new EnumSetConstraint(fact, vals, negated);
    }

    private static Set<String> enumValues(String op, Object value) {
        Set<String> out = new LinkedHashSet<>();
        if (value instanceof Collection<?> c) {
            for (Object o : c) {
                out.add(String.valueOf(o).toUpperCase(Locale.ROOT));
            }
        } else if (value != null) {
            out.add(String.valueOf(value).toUpperCase(Locale.ROOT));
        }
        return out;
    }

    private static FactConstraint tighten(FactConstraint a, FactConstraint b) {
        if (a instanceof NumericInterval na && b instanceof NumericInterval nb) {
            double lo = Math.max(na.lo(), nb.lo());
            double hi = Math.min(na.hi(), nb.hi());
            boolean loInc = lo == na.lo() ? na.loInc() : nb.loInc();
            if (lo == na.lo() && lo == nb.lo()) {
                loInc = na.loInc() && nb.loInc();
            }
            boolean hiInc = hi == na.hi() ? na.hiInc() : nb.hiInc();
            if (hi == na.hi() && hi == nb.hi()) {
                hiInc = na.hiInc() && nb.hiInc();
            }
            return new NumericInterval(na.fact(), lo, hi, loInc, hiInc);
        }
        if (a instanceof EnumSetConstraint ea && b instanceof EnumSetConstraint eb && !ea.negated() && !eb.negated()) {
            Set<String> inter = new LinkedHashSet<>(ea.values());
            inter.retainAll(eb.values());
            return new EnumSetConstraint(ea.fact(), inter, false);
        }
        if (a instanceof BoolConstraint ba && b instanceof BoolConstraint bb) {
            if (ba.expected() != bb.expected()) {
                // contradictory — keep a; detector will catch via contradict
                return ba;
            }
        }
        return a;
    }

    private static String guessType(Object value) {
        if (value instanceof Number) {
            return "number";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        return "string";
    }

    private static double toDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value).replace(",", ""));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static String contentFingerprint(
            Map<String, FactConstraint> facts, int minLevel, ModalityClass modality, Set<String> actions
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("L").append(minLevel).append("|M").append(modality).append("|A");
        actions.stream().sorted().forEach(a -> sb.append(a).append(','));
        facts.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e ->
                sb.append('|').append(e.getKey()).append('=').append(e.getValue()));
        return Integer.toHexString(sb.toString().hashCode());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        if (o instanceof Map<?, ?> m) {
            return new LinkedHashMap<>((Map<String, Object>) m);
        }
        return new LinkedHashMap<>();
    }

    /** True if every fact constraint in {@code a} overlaps the corresponding constraint in {@code b}. */
    public static boolean conditionsOverlap(NormalizedRule a, NormalizedRule b) {
        if (!a.sharesScope(b)) {
            return false;
        }
        Set<String> shared = new HashSet<>(a.factKeys());
        shared.retainAll(b.factKeys());
        if (shared.isEmpty()) {
            return !a.actionTypes().isEmpty() && !Collections.disjoint(a.actionTypes(), b.actionTypes());
        }
        for (String fact : shared) {
            FactConstraint ca = a.facts().get(fact);
            FactConstraint cb = b.facts().get(fact);
            if (ca != null && cb != null && !ca.overlaps(cb)) {
                return false;
            }
        }
        return true;
    }

    public static boolean conditionsEquivalent(NormalizedRule a, NormalizedRule b) {
        if (!Objects.equals(a.actionTypes(), b.actionTypes())) {
            return false;
        }
        if (!a.factKeys().equals(b.factKeys())) {
            return false;
        }
        for (String fact : a.factKeys()) {
            FactConstraint ca = a.facts().get(fact);
            FactConstraint cb = b.facts().get(fact);
            if (ca == null || cb == null || !ca.equivalent(cb)) {
                return false;
            }
        }
        return true;
    }

    /** Returns which rule subsumes the other: "A", "B", or null. */
    public static String subsumption(NormalizedRule a, NormalizedRule b) {
        if (!conditionsOverlap(a, b)) {
            return null;
        }
        boolean aSubB = true;
        boolean bSubA = true;
        Set<String> all = new HashSet<>(a.factKeys());
        all.addAll(b.factKeys());
        for (String fact : all) {
            FactConstraint ca = a.facts().get(fact);
            FactConstraint cb = b.facts().get(fact);
            if (ca == null && cb != null) {
                aSubB = false; // a is less constrained on this fact
            } else if (cb == null && ca != null) {
                bSubA = false;
            } else if (ca != null) {
                if (!ca.equivalent(cb) && !ca.subsumes(cb)) {
                    aSubB = false;
                }
                if (!cb.equivalent(ca) && !cb.subsumes(ca)) {
                    bSubA = false;
                }
            }
        }
        // Also compare action type sets: larger set is less specific
        if (!a.actionTypes().equals(b.actionTypes())) {
            if (a.actionTypes().containsAll(b.actionTypes()) && a.actionTypes().size() > b.actionTypes().size()) {
                aSubB = false;
            }
            if (b.actionTypes().containsAll(a.actionTypes()) && b.actionTypes().size() > a.actionTypes().size()) {
                bSubA = false;
            }
        }
        if (aSubB && !bSubA) {
            return "A";
        }
        if (bSubA && !aSubB) {
            return "B";
        }
        return null;
    }

    public static List<String> contradictingFacts(NormalizedRule a, NormalizedRule b) {
        List<String> out = new ArrayList<>();
        Set<String> shared = new HashSet<>(a.factKeys());
        shared.retainAll(b.factKeys());
        for (String fact : shared) {
            FactConstraint ca = a.facts().get(fact);
            FactConstraint cb = b.facts().get(fact);
            if (ca != null && cb != null && ca.contradicts(cb)) {
                out.add(fact);
            }
        }
        return out;
    }

    public static String overlapSummary(NormalizedRule a, NormalizedRule b) {
        List<String> parts = new ArrayList<>();
        if (!a.actionTypes().isEmpty() && !b.actionTypes().isEmpty()) {
            Set<String> inter = new TreeSet<>(a.actionTypes());
            inter.retainAll(b.actionTypes());
            if (!inter.isEmpty()) {
                parts.add("action " + String.join("/", inter));
            }
        }
        Set<String> shared = new HashSet<>(a.factKeys());
        shared.retainAll(b.factKeys());
        shared.remove("ask.type");
        for (String fact : shared) {
            FactConstraint ca = a.facts().get(fact);
            FactConstraint cb = b.facts().get(fact);
            if (ca != null && cb != null && ca.overlaps(cb)) {
                parts.add(ca.plainOverlap(cb));
            }
        }
        if (parts.isEmpty()) {
            return "overlapping conditions";
        }
        return "both fire when " + String.join(" and ", parts);
    }

    public static Map<String, NumericInterval> numericFacts(NormalizedRule n) {
        Map<String, NumericInterval> out = new HashMap<>();
        for (Map.Entry<String, FactConstraint> e : n.facts().entrySet()) {
            if (e.getValue() instanceof NumericInterval ni) {
                out.put(e.getKey(), ni);
            }
        }
        return out;
    }
}
