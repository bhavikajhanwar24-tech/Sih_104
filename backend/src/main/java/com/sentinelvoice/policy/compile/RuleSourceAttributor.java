package com.sentinelvoice.policy.compile;

import com.sentinelvoice.policy.PolicyDocumentChunkEntity;
import com.sentinelvoice.policy.dsl.ConditionEnglish;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic source attribution for compiled rules. The LLM must never invent
 * clauseRef / quote / title / ids — those come from the chunk being processed.
 */
public final class RuleSourceAttributor {

    /** Clause numbers like 2.1, 5.5(a), 10.2 (b). */
    private static final Pattern CLAUSE_NUMBER = Pattern.compile(
            "(?i)\\b(\\d{1,3}\\.\\d{1,3}(?:\\s*\\([a-z0-9]+\\))?)\\b"
    );

    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[.!?])\\s+(?=[A-Z0-9\"'])|\\n+");

    private RuleSourceAttributor() {
    }

    /**
     * Attach source, title, and ruleId from the chunk. Strips any model-supplied
     * source / title / ruleId / documentId / chunkId.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> attribute(
            Map<String, Object> raw,
            UUID documentId,
            PolicyDocumentChunkEntity chunk
    ) {
        Map<String, Object> rule = new LinkedHashMap<>(raw == null ? Map.of() : raw);
        // Discard model identity / citation fields — chunk is source of truth
        rule.remove("title");
        rule.remove("ruleId");
        rule.remove("source");
        rule.remove("documentId");
        rule.remove("chunkId");

        Map<String, Object> when = rule.get("when") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : Map.of();
        List<String> keywords = extractKeywordTerms(rule.get("keywords"));

        String clauseRef = resolveClauseRefDisplay(chunk);
        String quote = selectQuote(chunk.getText(), when, keywords, rule.get("modality"));
        String title = generateTitle(when, rule.get("modality"), quote, asMap(rule.get("then")));
        String ruleId = deterministicRuleId(documentId, clauseRef, when);

        Map<String, Object> source = new LinkedHashMap<>();
        if (documentId != null) {
            source.put("documentId", documentId.toString());
        }
        if (chunk.getId() != null) {
            source.put("chunkIds", List.of(chunk.getId().toString()));
        }
        source.put("clauseRef", clauseRef);
        source.put("quote", quote);
        source.put("attributedBy", "CODE");

        rule.put("source", source);
        rule.put("title", title);
        rule.put("ruleId", ruleId);
        rule.putIfAbsent("status", "PROPOSED");
        rule.putIfAbsent("origin", "LLM");
        if (!(rule.get("then") instanceof Map<?, ?>)) {
            Map<String, Object> then = new LinkedHashMap<>();
            then.put("minLevel", 2);
            then.put("scoreBoost", 0.2);
            then.put("reasonCode", "POLICY_GENERIC");
            rule.put("then", then);
        }
        if (!(rule.get("appliesTo") instanceof Map<?, ?>)) {
            String action = firstActionType(when);
            rule.put("appliesTo", Map.of(
                    "actionTypes", List.of(action == null ? "*" : action),
                    "callerRoles", List.of("*")
            ));
        }
        rule.putIfAbsent("severity", severityFromLevel(asMap(rule.get("then"))));
        return rule;
    }

    /**
     * Re-attribute an existing draft rule that is missing clauseRef/quote using its chunkIds.
     */
    public static Map<String, Object> reattribute(
            Map<String, Object> existing,
            UUID documentId,
            PolicyDocumentChunkEntity chunk
    ) {
        Map<String, Object> body = new LinkedHashMap<>(existing);
        // Preserve status / origin / warnings; rebuild source + title + ruleId
        String status = String.valueOf(body.getOrDefault("status", "PROPOSED"));
        String origin = String.valueOf(body.getOrDefault("origin", "LLM"));
        Object warnings = body.get("warnings");
        Map<String, Object> attributed = attribute(body, documentId, chunk);
        attributed.put("status", status);
        attributed.put("origin", origin);
        if (warnings != null) {
            attributed.put("warnings", warnings);
        }
        if (existing.get("id") != null) {
            attributed.put("id", existing.get("id"));
        }
        if (existing.get("policySetId") != null) {
            attributed.put("policySetId", existing.get("policySetId"));
        }
        return attributed;
    }

    public static String resolveClauseRef(PolicyDocumentChunkEntity chunk) {
        return resolveClauseRefDisplay(chunk);
    }

    /** Prefer preserving original case for display. */
    public static String resolveClauseRefDisplay(PolicyDocumentChunkEntity chunk) {
        if (chunk == null) {
            return "para 1";
        }
        String heading = chunk.getHeadingPath() == null ? "" : chunk.getHeadingPath().strip();
        String text = chunk.getText() == null ? "" : chunk.getText();
        Matcher hm = CLAUSE_NUMBER.matcher(heading);
        if (hm.find()) {
            return hm.group(1).replaceAll("\\s+", "");
        }
        Matcher tm = CLAUSE_NUMBER.matcher(text.length() > 120 ? text.substring(0, 120) : text);
        if (tm.find()) {
            return tm.group(1).replaceAll("\\s+", "");
        }
        List<String> parts = new ArrayList<>();
        if (!heading.isBlank()) {
            parts.add(heading.length() > 48 ? heading.substring(0, 48).strip() + "…" : heading);
        }
        if (chunk.getPageNo() != null) {
            parts.add("p." + chunk.getPageNo());
        }
        parts.add("para " + (chunk.getOrdinal() + 1));
        return String.join(" > ", parts);
    }

    public static String selectQuote(
            String chunkText,
            Map<String, Object> when,
            List<String> keywords,
            Object modality
    ) {
        String text = chunkText == null ? "" : chunkText.strip();
        if (text.isBlank()) {
            return "";
        }
        List<String> sentences = splitSentences(text);
        if (sentences.isEmpty()) {
            return clip(text, 200);
        }
        Set<String> tokens = scoringTokens(when, keywords, modality);
        String best = sentences.get(0);
        double bestScore = -1;
        for (String s : sentences) {
            double score = scoreSentence(s, tokens, when);
            if (score > bestScore) {
                bestScore = score;
                best = s;
            }
        }
        // Prefer a sentence that actually exists verbatim in the chunk
        if (!text.contains(best.strip())) {
            best = sentences.get(0);
        }
        return clip(best.strip().replaceAll("\\s+", " "), 200);
    }

    public static String generateTitle(
            Map<String, Object> when,
            Object modality,
            String quote,
            Map<String, Object> then
    ) {
        String action = firstActionType(when);
        String actionLabel = humanAction(action);
        String modalityWord = modalityLabel(modality, quote);
        Long amount = firstAmount(when);

        if (actionLabel != null && amount != null) {
            return capitalize(modalityWord + " for " + actionLabel + " above " + formatInr(amount));
        }
        if (actionLabel != null) {
            return capitalize(modalityWord + " for " + actionLabel);
        }
        if (quote != null && !quote.isBlank()) {
            String q = quote.strip();
            int cut = Math.min(72, q.length());
            String head = q.substring(0, cut).strip();
            if (cut < q.length()) {
                head = head.replaceAll("\\s+\\S*$", "") + "…";
            }
            return head;
        }
        int level = then != null && then.get("minLevel") instanceof Number n ? n.intValue() : 2;
        return "Policy rule (level " + level + ")";
    }

    public static String deterministicRuleId(UUID documentId, String clauseRef, Map<String, Object> when) {
        String basis = (documentId == null ? "nodoc" : documentId.toString())
                + "|"
                + (clauseRef == null ? "" : clauseRef)
                + "|"
                + canonicalWhen(when);
        String hash = sha256Hex(basis).substring(0, 8);
        String refSlug = (clauseRef == null ? "x" : clauseRef)
                .replaceAll("[^A-Za-z0-9.()-]+", "-")
                .replaceAll("-+", "-");
        if (refSlug.length() > 24) {
            refSlug = refSlug.substring(0, 24);
        }
        return "R-" + refSlug + "-" + hash;
    }

    public static boolean needsSourceRepair(Map<String, Object> rule) {
        Map<String, Object> source = asMap(rule.get("source"));
        Object ref = source.get("clauseRef");
        Object quote = source.get("quote");
        boolean missingRef = ref == null || String.valueOf(ref).isBlank()
                || "null".equalsIgnoreCase(String.valueOf(ref));
        boolean missingQuote = quote == null || String.valueOf(quote).isBlank()
                || "null".equalsIgnoreCase(String.valueOf(quote));
        return missingRef || missingQuote;
    }

    // --- helpers ---

    private static List<String> splitSentences(String text) {
        List<String> out = new ArrayList<>();
        for (String part : SENTENCE_SPLIT.split(text)) {
            String s = part.strip();
            if (s.length() >= 12) {
                out.add(s);
            }
        }
        if (out.isEmpty() && !text.isBlank()) {
            out.add(text.strip());
        }
        return out;
    }

    private static Set<String> scoringTokens(
            Map<String, Object> when, List<String> keywords, Object modality
    ) {
        Set<String> tokens = new LinkedHashSet<>();
        for (Map<String, Object> leaf : ConditionEnglish.collectLeaves(when)) {
            Object v = leaf.get("value");
            if (v != null) {
                for (String t : tokenize(String.valueOf(v))) {
                    tokens.add(t);
                }
            }
            String fact = String.valueOf(leaf.get("fact"));
            if (fact.contains("amount")) {
                tokens.add("inr");
                tokens.add("amount");
                tokens.add("lakh");
                tokens.add("crore");
            }
            if (fact.contains("credential") || fact.contains("otp") || fact.contains("pin")) {
                tokens.add("otp");
                tokens.add("pin");
                tokens.add("password");
                tokens.add("credential");
            }
            if ("ask.type".equals(fact) && v != null) {
                tokens.add(String.valueOf(v).toLowerCase(Locale.ROOT).replace('_', ' '));
            }
        }
        if (keywords != null) {
            for (String k : keywords) {
                tokens.addAll(tokenize(k));
            }
        }
        if (modality != null) {
            tokens.addAll(tokenize(String.valueOf(modality)));
        }
        for (String cue : List.of("must", "never", "shall", "require", "approval", "verify", "prohibited")) {
            tokens.add(cue);
        }
        return tokens;
    }

    private static double scoreSentence(String sentence, Set<String> tokens, Map<String, Object> when) {
        Set<String> st = new LinkedHashSet<>(tokenize(sentence));
        if (st.isEmpty()) {
            return 0;
        }
        int hits = 0;
        for (String t : tokens) {
            if (st.contains(t) || sentence.toLowerCase(Locale.ROOT).contains(t)) {
                hits++;
            }
        }
        // Bonus for numeric literals from the condition that appear in the sentence
        for (Object n : ConditionEnglish.collectNumericLiterals(when)) {
            String ns = String.valueOf(Math.round(((Number) n).doubleValue()));
            if (sentence.replace(",", "").contains(ns)) {
                hits += 3;
            }
        }
        return hits + Math.min(1.0, sentence.length() / 200.0);
    }

    private static List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) {
            return out;
        }
        for (String t : s.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (t.length() >= 2) {
                out.add(t);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> extractKeywordTerms(Object keywords) {
        List<String> out = new ArrayList<>();
        if (!(keywords instanceof List<?> list)) {
            return out;
        }
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                Object term = m.get("term");
                if (term != null) {
                    out.add(String.valueOf(term));
                }
            } else if (o != null) {
                out.add(String.valueOf(o));
            }
        }
        return out;
    }

    private static String firstActionType(Map<String, Object> when) {
        for (Map<String, Object> leaf : ConditionEnglish.collectLeaves(when)) {
            if ("ask.type".equals(String.valueOf(leaf.get("fact"))) && leaf.get("value") != null) {
                return String.valueOf(leaf.get("value")).toUpperCase(Locale.ROOT);
            }
        }
        return null;
    }

    private static Long firstAmount(Map<String, Object> when) {
        for (Map<String, Object> leaf : ConditionEnglish.collectLeaves(when)) {
            if ("ask.amountInr".equals(String.valueOf(leaf.get("fact")))
                    && leaf.get("value") instanceof Number n) {
                return Math.round(n.doubleValue());
            }
        }
        return null;
    }

    private static String humanAction(String action) {
        if (action == null) {
            return null;
        }
        return switch (action.toUpperCase(Locale.ROOT)) {
            case "WIRE_TRANSFER" -> "wire transfer";
            case "PASSWORD_RESET" -> "password reset";
            case "ACCOUNT_LOOKUP" -> "account lookup";
            case "BENEFICIARY_CHANGE" -> "beneficiary change";
            case "OTP_SHARE" -> "OTP share";
            case "PIN_SHARE" -> "PIN share";
            case "CALLBACK" -> "callback";
            case "INFORMATION" -> "information request";
            default -> action.toLowerCase(Locale.ROOT).replace('_', ' ');
        };
    }

    private static String modalityLabel(Object modality, String quote) {
        String m = modality == null ? "" : String.valueOf(modality).toLowerCase(Locale.ROOT);
        String q = quote == null ? "" : quote.toLowerCase(Locale.ROOT);
        if (m.contains("dual") || q.contains("dual approval")) {
            return "Dual approval";
        }
        if (m.contains("halt") || q.contains("terminate") || q.contains("disconnect")) {
            return "Halt";
        }
        if (m.contains("prohibit") || q.contains("must not") || q.contains("never")) {
            return "Prohibition";
        }
        if (m.contains("verify") || q.contains("verif")) {
            return "Verification";
        }
        if (m.contains("approv") || q.contains("approval")) {
            return "Approval";
        }
        return "Obligation";
    }

    private static String formatInr(long amount) {
        if (amount >= 10_000_000L && amount % 10_000_000L == 0) {
            return "INR " + (amount / 10_000_000L) + " crore";
        }
        if (amount >= 100_000L && amount % 100_000L == 0) {
            return "INR " + (amount / 100_000L) + " lakh";
        }
        return "INR " + amount;
    }

    private static String capitalize(String s) {
        if (s == null || s.isBlank()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String severityFromLevel(Map<String, Object> then) {
        int level = then.get("minLevel") instanceof Number n ? n.intValue() : 2;
        if (level >= 4) {
            return "CRITICAL";
        }
        if (level >= 3) {
            return "HIGH";
        }
        if (level >= 2) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max).replaceAll("\\s+\\S*$", "").strip();
    }

    private static String canonicalWhen(Map<String, Object> when) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(when == null ? Map.of() : when);
        } catch (Exception e) {
            return String.valueOf(when);
        }
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
