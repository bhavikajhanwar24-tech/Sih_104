package com.sentinelvoice.policy.dsl;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Plain-English rendering of closed condition trees for the review UI.
 */
public final class ConditionEnglish {

    private ConditionEnglish() {
    }

    public static String render(Map<String, Object> when, Map<String, Object> then, Map<String, Object> appliesTo) {
        String cond = renderCond(when);
        int minLevel = then != null && then.get("minLevel") instanceof Number n ? n.intValue() : 0;
        // Prefer conditions that already name the action (ask.type); do not append "action is *".
        return "IF " + cond + " THEN raise the level to at least " + minLevel;
    }

    /** Operator-facing "fires when …" line (positive condition only). */
    public static String firesWhen(Map<String, Object> when) {
        return "fires when " + renderCond(when);
    }

    /** Operator-facing "does NOT fire when …" — negation of the root condition. */
    public static String doesNotFireWhen(Map<String, Object> when) {
        if (when == null || when.isEmpty()) {
            return "does NOT fire when (never — condition is always true)";
        }
        Map<String, Object> negated = new java.util.LinkedHashMap<>();
        negated.put("not", when);
        return "does NOT fire when " + renderCond(negated);
    }

    @SuppressWarnings("unchecked")
    private static String renderCond(Map<String, Object> m) {
        if (m == null || m.isEmpty()) {
            return "(always)";
        }
        if (m.containsKey("all")) {
            List<Map<String, Object>> kids = (List<Map<String, Object>>) m.get("all");
            return join(kids, " AND ");
        }
        if (m.containsKey("any")) {
            List<Map<String, Object>> kids = (List<Map<String, Object>>) m.get("any");
            return "(" + join(kids, " OR ") + ")";
        }
        if (m.containsKey("not")) {
            return "NOT (" + renderCond((Map<String, Object>) m.get("not")) + ")";
        }
        String fact = String.valueOf(m.get("fact"));
        String op = String.valueOf(m.get("op"));
        Object value = m.get("value");
        return renderLeaf(fact, op, value);
    }

    private static String renderLeaf(String fact, String op, Object value) {
        return switch (fact) {
            case "ask.type" -> switch (op) {
                case "EQ" -> "the request is a " + humanAction(value);
                case "NE" -> "the request is not a " + humanAction(value);
                case "IN" -> "the request is one of " + fmt(value);
                default -> "the request type " + opHuman(op) + " " + fmt(value);
            };
            case "ask.amountInr" -> switch (op) {
                case "GT" -> "above " + inr(value);
                case "GTE" -> "at least " + inr(value);
                case "LT" -> "below " + inr(value);
                case "LTE" -> "at most " + inr(value);
                case "EQ" -> "equal to " + inr(value);
                default -> "amount " + opHuman(op) + " " + inr(value);
            };
            case "ask.beneficiaryKnown" -> boolPhrase("the beneficiary is known", "the beneficiary is unknown", op, value);
            case "ask.beneficiaryVerified" -> boolPhrase(
                    "the beneficiary is verified", "the beneficiary is not verified", op, value);
            case "ask.sharesCredential" -> boolPhrase(
                    "credentials (OTP/PIN) are being shared or solicited",
                    "credentials are not involved",
                    op,
                    value
            );
            case "ask.secrecyRequested" -> boolPhrase("secrecy was requested", "secrecy was not requested", op, value);
            case "ask.authorityClaimed" -> boolPhrase(
                    "the caller claimed superior authority",
                    "no superior authority claim",
                    op,
                    value
            );
            case "caller.isHighAuthority" -> boolPhrase(
                    "the caller is high-authority", "the caller is not high-authority", op, value);
            case "time.isBusinessHours" -> boolPhrase(
                    "it is business hours", "it is outside business hours", op, value);
            case "voice.speakerMismatch" -> boolPhrase(
                    "the speaker does not match the enrolled voice",
                    "the speaker matches",
                    op,
                    value
            );
            default -> factLabel(fact) + " " + opHuman(op) + " " + fmt(value);
        };
    }

    private static String boolPhrase(String ifTrue, String ifFalse, String op, Object value) {
        boolean expected = Boolean.TRUE.equals(value)
                || "true".equalsIgnoreCase(String.valueOf(value));
        if ("NE".equals(op)) {
            expected = !expected;
        }
        if ("EXISTS".equals(op)) {
            return ifTrue.replace(" is ", " is present as ");
        }
        return expected ? ifTrue : ifFalse;
    }

    private static String humanAction(Object value) {
        String s = String.valueOf(value).toUpperCase(Locale.ROOT);
        return switch (s) {
            case "WIRE_TRANSFER" -> "wire transfer";
            case "PASSWORD_RESET" -> "password reset";
            case "ACCOUNT_LOOKUP" -> "account lookup";
            case "BENEFICIARY_CHANGE" -> "beneficiary change";
            case "OTP_SHARE" -> "OTP share";
            case "PIN_SHARE" -> "PIN share";
            case "CALLBACK" -> "callback";
            case "INFORMATION" -> "information request";
            default -> s.toLowerCase(Locale.ROOT).replace('_', ' ');
        };
    }

    private static String factLabel(String fact) {
        return switch (fact) {
            case "caller.authorityLimitInr" -> "the caller authority limit";
            case "session.durationSec" -> "session duration (seconds)";
            case "ask.urgencyLevel" -> "urgency";
            case "time.hourLocal" -> "local hour";
            default -> fact;
        };
    }

    private static String opHuman(String op) {
        return switch (op) {
            case "EQ" -> "is";
            case "NE" -> "is not";
            case "GT" -> "is above";
            case "GTE" -> "is at least";
            case "LT" -> "is below";
            case "LTE" -> "is at most";
            case "IN" -> "is in";
            case "NOT_IN" -> "is not in";
            case "CONTAINS" -> "contains";
            case "EXISTS" -> "is present";
            default -> op;
        };
    }

    private static String join(List<Map<String, Object>> kids, String sep) {
        List<String> parts = new ArrayList<>();
        // Special-case: ask.type + ask.amountInr → "a wire transfer above INR …"
        String typePhrase = null;
        String amountPhrase = null;
        List<String> rest = new ArrayList<>();
        for (Map<String, Object> k : kids) {
            if (k == null) {
                continue;
            }
            if ("ask.type".equals(String.valueOf(k.get("fact"))) && "EQ".equals(String.valueOf(k.get("op")))) {
                typePhrase = "the request is a " + humanAction(k.get("value"));
            } else if ("ask.amountInr".equals(String.valueOf(k.get("fact")))) {
                amountPhrase = renderLeaf("ask.amountInr", String.valueOf(k.get("op")), k.get("value"));
            } else {
                rest.add(renderCond(k));
            }
        }
        if (typePhrase != null && amountPhrase != null) {
            parts.add(typePhrase + " " + amountPhrase);
            parts.addAll(rest);
            return String.join(sep, parts);
        }
        for (Map<String, Object> k : kids) {
            parts.add(renderCond(k));
        }
        return String.join(sep, parts);
    }

    private static String inr(Object value) {
        if (value == null) {
            return "INR ?";
        }
        long v;
        if (value instanceof Number n) {
            v = Math.round(n.doubleValue());
        } else {
            try {
                v = Math.round(Double.parseDouble(String.valueOf(value).replace(",", "")));
            } catch (NumberFormatException e) {
                return String.valueOf(value);
            }
        }
        String indian = formatIndian(v);
        String gloss = glossLakhCrore(v);
        if (gloss != null) {
            return "INR " + indian + " (" + gloss + ")";
        }
        return "INR " + indian;
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

    private static String glossLakhCrore(long v) {
        if (v == 100_000L) {
            return "1 lakh";
        }
        if (v == 1_000_000L) {
            return "10 lakh";
        }
        if (v == 10_000_000L) {
            return "1 crore";
        }
        if (v > 0 && v % 100_000L == 0 && v < 10_000_000L) {
            return (v / 100_000L) + " lakh";
        }
        if (v > 0 && v % 10_000_000L == 0) {
            return (v / 10_000_000L) + " crore";
        }
        return null;
    }

    private static String fmt(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number n) {
            long v = Math.round(n.doubleValue());
            if (v >= 1000) {
                return inr(v);
            }
            return String.valueOf(value);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(ConditionEnglish::fmt).toList().toString();
        }
        if (value instanceof Boolean b) {
            return b ? "true" : "false";
        }
        return String.valueOf(value);
    }

    public static Set<String> collectFacts(Map<String, Object> when) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        collect(when, out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void collect(Map<String, Object> m, Set<String> out) {
        if (m == null) {
            return;
        }
        if (m.containsKey("all")) {
            for (Map<String, Object> k : (List<Map<String, Object>>) m.get("all")) {
                collect(k, out);
            }
        } else if (m.containsKey("any")) {
            for (Map<String, Object> k : (List<Map<String, Object>>) m.get("any")) {
                collect(k, out);
            }
        } else if (m.containsKey("not")) {
            collect((Map<String, Object>) m.get("not"), out);
        } else if (m.containsKey("fact")) {
            out.add(String.valueOf(m.get("fact")));
        }
    }

    public static List<Object> collectNumericLiterals(Map<String, Object> when) {
        List<Object> out = new ArrayList<>();
        collectNums(when, out);
        return out;
    }

    /** Collect leaf conditions as fact/op/value maps (for edit validation). */
    public static List<Map<String, Object>> collectLeaves(Map<String, Object> when) {
        List<Map<String, Object>> out = new ArrayList<>();
        collectLeaves0(when, out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void collectLeaves0(Map<String, Object> m, List<Map<String, Object>> out) {
        if (m == null) {
            return;
        }
        if (m.containsKey("all")) {
            for (Map<String, Object> k : (List<Map<String, Object>>) m.get("all")) {
                collectLeaves0(k, out);
            }
        } else if (m.containsKey("any")) {
            for (Map<String, Object> k : (List<Map<String, Object>>) m.get("any")) {
                collectLeaves0(k, out);
            }
        } else if (m.containsKey("not")) {
            collectLeaves0((Map<String, Object>) m.get("not"), out);
        } else if (m.containsKey("fact")) {
            out.add(m);
        }
    }

    @SuppressWarnings("unchecked")
    private static void collectNums(Map<String, Object> m, List<Object> out) {
        if (m == null) {
            return;
        }
        if (m.containsKey("all")) {
            for (Map<String, Object> k : (List<Map<String, Object>>) m.get("all")) {
                collectNums(k, out);
            }
        } else if (m.containsKey("any")) {
            for (Map<String, Object> k : (List<Map<String, Object>>) m.get("any")) {
                collectNums(k, out);
            }
        } else if (m.containsKey("not")) {
            collectNums((Map<String, Object>) m.get("not"), out);
        } else if (m.containsKey("value")) {
            Object v = m.get("value");
            if (v instanceof Number) {
                out.add(v);
            } else if (v instanceof String s) {
                try {
                    out.add(Double.parseDouble(s.replace(",", "")));
                } catch (NumberFormatException ignored) {
                    // not numeric
                }
            }
        }
    }
}
