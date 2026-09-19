package com.sentinelvoice.policy.dsl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Fixed runtime fact registry for F6/F7. Compiler may only reference these paths.
 */
public final class FactCatalogue {

    public record FactDef(String path, String type, String description, List<String> enumValues) {
        public boolean hasEnum() {
            return enumValues != null && !enumValues.isEmpty();
        }
    }

    private static final List<FactDef> FACTS;
    private static final Map<String, FactDef> BY_PATH;
    private static final Set<String> PATHS;

    /** Facts that alone do not discriminate an actionable policy condition. */
    private static final Set<String> NON_DISCRIMINATING = Set.of(
            "session.durationSec",
            "time.hourLocal",
            "time.isBusinessHours",
            "voice.syntheticScore",
            "voice.speakerMismatch",
            "relationship.daysSinceLastContact",
            "relationship.isFirstContact"
    );

    static {
        try {
            ObjectMapper mapper = new ObjectMapper();
            InputStream in = FactCatalogue.class.getResourceAsStream("/policy/fact-catalogue.json");
            if (in == null) {
                in = FactCatalogue.class.getClassLoader().getResourceAsStream("policy/fact-catalogue.json");
            }
            if (in == null) {
                throw new IllegalStateException("fact-catalogue.json missing on classpath");
            }
            Map<String, Object> root = mapper.readValue(in, new TypeReference<>() {
            });
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> raw = (List<Map<String, Object>>) root.get("facts");
            FACTS = raw.stream()
                    .map(m -> {
                        List<String> enums = List.of();
                        if (m.get("enum") instanceof List<?> el) {
                            enums = el.stream().map(String::valueOf).toList();
                        }
                        return new FactDef(
                                String.valueOf(m.get("path")),
                                String.valueOf(m.get("type")),
                                String.valueOf(m.get("description")),
                                enums
                        );
                    })
                    .toList();
            BY_PATH = FACTS.stream().collect(Collectors.toMap(FactDef::path, f -> f, (a, b) -> a, LinkedHashMap::new));
            PATHS = FACTS.stream().map(FactDef::path).collect(Collectors.toUnmodifiableSet());
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private FactCatalogue() {
    }

    public static List<FactDef> facts() {
        return FACTS;
    }

    public static Set<String> paths() {
        return PATHS;
    }

    public static boolean isKnown(String path) {
        return path != null && PATHS.contains(path);
    }

    public static Optional<FactDef> find(String path) {
        return Optional.ofNullable(BY_PATH.get(path));
    }

    public static boolean isNonDiscriminating(String path) {
        return path != null && NON_DISCRIMINATING.contains(path);
    }

    /**
     * Validate a leaf condition value against the catalogue type / enum.
     * @return error message or empty if ok
     */
    public static Optional<String> validateValue(String factPath, String op, Object value) {
        if (!isKnown(factPath)) {
            return Optional.of("Unknown fact path: " + factPath);
        }
        if ("EXISTS".equals(op)) {
            return Optional.empty();
        }
        if (value == null || (value instanceof String s && s.isBlank())) {
            return Optional.of("Empty condition value for " + factPath);
        }
        FactDef def = BY_PATH.get(factPath);
        String type = def.type().toLowerCase(Locale.ROOT);
        if ("IN".equals(op) || "NOT_IN".equals(op)) {
            if (!(value instanceof List<?> list) || list.isEmpty()) {
                return Optional.of("IN/NOT_IN requires a non-empty array for " + factPath);
            }
            for (Object item : list) {
                Optional<String> err = validateScalar(def, type, item);
                if (err.isPresent()) {
                    return err;
                }
            }
            return Optional.empty();
        }
        return validateScalar(def, type, value);
    }

    private static Optional<String> validateScalar(FactDef def, String type, Object value) {
        return switch (type) {
            case "boolean" -> {
                if (value instanceof Boolean) {
                    yield Optional.empty();
                }
                if (value instanceof String s
                        && ("true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s))) {
                    yield Optional.empty();
                }
                yield Optional.of(def.path() + " requires a boolean value");
            }
            case "number" -> {
                if (value instanceof Number) {
                    yield Optional.empty();
                }
                if (value instanceof String s) {
                    try {
                        Double.parseDouble(s.replace(",", ""));
                        yield Optional.empty();
                    } catch (NumberFormatException e) {
                        yield Optional.of(def.path() + " requires a number");
                    }
                }
                yield Optional.of(def.path() + " requires a number");
            }
            case "string" -> {
                String s = String.valueOf(value).strip();
                if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
                    yield Optional.of("Empty string value for " + def.path());
                }
                if (def.hasEnum() && !def.enumValues().contains(s)) {
                    yield Optional.of(
                            def.path() + " value \"" + s + "\" is not in catalogue enum "
                                    + def.enumValues()
                    );
                }
                yield Optional.empty();
            }
            default -> Optional.empty();
        };
    }

    /** Facts likely relevant to a clause (for a shorter LLM prompt). */
    public static List<FactDef> subsetForClause(String chunkText) {
        String lower = chunkText == null ? "" : chunkText.toLowerCase(Locale.ROOT);
        List<FactDef> out = new ArrayList<>();
        for (FactDef f : FACTS) {
            if (relevant(f.path(), lower)) {
                out.add(f);
            }
        }
        if (out.isEmpty()) {
            // Always include core ask facts so the model has something constrained
            for (String p : List.of("ask.type", "ask.amountInr", "ask.sharesCredential", "ask.beneficiaryKnown")) {
                find(p).ifPresent(out::add);
            }
        }
        return out;
    }

    private static boolean relevant(String path, String lower) {
        if (path.startsWith("ask.")) {
            if (path.contains("amount") || path.contains("beneficiary") || path.equals("ask.type")) {
                return lower.matches("(?s).*(wire|transfer|payment|beneficiary|inr|rupee|amount|lakh|crore).*")
                        || lower.matches("(?s).*(must|shall|prohibited|not permitted).*");
            }
            if (path.contains("Credential") || path.contains("secrecy") || path.contains("urgency")
                    || path.contains("authority") || path.contains("channel")) {
                return lower.matches("(?s).*(otp|pin|password|credential|secrecy|confidential|urgent|authority|sms|email).*")
                        || path.equals("ask.sharesCredential");
            }
            return true;
        }
        if (path.startsWith("caller.")) {
            return lower.matches("(?s).*(caller|staff|employee|role|authority|directory|limit).*");
        }
        if (path.startsWith("time.")) {
            return lower.matches("(?s).*(hour|business hours|after.?hours|time).*");
        }
        if (path.startsWith("voice.") || path.startsWith("session.") || path.startsWith("relationship.")) {
            return lower.matches("(?s).*(voice|speaker|session|duration|contact|first.?contact).*");
        }
        if (path.startsWith("callee.")) {
            return lower.matches("(?s).*(agent|callee|staff).*");
        }
        return true;
    }

    public static Map<String, Object> toApiBody() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", "2");
        out.put("facts", FACTS.stream().map(FactCatalogue::factToMap).toList());
        return out;
    }

    public static Map<String, Object> subsetToApiBody(List<FactDef> subset) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", "2");
        out.put("facts", subset.stream().map(FactCatalogue::factToMap).toList());
        return out;
    }

    private static Map<String, Object> factToMap(FactDef f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", f.path());
        m.put("type", f.type());
        m.put("description", f.description());
        if (f.hasEnum()) {
            m.put("enum", f.enumValues());
        }
        return m;
    }

    /**
     * Build a JSON Schema for {@code policy_compile} LLM output with {@code fact} as an enum
     * of catalogue paths and typed leaf values.
     */
    public static Map<String, Object> buildCompileResultSchema() {
        List<String> factEnum = FACTS.stream().map(FactDef::path).toList();
        Map<String, Object> leafValue = new LinkedHashMap<>();
        // Broad value; Java validator enforces per-fact type/enum after parse
        leafValue.put("description", "Must match the catalogue type/enum for the chosen fact");

        Map<String, Object> leaf = new LinkedHashMap<>();
        leaf.put("type", "object");
        leaf.put("additionalProperties", false);
        leaf.put("required", List.of("fact", "op"));
        Map<String, Object> leafProps = new LinkedHashMap<>();
        leafProps.put("fact", Map.of("type", "string", "enum", factEnum));
        leafProps.put("op", Map.of(
                "type", "string",
                "enum", List.of("EQ", "NE", "GT", "GTE", "LT", "LTE", "IN", "NOT_IN", "CONTAINS", "EXISTS")
        ));
        leafProps.put("value", leafValue);
        leaf.put("properties", leafProps);

        Map<String, Object> condition = new LinkedHashMap<>();
        // Avoid deep $ref recursion for small models — allow object with all/any/not/fact
        condition.put("type", "object");

        Map<String, Object> thenObj = Map.of(
                "type", "object",
                "required", List.of("minLevel", "scoreBoost", "reasonCode"),
                "properties", Map.of(
                        "minLevel", Map.of("type", "integer", "minimum", 1, "maximum", 4),
                        "scoreBoost", Map.of("type", "number"),
                        "reasonCode", Map.of("type", "string"),
                        "advice", Map.of("type", "string")
                )
        );

        Map<String, Object> source = Map.of(
                "type", "object",
                "required", List.of("quote", "clauseRef"),
                "properties", Map.of(
                        "clauseRef", Map.of("type", "string", "minLength", 1),
                        "quote", Map.of("type", "string", "minLength", 1, "maxLength", 200)
                )
        );

        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("type", "object");
        rule.put("required", List.of("ruleId", "title", "source", "appliesTo", "when", "then", "severity"));
        Map<String, Object> ruleProps = new LinkedHashMap<>();
        ruleProps.put("ruleId", Map.of("type", "string"));
        ruleProps.put("title", Map.of("type", "string"));
        ruleProps.put("description", Map.of("type", "string"));
        ruleProps.put("source", source);
        ruleProps.put("appliesTo", Map.of("type", "object"));
        ruleProps.put("when", condition);
        ruleProps.put("then", thenObj);
        ruleProps.put("severity", Map.of("type", "string", "enum", List.of("LOW", "MEDIUM", "HIGH", "CRITICAL")));
        ruleProps.put("keywords", Map.of("type", "array"));
        ruleProps.put("policyFact", Map.of("type", "string"));
        rule.put("properties", ruleProps);

        // Document leaf shape for models that inspect $defs
        Map<String, Object> defs = new LinkedHashMap<>();
        defs.put("ConditionLeaf", leaf);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("additionalProperties", false);
        root.put("required", List.of("schemaVersion", "rules"));
        root.put("$defs", defs);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("schemaVersion", Map.of("const", "2"));
        props.put("rules", Map.of("type", "array", "items", rule));
        root.put("properties", props);
        return root;
    }
}
