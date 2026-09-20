package com.sentinelvoice.fusion.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable parsed view of a fusion_configs.config JSONB document (F8).
 */
public record FusionConfigDocument(
        Weights weights,
        Smoothing smoothing,
        Map<String, Double> familyThresholds,
        Corroboration corroboration,
        Map<String, LevelBand> levels,
        InsufficientEvidence insufficientEvidence,
        MissingEvidence missingEvidence,
        HardFloors hardFloors,
        Emergency emergency,
        long overridePinDurationMs
) {

    public static final List<String> FAMILY_KEYS = List.of(
            "voice", "channel", "prosody", "linguistic", "transaction", "relationship"
    );

    private static final ObjectMapper DEFAULT_MAPPER = new ObjectMapper();

    public FusionConfigDocument {
        Objects.requireNonNull(weights, "weights");
        Objects.requireNonNull(smoothing, "smoothing");
        familyThresholds = Map.copyOf(familyThresholds);
        Objects.requireNonNull(corroboration, "corroboration");
        levels = Map.copyOf(levels);
        Objects.requireNonNull(insufficientEvidence, "insufficientEvidence");
        Objects.requireNonNull(missingEvidence, "missingEvidence");
        Objects.requireNonNull(hardFloors, "hardFloors");
        Objects.requireNonNull(emergency, "emergency");
    }

    public record Weights(Map<String, Double> wideband, Map<String, Double> narrowband) {
        public Weights {
            wideband = Map.copyOf(wideband);
            narrowband = Map.copyOf(narrowband);
        }
    }

    public record Smoothing(double lambdaUp, double lambdaDown, long linguisticStalenessTauMs) {
    }

    public record Corroboration(int minIndependentFamiliesForL3, int minForL4) {
    }

    public record LevelBand(double enter, double exit, long minDwellMs) {
    }

    public record InsufficientEvidence(long minSpeechMs) {
    }

    public record MissingEvidence(String llmUnavailable, String directoryEmpty) {
    }

    public record HardFloors(int acousticAloneMaxLevel) {
    }

    public record Emergency(boolean enabled, List<EmergencyRule> rules) {
        public Emergency {
            rules = List.copyOf(rules == null ? List.of() : rules);
        }
    }

    public record EmergencyRule(
            String id,
            int targetLevel,
            double cosineMismatchMin,
            double secrecyMin,
            double authorityMin,
            double transactionScoreMin
    ) {
    }

    public double weight(String familyKey, boolean narrowband) {
        String key = familyKey == null ? "" : familyKey.trim().toLowerCase(Locale.ROOT);
        Map<String, Double> map = narrowband ? weights.narrowband() : weights.wideband();
        Double value = map.get(key);
        if (value == null) {
            throw new IllegalStateException("Missing fusion weight for family '" + key + "'");
        }
        return value;
    }

    public double familyThreshold(String familyKey) {
        String key = familyKey == null ? "" : familyKey.trim().toLowerCase(Locale.ROOT);
        Double value = familyThresholds.get(key);
        if (value == null) {
            throw new IllegalStateException("Missing family threshold for '" + key + "'");
        }
        return value;
    }

    public LevelBand level(String key) {
        LevelBand band = levels.get(key);
        if (band == null) {
            throw new IllegalStateException("Missing level band '" + key + "'");
        }
        return band;
    }

    public static FusionConfigDocument parse(Object raw) {
        return parse(raw, DEFAULT_MAPPER);
    }

    public static FusionConfigDocument parse(Object raw, ObjectMapper mapper) {
        ObjectMapper om = mapper == null ? DEFAULT_MAPPER : mapper;
        if (raw == null) {
            throw new FusionConfigException("INVALID_CONFIG", "config is required");
        }
        try {
            JsonNode root = raw instanceof JsonNode node
                    ? node
                    : om.valueToTree(raw);
            if (root == null || !root.isObject()) {
                throw new FusionConfigException("INVALID_CONFIG", "config must be a JSON object");
            }
            Weights weights = parseWeights(root.get("weights"));
            Smoothing smoothing = parseSmoothing(root.get("smoothing"));
            Map<String, Double> thresholds = parseDoubleMap(root.get("familyThresholds"), "familyThresholds");
            Corroboration corroboration = parseCorroboration(root.get("corroboration"));
            Map<String, LevelBand> levels = parseLevels(root.get("levels"));
            InsufficientEvidence insufficient = parseInsufficient(root.get("insufficientEvidence"));
            MissingEvidence missing = parseMissing(root.get("missingEvidence"));
            HardFloors floors = parseHardFloors(root.get("hardFloors"));
            Emergency emergency = parseEmergency(root.get("emergency"));
            long overridePin = requireLong(root.get("overridePinDurationMs"), "overridePinDurationMs");
            return new FusionConfigDocument(
                    weights, smoothing, thresholds, corroboration, levels,
                    insufficient, missing, floors, emergency, overridePin
            );
        } catch (FusionConfigException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new FusionConfigException("INVALID_CONFIG", "Failed to parse fusion config: " + ex.getMessage());
        }
    }

    /** Canonical Map for SHA-256 / draft storage (stable key order). */
    public Map<String, Object> toCanonicalMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("wideband", orderedFamilyMap(weights.wideband()));
        w.put("narrowband", orderedFamilyMap(weights.narrowband()));
        out.put("weights", w);

        Map<String, Object> sm = new LinkedHashMap<>();
        sm.put("lambdaUp", smoothing.lambdaUp());
        sm.put("lambdaDown", smoothing.lambdaDown());
        sm.put("linguisticStalenessTauMs", smoothing.linguisticStalenessTauMs());
        out.put("smoothing", sm);

        out.put("familyThresholds", orderedFamilyMap(familyThresholds));

        Map<String, Object> corr = new LinkedHashMap<>();
        corr.put("minIndependentFamiliesForL3", corroboration.minIndependentFamiliesForL3());
        corr.put("minForL4", corroboration.minForL4());
        out.put("corroboration", corr);

        Map<String, Object> lvl = new LinkedHashMap<>();
        for (String key : List.of("L1", "L2", "L3", "L4")) {
            LevelBand b = level(key);
            Map<String, Object> band = new LinkedHashMap<>();
            band.put("enter", b.enter());
            band.put("exit", b.exit());
            band.put("minDwellMs", b.minDwellMs());
            lvl.put(key, band);
        }
        out.put("levels", lvl);

        out.put("insufficientEvidence", Map.of("minSpeechMs", insufficientEvidence.minSpeechMs()));
        Map<String, Object> miss = new LinkedHashMap<>();
        miss.put("llmUnavailable", missingEvidence.llmUnavailable());
        miss.put("directoryEmpty", missingEvidence.directoryEmpty());
        out.put("missingEvidence", miss);
        out.put("hardFloors", Map.of("acousticAloneMaxLevel", hardFloors.acousticAloneMaxLevel()));

        Map<String, Object> emerg = new LinkedHashMap<>();
        emerg.put("enabled", emergency.enabled());
        List<Map<String, Object>> rules = new ArrayList<>();
        for (EmergencyRule r : emergency.rules()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", r.id());
            row.put("targetLevel", r.targetLevel());
            row.put("cosineMismatchMin", r.cosineMismatchMin());
            row.put("secrecyMin", r.secrecyMin());
            row.put("authorityMin", r.authorityMin());
            row.put("transactionScoreMin", r.transactionScoreMin());
            rules.add(row);
        }
        emerg.put("rules", rules);
        out.put("emergency", emerg);
        out.put("overridePinDurationMs", overridePinDurationMs);
        return out;
    }

    private static Map<String, Double> orderedFamilyMap(Map<String, Double> src) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (String key : FAMILY_KEYS) {
            if (src.containsKey(key)) {
                out.put(key, src.get(key));
            }
        }
        for (Map.Entry<String, Double> e : src.entrySet()) {
            if (!out.containsKey(e.getKey())) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    private static Weights parseWeights(JsonNode node) {
        requireObject(node, "weights");
        return new Weights(
                parseDoubleMap(node.get("wideband"), "weights.wideband"),
                parseDoubleMap(node.get("narrowband"), "weights.narrowband")
        );
    }

    private static Smoothing parseSmoothing(JsonNode node) {
        requireObject(node, "smoothing");
        return new Smoothing(
                requireDouble(node.get("lambdaUp"), "smoothing.lambdaUp"),
                requireDouble(node.get("lambdaDown"), "smoothing.lambdaDown"),
                requireLong(node.get("linguisticStalenessTauMs"), "smoothing.linguisticStalenessTauMs")
        );
    }

    private static Corroboration parseCorroboration(JsonNode node) {
        requireObject(node, "corroboration");
        return new Corroboration(
                requireInt(node.get("minIndependentFamiliesForL3"), "corroboration.minIndependentFamiliesForL3"),
                requireInt(node.get("minForL4"), "corroboration.minForL4")
        );
    }

    private static Map<String, LevelBand> parseLevels(JsonNode node) {
        requireObject(node, "levels");
        Map<String, LevelBand> out = new LinkedHashMap<>();
        for (String key : List.of("L1", "L2", "L3", "L4")) {
            JsonNode band = node.get(key);
            requireObject(band, "levels." + key);
            out.put(key, new LevelBand(
                    requireDouble(band.get("enter"), "levels." + key + ".enter"),
                    requireDouble(band.get("exit"), "levels." + key + ".exit"),
                    requireLong(band.get("minDwellMs"), "levels." + key + ".minDwellMs")
            ));
        }
        return out;
    }

    private static InsufficientEvidence parseInsufficient(JsonNode node) {
        requireObject(node, "insufficientEvidence");
        return new InsufficientEvidence(requireLong(node.get("minSpeechMs"), "insufficientEvidence.minSpeechMs"));
    }

    private static MissingEvidence parseMissing(JsonNode node) {
        requireObject(node, "missingEvidence");
        return new MissingEvidence(
                requireText(node.get("llmUnavailable"), "missingEvidence.llmUnavailable"),
                requireText(node.get("directoryEmpty"), "missingEvidence.directoryEmpty")
        );
    }

    private static HardFloors parseHardFloors(JsonNode node) {
        requireObject(node, "hardFloors");
        return new HardFloors(requireInt(node.get("acousticAloneMaxLevel"), "hardFloors.acousticAloneMaxLevel"));
    }

    private static Emergency parseEmergency(JsonNode node) {
        requireObject(node, "emergency");
        boolean enabled = node.path("enabled").asBoolean(true);
        List<EmergencyRule> rules = new ArrayList<>();
        JsonNode arr = node.get("rules");
        if (arr != null && arr.isArray()) {
            for (JsonNode r : arr) {
                rules.add(new EmergencyRule(
                        requireText(r.get("id"), "emergency.rules[].id"),
                        requireInt(r.get("targetLevel"), "emergency.rules[].targetLevel"),
                        requireDouble(r.get("cosineMismatchMin"), "emergency.rules[].cosineMismatchMin"),
                        requireDouble(r.get("secrecyMin"), "emergency.rules[].secrecyMin"),
                        requireDouble(r.get("authorityMin"), "emergency.rules[].authorityMin"),
                        requireDouble(r.get("transactionScoreMin"), "emergency.rules[].transactionScoreMin")
                ));
            }
        }
        return new Emergency(enabled, rules);
    }

    private static Map<String, Double> parseDoubleMap(JsonNode node, String path) {
        requireObject(node, path);
        Map<String, Double> out = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> {
            if (!e.getValue().isNumber()) {
                throw new FusionConfigException("INVALID_CONFIG", path + "." + e.getKey() + " must be a number");
            }
            out.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().asDouble());
        });
        return out;
    }

    private static void requireObject(JsonNode node, String path) {
        if (node == null || !node.isObject()) {
            throw new FusionConfigException("INVALID_CONFIG", path + " must be an object");
        }
    }

    private static double requireDouble(JsonNode node, String path) {
        if (node == null || !node.isNumber()) {
            throw new FusionConfigException("INVALID_CONFIG", path + " must be a number");
        }
        return node.asDouble();
    }

    private static long requireLong(JsonNode node, String path) {
        if (node == null || !node.isNumber()) {
            throw new FusionConfigException("INVALID_CONFIG", path + " must be a number");
        }
        return node.asLong();
    }

    private static int requireInt(JsonNode node, String path) {
        if (node == null || !node.isNumber()) {
            throw new FusionConfigException("INVALID_CONFIG", path + " must be an integer");
        }
        return node.asInt();
    }

    private static String requireText(JsonNode node, String path) {
        if (node == null || node.isNull() || !node.isValueNode()) {
            throw new FusionConfigException("INVALID_CONFIG", path + " must be a string");
        }
        String v = node.asText();
        if (v == null || v.isBlank()) {
            throw new FusionConfigException("INVALID_CONFIG", path + " must be a non-blank string");
        }
        return v;
    }
}
