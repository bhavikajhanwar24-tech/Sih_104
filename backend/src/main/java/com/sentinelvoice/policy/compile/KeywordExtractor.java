package com.sentinelvoice.policy.compile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic keyword harvest for PDF compile and manual text-box rules.
 * Fast — no LLM. Merges with any model-emitted keyword terms.
 */
public final class KeywordExtractor {

    private static final Pattern WORD = Pattern.compile(
            "(?i)\\b(wire\\s*transfer|otp|pin|password|cvv|beneficiary|approval|"
                    + "verify|verification|halt|terminate|must\\s+not|must\\s+never|"
                    + "secrecy|authority|callback|lakh|crore|inr)\\b"
    );

    private static final Pattern AMOUNT = Pattern.compile(
            "(?i)(?:INR|Rs\\.?|₹)\\s*[\\d,]+(?:\\.\\d+)?|\\b\\d[\\d,]*(?:\\.\\d+)?\\s*(?:lakh|crore)s?\\b"
    );

    private KeywordExtractor() {
    }

    /**
     * Merge LLM keyword objects/strings with heuristic terms from the clause text.
     * Returns list of {term, lang, category, weight} maps ready for persistence.
     */
    public static List<Map<String, Object>> merge(Object llmKeywords, String clauseText) {
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> out = new ArrayList<>();
        if (llmKeywords instanceof List<?> list) {
            for (Object item : list) {
                String term = null;
                String lang = "en";
                String cat = "CUSTOM";
                double weight = 1.0;
                if (item instanceof Map<?, ?> m) {
                    Object t = m.get("term");
                    if (t != null) {
                        term = String.valueOf(t).trim();
                    }
                    if (m.get("lang") != null) {
                        lang = String.valueOf(m.get("lang"));
                    }
                    if (m.get("category") != null) {
                        cat = String.valueOf(m.get("category"));
                    }
                    if (m.get("weight") instanceof Number n) {
                        weight = n.doubleValue();
                    }
                } else if (item != null) {
                    term = String.valueOf(item).trim();
                }
                add(out, seen, term, lang, cat, weight);
            }
        }
        for (Map<String, Object> h : fromText(clauseText)) {
            add(out, seen, String.valueOf(h.get("term")), "en",
                    String.valueOf(h.get("category")), 1.0);
        }
        return out;
    }

    public static List<Map<String, Object>> fromText(String clauseText) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (clauseText == null || clauseText.isBlank()) {
            return out;
        }
        String text = clauseText.strip();
        Matcher wm = WORD.matcher(text);
        while (wm.find()) {
            String term = wm.group().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
            out.add(kw(term, categoryFor(term)));
        }
        Matcher am = AMOUNT.matcher(text);
        while (am.find()) {
            out.add(kw(am.group().replaceAll("\\s+", " ").trim(), "PAYMENT"));
        }
        return out;
    }

    private static void add(
            List<Map<String, Object>> out, Set<String> seen,
            String term, String lang, String cat, double weight
    ) {
        if (term == null || term.isBlank() || "null".equalsIgnoreCase(term)) {
            return;
        }
        String key = term.toLowerCase(Locale.ROOT);
        if (!seen.add(key)) {
            return;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("term", term);
        m.put("lang", lang == null || lang.isBlank() ? "en" : lang);
        m.put("category", cat == null || cat.isBlank() ? "CUSTOM" : cat);
        m.put("weight", weight);
        out.add(m);
    }

    private static Map<String, Object> kw(String term, String category) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("term", term);
        m.put("lang", "en");
        m.put("category", category);
        m.put("weight", 1.0);
        return m;
    }

    private static String categoryFor(String term) {
        String t = term.toLowerCase(Locale.ROOT);
        if (t.contains("otp") || t.contains("pin") || t.contains("password") || t.contains("cvv")) {
            return "CREDENTIAL";
        }
        if (t.contains("wire") || t.contains("lakh") || t.contains("crore") || t.contains("inr")
                || t.contains("beneficiary") || t.contains("payment")) {
            return "PAYMENT";
        }
        if (t.contains("secrecy")) {
            return "SECRECY";
        }
        if (t.contains("authority")) {
            return "AUTHORITY";
        }
        if (t.contains("urgenc")) {
            return "URGENCY";
        }
        return "CUSTOM";
    }
}
