package com.sentinelvoice.policy.engine;

import com.sentinelvoice.policy.dsl.Condition;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Closed-operator condition tree evaluator with Kleene three-valued logic (F7).
 * No script engines, reflection, or eval.
 */
public final class ConditionEvaluator {

    private ConditionEvaluator() {
    }

    public record EvalResult(TriBool value, Set<String> unknownFacts) {
        public static EvalResult of(TriBool v, Set<String> unknowns) {
            return new EvalResult(v, unknowns == null ? Set.of() : Set.copyOf(unknowns));
        }
    }

    public static EvalResult evaluate(Condition condition, FactSet facts) {
        if (condition == null) {
            return EvalResult.of(TriBool.FALSE, Set.of());
        }
        return switch (condition) {
            case Condition.All all -> evalAll(all.all(), facts);
            case Condition.Any any -> evalAny(any.any(), facts);
            case Condition.Not not -> {
                EvalResult inner = evaluate(not.not(), facts);
                yield EvalResult.of(inner.value().not(), inner.unknownFacts());
            }
            case Condition.FactPred pred -> evalPred(pred, facts);
        };
    }

    private static EvalResult evalAll(List<Condition> kids, FactSet facts) {
        TriBool acc = TriBool.TRUE;
        Set<String> unknowns = new LinkedHashSet<>();
        if (kids == null || kids.isEmpty()) {
            return EvalResult.of(TriBool.TRUE, Set.of());
        }
        for (Condition c : kids) {
            EvalResult r = evaluate(c, facts);
            unknowns.addAll(r.unknownFacts());
            acc = acc.and(r.value());
            if (acc == TriBool.FALSE) {
                // Still collect unknowns from remaining? Spec: report which facts caused UNDETERMINED.
                // Short-circuit on FALSE is fine; unknowns from this branch already collected.
                break;
            }
        }
        return EvalResult.of(acc, unknowns);
    }

    private static EvalResult evalAny(List<Condition> kids, FactSet facts) {
        TriBool acc = TriBool.FALSE;
        Set<String> unknowns = new LinkedHashSet<>();
        if (kids == null || kids.isEmpty()) {
            return EvalResult.of(TriBool.FALSE, Set.of());
        }
        for (Condition c : kids) {
            EvalResult r = evaluate(c, facts);
            unknowns.addAll(r.unknownFacts());
            acc = acc.or(r.value());
            if (acc == TriBool.TRUE) {
                break;
            }
        }
        return EvalResult.of(acc, unknowns);
    }

    private static EvalResult evalPred(Condition.FactPred pred, FactSet facts) {
        String path = pred.fact();
        String op = pred.op() == null ? "" : pred.op().toUpperCase(Locale.ROOT);
        if (!facts.isPresent(path)) {
            if ("EXISTS".equals(op)) {
                return EvalResult.of(TriBool.FALSE, Set.of());
            }
            return EvalResult.of(TriBool.UNKNOWN, Set.of(path));
        }
        Object actual = facts.raw(path);
        Object expected = pred.value();
        boolean result = switch (op) {
            case "EQ", "NE" -> {
                boolean eq = valuesEqual(actual, expected);
                yield "EQ".equals(op) == eq;
            }
            case "GT", "GTE", "LT", "LTE" -> compareNumbers(actual, expected, op);
            case "IN" -> collectionContains(expected, actual);
            case "NOT_IN" -> !collectionContains(expected, actual);
            case "CONTAINS" -> stringContains(actual, expected);
            case "EXISTS" -> true;
            default -> false;
        };
        return EvalResult.of(result ? TriBool.TRUE : TriBool.FALSE, Set.of());
    }

    private static boolean valuesEqual(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof Number na && b instanceof Number nb) {
            return Double.compare(na.doubleValue(), nb.doubleValue()) == 0;
        }
        if (a instanceof Boolean ba) {
            return ba.equals(asBoolean(b));
        }
        if (b instanceof Boolean bb) {
            return bb.equals(asBoolean(a));
        }
        return String.valueOf(a).equalsIgnoreCase(String.valueOf(b));
    }

    private static Boolean asBoolean(Object o) {
        if (o instanceof Boolean b) {
            return b;
        }
        if (o instanceof String s) {
            if ("true".equalsIgnoreCase(s)) {
                return true;
            }
            if ("false".equalsIgnoreCase(s)) {
                return false;
            }
        }
        return null;
    }

    private static boolean compareNumbers(Object actual, Object expected, String op) {
        Double a = toDouble(actual);
        Double e = toDouble(expected);
        if (a == null || e == null) {
            return false;
        }
        int cmp = Double.compare(a, e);
        return switch (op) {
            case "GT" -> cmp > 0;
            case "GTE" -> cmp >= 0;
            case "LT" -> cmp < 0;
            case "LTE" -> cmp <= 0;
            default -> false;
        };
    }

    private static Double toDouble(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o instanceof String s) {
            try {
                return Double.parseDouble(s.replace(",", "").trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static boolean collectionContains(Object expected, Object actual) {
        Collection<?> col;
        if (expected instanceof Collection<?> c) {
            col = c;
        } else {
            return false;
        }
        for (Object item : col) {
            if (valuesEqual(actual, item)) {
                return true;
            }
        }
        return false;
    }

    private static boolean stringContains(Object actual, Object expected) {
        if (actual == null || expected == null) {
            return false;
        }
        return String.valueOf(actual).toLowerCase(Locale.ROOT)
                .contains(String.valueOf(expected).toLowerCase(Locale.ROOT));
    }

    /** Collect fact paths referenced by a condition tree. */
    public static Set<String> referencedFacts(Condition condition) {
        Set<String> out = new LinkedHashSet<>();
        collect(condition, out);
        return out;
    }

    private static void collect(Condition condition, Set<String> out) {
        if (condition == null) {
            return;
        }
        switch (condition) {
            case Condition.All all -> {
                if (all.all() != null) {
                    all.all().forEach(c -> collect(c, out));
                }
            }
            case Condition.Any any -> {
                if (any.any() != null) {
                    any.any().forEach(c -> collect(c, out));
                }
            }
            case Condition.Not not -> collect(not.not(), out);
            case Condition.FactPred pred -> {
                if (pred.fact() != null) {
                    out.add(pred.fact());
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    public static Condition fromWhen(Object when) {
        if (when instanceof Condition c) {
            return c;
        }
        if (when instanceof Map<?, ?> m) {
            return Condition.fromMap((Map<String, Object>) m);
        }
        throw new IllegalArgumentException("when must be a condition map");
    }
}
