package com.sentinelvoice.policy.dsl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelvoice.directory.DirectoryMatch;

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
            // Directory-backed facts always take enum values from DirectoryMatch — never free text
            // (rejects e.g. caller.status=INACTIVE).
            Map<String, List<String>> directoryEnums = Map.of(
                    "caller.status", DirectoryMatch.EMPLOYEE_STATUSES,
                    "caller.matchType", DirectoryMatch.matchTypeNames(),
                    "caller.numberProvenance", DirectoryMatch.numberProvenanceNames()
            );
            FACTS = raw.stream()
                    .map(m -> {
                        String path = String.valueOf(m.get("path"));
                        List<String> enums = List.of();
                        if (directoryEnums.containsKey(path)) {
                            enums = directoryEnums.get(path);
                        } else if (m.get("enum") instanceof List<?> el) {
                            enums = el.stream().map(String::valueOf).toList();
                        }
                        return new FactDef(
                                path,
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

    /**
     * Map a free-text value onto the fact's enum: exact (case-insensitive) → substring →
     * best token overlap → OTHER when the enum has it. Empty when the fact has no enum
     * or nothing reasonable matches.
     */
    public static Optional<String> coerceEnumValue(String factPath, Object value) {
        FactDef def = BY_PATH.get(factPath);
        if (def == null || !def.hasEnum() || value == null) {
            return Optional.empty();
        }
        String raw = String.valueOf(value).strip();
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        for (String e : def.enumValues()) {
            if (e.equalsIgnoreCase(raw)) {
                return Optional.of(e);
            }
        }
        String norm = raw.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
        for (String e : def.enumValues()) {
            if (!"OTHER".equals(e) && (norm.contains(e) || e.contains(norm))) {
                return Optional.of(e);
            }
        }
        Set<String> tokens = Set.of(norm.split("_"));
        String best = null;
        int bestHits = 0;
        for (String e : def.enumValues()) {
            if ("OTHER".equals(e)) {
                continue;
            }
            int hits = 0;
            for (String t : e.split("_")) {
                if (t.length() >= 3 && tokens.contains(t)) {
                    hits++;
                }
            }
            if (hits > bestHits) {
                bestHits = hits;
                best = e;
            }
        }
        if (best != null) {
            return Optional.of(best);
        }
        return def.enumValues().contains("OTHER") ? Optional.of("OTHER") : Optional.empty();
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
            for (String p : List.of("ask.type", "ask.sharesCredential", "ask.authorityClaimed", "ask.urgencyLevel")) {
                find(p).ifPresent(out::add);
            }
        }
        return out;
    }

    private static boolean relevant(String path, String lower) {
        if (path.startsWith("ask.")) {
            if (path.equals("ask.type")) {
                return true;
            }
            if (path.contains("amount")) {
                // Only offer amount facts when the clause actually states an amount
                return lower.matches("(?s).*(inr|rs\\.|rupee|₹|lakh|crore|\\d{1,3}(,\\d{2,3})+|\\d{4,}).*");
            }
            if (path.contains("beneficiary")) {
                return lower.matches("(?s).*(beneficiar|payee|vendor|bank account|account details|wire|transfer).*");
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
     * Flat JSON Schema for small models (gemma 4B). Avoids deep oneOf/anyOf/$ref and
     * {@code additionalProperties:false} which Ollama often fails with empty content.
     * Fact path enums are limited to {@code subset} (clause-relevant catalogue only).
     * Full rule shape is validated/normalised in Java after parse.
     */
    public static Map<String, Object> buildCompileResultSchema() {
        return buildCompileResultSchema(FACTS);
    }

    public static Map<String, Object> buildCompileResultSchema(List<FactDef> subset) {
        List<FactDef> facts = (subset == null || subset.isEmpty()) ? FACTS : subset;
        List<String> factEnum = facts.stream().map(FactDef::path).toList();

        Map<String, Object> leaf = new LinkedHashMap<>();
        leaf.put("type", "object");
        leaf.put("properties", Map.of(
                "fact", Map.of("type", "string", "enum", factEnum),
                "op", Map.of(
                        "type", "string",
                        "enum", List.of("EQ", "NE", "GT", "GTE", "LT", "LTE", "IN", "NOT_IN", "CONTAINS", "EXISTS")
                ),
                "value", Map.of("description", "Scalar matching the fact type/enum")
        ));
        leaf.put("required", List.of("fact", "op", "value"));

        Map<String, Object> thenObj = new LinkedHashMap<>();
        thenObj.put("type", "object");
        thenObj.put("properties", Map.of(
                "minLevel", Map.of("type", "integer", "minimum", 1, "maximum", 4)
        ));
        thenObj.put("required", List.of("minLevel"));

        // Fixed {all:[leaf...]} shape: without it small models invent unreadable condition
        // objects that are then dropped as placeholders.
        Map<String, Object> whenRequired = new LinkedHashMap<>();
        whenRequired.put("type", "object");
        whenRequired.put("properties", Map.of(
                "all", Map.of("type", "array", "minItems", 1, "maxItems", 4, "items", leaf)
        ));
        whenRequired.put("required", List.of("all"));

        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("type", "object");
        Map<String, Object> ruleProps = new LinkedHashMap<>();
        // Meaning only — source/ids are attached in Java from the chunk
        ruleProps.put("when", whenRequired);
        ruleProps.put("then", thenObj);
        ruleProps.put("modality", Map.of(
                "type", "string",
                "enum", List.of("must", "must_not", "never", "requires", "verify", "approval", "halt")
        ));
        ruleProps.put("keywords", Map.of(
                "type", "array",
                "maxItems", 4,
                "items", Map.of(
                        "type", "object",
                        "properties", Map.of("term", Map.of("type", "string")),
                        "required", List.of("term")
                )
        ));
        ruleProps.put("title", Map.of(
                "type", "string",
                "description", "Specific 4-10 word rule name, e.g. 'Block OTP sharing over phone'"
        ));
        ruleProps.put("summary", Map.of(
                "type", "string",
                "description", "One or two plain-English sentences: what is required/forbidden and when"
        ));
        ruleProps.put("decision", Map.of(
                "type", "string",
                "enum", List.of("ACCEPT", "REJECT"),
                "description", "REJECT only if clearly unimportant or already covered; otherwise ACCEPT"
        ));
        ruleProps.put("rejectReason", Map.of(
                "type", "string",
                "enum", List.of("NOT_IMPORTANT", "ALREADY_COVERED")
        ));
        rule.put("properties", ruleProps);
        rule.put("required", List.of("when", "then", "title", "summary", "decision"));
        rule.put("$comment", "Do not emit source, clauseRef, quote, ruleId, documentId, or chunkId");

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        // Do NOT set additionalProperties:false — gemma/ollama often emit empty content with it
        root.put("required", List.of("rules"));
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("schemaVersion", Map.of("type", "string"));
        props.put("rules", Map.of("type", "array", "maxItems", 3, "items", rule));
        root.put("properties", props);
        root.put("$defs", Map.of("ConditionLeaf", leaf));
        return root;
    }
}
