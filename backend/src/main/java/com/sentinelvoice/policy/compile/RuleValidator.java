package com.sentinelvoice.policy.compile;

import com.sentinelvoice.policy.dsl.ConditionEnglish;
import com.sentinelvoice.policy.dsl.FactCatalogue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic validation of LLM-proposed (and manually edited) rules against the source chunk.
 */
public final class RuleValidator {

    private static final Logger log = LoggerFactory.getLogger(RuleValidator.class);

    private static final Pattern WS = Pattern.compile("\\s+");
    private static final Pattern SMART_QUOTES = Pattern.compile("[\u2018\u2019\u201A\u201B\u2032\u2035`]");
    private static final Pattern SMART_DOUBLE = Pattern.compile("[\u201C\u201D\u201E\u201F\u2033\u2036«»]");
    private static final Pattern DASHES = Pattern.compile("[\u2010\u2011\u2012\u2013\u2014\u2015\u2212]");

    private static final Pattern DEFINITION_SCOPE = Pattern.compile(
            "(?i)\\b(definitions?|for the purposes of|this (policy|document|section) applies|"
                    + "scope of this|hereinafter|means the following|glossary)\\b"
    );

    private static final Pattern OBLIGATION = Pattern.compile(
            "(?i)\\b(must|shall|must not|shall not|prohibited|not permitted|forbidden|"
                    + "required|require|block|lock|halt|terminate|end the (call|interaction|session)|"
                    + "approval|approve|verify|verification|authenticate|"
                    + "above|below|exceeds|exceed|less than|greater than|limit)\\b"
    );

    private static final Pattern HALT = Pattern.compile(
            "(?i)\\b(halt|terminate|end the (call|interaction|session)|disconnect|"
                    + "immediately (stop|end|terminate)|do not proceed)\\b"
    );

    private static final Pattern BLOCK_PROHIBITION = Pattern.compile(
            "(?i)\\b(must not|shall not|prohibited|not permitted|forbidden|block|lock|"
                    + "deny|refuse|do not (process|allow|share))\\b"
    );

    private static final Pattern ADVICE_VERIFY = Pattern.compile(
            "(?i)\\b(advice|advise|verify|verification|approval|approve|confirm|"
                    + "step[- ]?up|escalat)\\b"
    );

    private RuleValidator() {
    }

    public record Result(Map<String, Object> rule, boolean autoReject, List<Map<String, Object>> warnings) {
    }

    public record EditValidation(boolean ok, List<String> errors, List<Map<String, Object>> warnings) {
    }

    public static Result validate(Map<String, Object> proposed, String chunkText, boolean injection) {
        List<Map<String, Object>> warnings = new ArrayList<>();
        Map<String, Object> rule = new LinkedHashMap<>(proposed);
        String ruleId = String.valueOf(rule.getOrDefault("ruleId", "?"));

        // Grounding: general / definition / scope clauses must never yield rules
        if (chunkText != null && !chunkText.isBlank()) {
            if (DEFINITION_SCOPE.matcher(chunkText).find() && !OBLIGATION.matcher(chunkText).find()) {
                return reject(rule, warnings, ruleId, "UNGBOUNDED_CLAUSE",
                        "Chunk looks like a definition/scope clause with no obligation or threshold");
            }
            if (!OBLIGATION.matcher(chunkText).find() && !ChunkPrefilter.isCandidate(chunkText)) {
                return reject(rule, warnings, ruleId, "UNGBOUNDED_CLAUSE",
                        "Chunk contains no obligation, prohibition, or threshold language");
            }
        }

        Map<String, Object> source = asMap(rule.get("source"));
        Object clauseRef = source.get("clauseRef");
        if (clauseRef == null || String.valueOf(clauseRef).isBlank()) {
            return reject(rule, warnings, ruleId, "MISSING_CLAUSE_REF",
                    "source.clauseRef is required");
        }

        String quote = source.get("quote") == null ? "" : String.valueOf(source.get("quote"));
        if (quote.isBlank() || !containsNormalised(chunkText, quote)) {
            log.info(
                    "rule_validation_reject ruleId={} check=HALLUCINATED_QUOTE quoteLen={} chunkLen={}",
                    ruleId,
                    quote.length(),
                    chunkText == null ? 0 : chunkText.length()
            );
            return reject(rule, warnings, ruleId, "HALLUCINATED_QUOTE",
                    "Quote does not appear in the source chunk (after normalisation)");
        }
        if (quote.length() > 200) {
            source.put("quote", quote.substring(0, 200));
            rule.put("source", source);
        }

        Map<String, Object> when = asMap(rule.get("when"));
        if (when.isEmpty()) {
            return reject(rule, warnings, ruleId, "EMPTY_WHEN", "when condition is empty");
        }

        List<Map<String, Object>> leaves = ConditionEnglish.collectLeaves(when);
        if (leaves.isEmpty()) {
            return reject(rule, warnings, ruleId, "EMPTY_WHEN", "when has no leaf conditions");
        }

        // Single non-discriminating fact
        if (leaves.size() == 1) {
            String only = String.valueOf(leaves.get(0).get("fact"));
            if (FactCatalogue.isNonDiscriminating(only)) {
                return reject(rule, warnings, ruleId, "NON_DISCRIMINATING",
                        "Condition is a single non-discriminating fact: " + only);
            }
        }

        for (Map<String, Object> leaf : leaves) {
            String fact = String.valueOf(leaf.get("fact"));
            String op = String.valueOf(leaf.get("op"));
            Object value = leaf.get("value");
            if (!FactCatalogue.isKnown(fact)) {
                log.info("rule_validation_reject ruleId={} check=UNKNOWN_FACT fact={}", ruleId, fact);
                return reject(rule, warnings, ruleId, "UNKNOWN_FACT", "Unknown fact path: " + fact);
            }
            Optional<String> typeErr = FactCatalogue.validateValue(fact, op, value);
            if (typeErr.isPresent()) {
                log.info("rule_validation_reject ruleId={} check=INVALID_VALUE msg={}", ruleId, typeErr.get());
                return reject(rule, warnings, ruleId, "INVALID_VALUE", typeErr.get());
            }
        }

        // Number verification — every numeric literal must equal a number found in the chunk
        for (Object num : ConditionEnglish.collectNumericLiterals(when)) {
            if (!(num instanceof Number n)) {
                continue;
            }
            if (!SourceNumberParser.containsNumber(chunkText, n)) {
                log.info(
                        "rule_validation_reject ruleId={} check=REJECTED_VALUE_NOT_IN_SOURCE value={} chunkNums={}",
                        ruleId,
                        n,
                        SourceNumberParser.extractNumbers(chunkText)
                );
                return reject(
                        rule,
                        warnings,
                        ruleId,
                        "REJECTED_VALUE_NOT_IN_SOURCE",
                        "Numeric literal " + n + " does not equal any number found in the cited chunk"
                );
            }
        }

        // Thresholds that cite session/time facts must appear as numbers in the clause
        for (Map<String, Object> leaf : leaves) {
            String fact = String.valueOf(leaf.get("fact"));
            if (("session.durationSec".equals(fact) || "time.hourLocal".equals(fact)
                    || "ask.urgencyLevel".equals(fact) || "caller.authorityLimitInr".equals(fact)
                    || "ask.amountInr".equals(fact))
                    && leaf.get("value") instanceof Number n
                    && !SourceNumberParser.containsNumber(chunkText, n)) {
                return reject(
                        rule,
                        warnings,
                        ruleId,
                        "REJECTED_VALUE_NOT_IN_SOURCE",
                        "Threshold for " + fact + "=" + n + " is not grounded in the chunk"
                );
            }
        }

        Map<String, Object> then = asMap(rule.get("then"));
        Object minLevelObj = then.get("minLevel");
        if (!(minLevelObj instanceof Number minNum)) {
            return reject(rule, warnings, ruleId, "INVALID_LEVEL", "minLevel must be an integer 1..4");
        }
        int minLevel = minNum.intValue();
        if (minLevel <= 0) {
            log.info("rule_validation_reject ruleId={} check=MIN_LEVEL_ZERO", ruleId);
            return reject(rule, warnings, ruleId, "INVALID_LEVEL", "minLevel 0 has no effect and is rejected");
        }
        if (minLevel > 4) {
            minLevel = 4;
        }

        int cap = levelCap(chunkText, quote);
        if (minLevel > cap) {
            log.info("rule_validation_flag ruleId={} check=LEVEL_ADJUSTED from={} to={}", ruleId, minLevel, cap);
            warnings.add(warn("LEVEL_ADJUSTED",
                    "minLevel " + minLevel + " clamped to " + cap + " based on clause language"));
            minLevel = cap;
        }
        then.put("minLevel", minLevel);
        rule.put("then", then);

        if (injection) {
            warnings.add(warn("INJECTION_FLAG", "Source chunk carries a prompt-injection flag"));
            warnings.add(warn("NEEDS_REVIEW", "Review carefully — untrusted prompt content in source"));
        }

        if (rule.get("status") == null || "PROPOSED".equals(rule.get("status"))) {
            rule.put("status", "PROPOSED");
        }
        rule.put("warnings", warnings);
        // Recompute plain English after adjustments
        rule.put("plainEnglish", ConditionEnglish.render(
                when,
                then,
                asMap(rule.get("appliesTo"))
        ));
        return new Result(rule, false, warnings);
    }

    /**
     * Validate a manual edit payload — used by API and surfaced to the UI.
     * {@code chunkText} must be the cited source chunk(s); {@code quote} is the rule's source.quote.
     */
    public static EditValidation validateEdit(
            Map<String, Object> when,
            Map<String, Object> then,
            Map<String, Object> source,
            String chunkText
    ) {
        List<String> errors = new ArrayList<>();
        List<Map<String, Object>> warnings = new ArrayList<>();
        String quote = source == null || source.get("quote") == null
                ? ""
                : String.valueOf(source.get("quote"));
        if (quote.isBlank()) {
            errors.add("quote not found in source");
            warnings.add(warn("HALLUCINATED_QUOTE", "quote not found in source"));
        } else if (chunkText == null || chunkText.isBlank() || !containsNormalised(chunkText, quote)) {
            errors.add("quote not found in source");
            warnings.add(warn("HALLUCINATED_QUOTE", "quote not found in source"));
        }
        if (when == null || when.isEmpty()) {
            errors.add("Condition (when) is required");
            return new EditValidation(false, errors, warnings);
        }
        List<Map<String, Object>> leaves = ConditionEnglish.collectLeaves(when);
        if (leaves.isEmpty()) {
            errors.add("Condition has no leaf facts");
        }
        if (leaves.size() == 1 && FactCatalogue.isNonDiscriminating(String.valueOf(leaves.get(0).get("fact")))) {
            errors.add("A single non-discriminating fact is not allowed");
        }
        for (Map<String, Object> leaf : leaves) {
            String fact = String.valueOf(leaf.get("fact"));
            String op = String.valueOf(leaf.get("op"));
            Object value = leaf.get("value");
            Optional<String> err = FactCatalogue.validateValue(fact, op, value);
            err.ifPresent(errors::add);
            if (chunkText != null && value instanceof Number n
                    && !SourceNumberParser.containsNumber(chunkText, n)) {
                errors.add(
                        "Numeric value " + n + " for " + fact
                                + " is not in the source clause (Indian/Western formats checked)"
                );
                warnings.add(warn(
                        "REJECTED_VALUE_NOT_IN_SOURCE",
                        "Numeric literal " + n + " not found in source"
                ));
            }
        }
        if (then != null) {
            Object ml = then.get("minLevel");
            if (!(ml instanceof Number n) || n.intValue() <= 0 || n.intValue() > 4) {
                errors.add("minLevel must be an integer 1..4");
            }
        }
        return new EditValidation(errors.isEmpty(), errors, warnings);
    }

    /** True when warnings include a quote/source hallucination that blocks Accept. */
    public static boolean blocksAcceptance(List<Map<String, Object>> warnings) {
        if (warnings == null) {
            return false;
        }
        for (Map<String, Object> w : warnings) {
            String code = String.valueOf(w.get("code"));
            if ("HALLUCINATED_QUOTE".equals(code) || "REJECTED_VALUE_NOT_IN_SOURCE".equals(code)) {
                return true;
            }
        }
        return false;
    }

    private static int levelCap(String chunkText, String quote) {
        String text = ((chunkText == null ? "" : chunkText) + " " + (quote == null ? "" : quote))
                .toLowerCase(Locale.ROOT);
        if (HALT.matcher(text).find()) {
            return 4;
        }
        if (BLOCK_PROHIBITION.matcher(text).find()) {
            return 3;
        }
        if (ADVICE_VERIFY.matcher(text).find()) {
            return 2;
        }
        // Default: treat as verification/approval class
        return 2;
    }

    private static Result reject(
            Map<String, Object> rule,
            List<Map<String, Object>> warnings,
            String ruleId,
            String code,
            String message
    ) {
        log.info("rule_validation_reject ruleId={} check={} msg={}", ruleId, code, message);
        warnings.add(warn(code, message));
        rule.put("status", "REJECTED");
        rule.put("warnings", warnings);
        return new Result(rule, true, warnings);
    }

    static String normaliseForMatch(String input) {
        if (input == null) {
            return "";
        }
        String s = input.toLowerCase(Locale.ROOT);
        s = SMART_QUOTES.matcher(s).replaceAll("'");
        s = SMART_DOUBLE.matcher(s).replaceAll("\"");
        s = DASHES.matcher(s).replaceAll("-");
        s = WS.matcher(s).replaceAll(" ").strip();
        return s;
    }

    private static Map<String, Object> warn(String code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("message", message);
        return m;
    }

    private static boolean containsNormalised(String haystack, String needle) {
        String h = normaliseForMatch(haystack);
        String n = normaliseForMatch(needle);
        return !n.isEmpty() && h.contains(n);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        if (o instanceof Map<?, ?> m) {
            return new LinkedHashMap<>((Map<String, Object>) m);
        }
        return new LinkedHashMap<>();
    }
}
