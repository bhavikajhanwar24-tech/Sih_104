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

    private static final Pattern OBLIGATION = Pattern.compile(
            "(?i)\\b(must|shall|must not|shall not|prohibited|not permitted|forbidden|"
                    + "required|require|block|lock|halt|terminate|end the (call|interaction|session)|"
                    + "approval|approve|verify|verification|authenticate|"
                    + "above|below|exceeds|exceed|less than|greater than|limit)\\b"
    );

    private static final Pattern STRONG_OBLIGATION = Pattern.compile(
            "(?i)\\b(must|shall|never|prohibited|not permitted|forbidden|required|high[- ]risk)\\b"
    );

    private static final Pattern MONEY_MOVEMENT = Pattern.compile(
            "(?i)\\b(wire|transfers?|payments?|pay|remit\\w*|neft|rtgs|imps|swift|funds?|disburse\\w*)\\b"
    );

    private static final Pattern PAYEE_CHANGE = Pattern.compile(
            "(?i)\\b(vendor|beneficiar\\w*|payee|bank account|account details|bank details)\\b"
    );

    private static final Pattern HALT = Pattern.compile(
            "(?i)\\b(halt|terminate|end the (call|interaction|session)|disconnect|"
                    + "immediately (stop|end|terminate)|do not proceed)\\b"
    );

    private static final Pattern BLOCK_PROHIBITION = Pattern.compile(
            "(?i)\\b(must not|shall not|must never|shall never|"
                    + "never (share|ask|accept|process|release|rely)|"
                    + "prohibited|not permitted|forbidden|block|lock|"
                    + "deny|refuse|do not (process|allow|share|ask|accept|rely))\\b"
    );

    /** Soft obligations — only used when BLOCK/HALT did not match. */
    private static final Pattern ADVICE_VERIFY = Pattern.compile(
            "(?i)\\b(advice|advise|verify|verification|approval|approve|confirm|"
                    + "dual approval|step[- ]?up|escalat|out[- ]of[- ]band)\\b"
    );

    /**
     * Hard credential prohibition → floor 3.
     * e.g. "must never share OTP or PIN", "never ask … password or card verification value".
     */
    private static final Pattern CREDENTIAL_PROHIBITION_FLOOR = Pattern.compile(
            "(?i)(?:must\\s+never|shall\\s+never|never|must\\s+not|shall\\s+not|do\\s+not)\\s+"
                    + "(?:share|ask|solicit|request|disclose|reveal|read\\s+out).{0,120}"
                    + "\\b(?:otp|pins?|password|passwd|cvv|cvv2|card\\s+verification|credentials?)\\b"
                    + "|"
                    + "\\b(?:otp|pins?|password|passwd|cvv|cvv2|card\\s+verification|credentials?)\\b.{0,80}"
                    + "(?:must\\s+never|never|must\\s+not|shall\\s+not).{0,40}"
                    + "(?:share|ask|solicit|disclose|reveal)"
    );

    /**
     * Must-not-accept unverified → floor 3.
     * e.g. "must not be accepted from … unverified numbers".
     */
    private static final Pattern UNVERIFIED_ACCEPT_FLOOR = Pattern.compile(
            "(?i)(?:must\\s+not|shall\\s+not|do\\s+not|never).{0,40}"
                    + "(?:accept|accepted|process|release|honou?r|act\\s+on).{0,100}unverif"
                    + "|"
                    + "unverif.{0,80}(?:must\\s+not|shall\\s+not|do\\s+not|never).{0,40}"
                    + "(?:accept|accepted|process|release)"
    );

    /** Approval / verification required → floor 2. */
    private static final Pattern APPROVAL_VERIFY_FLOOR = Pattern.compile(
            "(?i)\\b(?:dual\\s+approval|supervisor\\s+approval|out[- ]of[- ]band|"
                    + "approval|approve|verify|verification|step[- ]?up|confirm)\\b"
    );

    private RuleValidator() {
    }

    public record Result(Map<String, Object> rule, boolean autoReject, List<Map<String, Object>> warnings) {
    }

    public record EditValidation(boolean ok, List<String> errors, List<Map<String, Object>> warnings) {
    }

    /**
     * Only the LLM may reject a proposed rule (decision=REJECT: not important / already covered).
     * Every deterministic check repairs the rule or adds a non-blocking warning instead, except
     * when nothing enforceable is left (no usable condition at all).
     */
    public static Result validate(Map<String, Object> proposed, String chunkText, boolean injection) {
        List<Map<String, Object>> warnings = new ArrayList<>();
        Map<String, Object> rule = new LinkedHashMap<>(proposed);
        String ruleId = String.valueOf(rule.getOrDefault("ruleId", "?"));

        if (chunkText != null && !chunkText.isBlank()
                && !OBLIGATION.matcher(chunkText).find() && !ChunkPrefilter.isCandidate(chunkText)) {
            warnings.add(warn("SOFT_CLAUSE",
                    "Clause has no must/shall/threshold wording — kept because the LLM judged it relevant"));
        }

        Map<String, Object> source = asMap(rule.get("source"));
        String quote = source.get("quote") == null ? "" : String.valueOf(source.get("quote"));
        if ((quote.isBlank() || !containsNormalised(chunkText, quote))
                && chunkText != null && !chunkText.isBlank()) {
            quote = clipQuote(chunkText);
            source.put("quote", quote);
            warnings.add(warn("QUOTE_REPAIRED", "Quote re-taken from the source chunk"));
        }
        if (quote.length() > 200) {
            quote = quote.substring(0, 200);
            source.put("quote", quote);
        }
        rule.put("source", source);

        Map<String, Object> when = repairWhen(asMap(rule.get("when")), warnings);
        if (chunkText != null && !chunkText.isBlank()) {
            when = groundInClause(when, chunkText, warnings);
        }
        rule.put("when", when);
        List<Map<String, Object>> leaves = ConditionEnglish.collectLeaves(when);
        if (leaves.isEmpty()) {
            return reject(rule, warnings, ruleId, "EMPTY_WHEN", "No usable condition to enforce");
        }

        if (leaves.size() == 1 && FactCatalogue.isNonDiscriminating(String.valueOf(leaves.get(0).get("fact")))) {
            warnings.add(warn("BROAD_CONDITION",
                    "Condition uses a single broad fact (" + leaves.get(0).get("fact") + ") — may fire often"));
        }

        Map<String, Object> then = asMap(rule.get("then"));
        int floor = levelFloor(chunkText, quote);
        int minLevel = then.get("minLevel") instanceof Number minNum ? minNum.intValue() : 0;
        if (minLevel <= 0) {
            minLevel = Math.max(floor, 2);
            warnings.add(warn("LEVEL_ADJUSTED", "Missing/zero minLevel set to " + minLevel));
        }
        if (minLevel > 4) {
            minLevel = 4;
        }

        String decision = String.valueOf(rule.getOrDefault("decision", "ACCEPT")).toUpperCase(Locale.ROOT);
        String reason = String.valueOf(rule.getOrDefault("rejectReason", "NOT_IMPORTANT")).toUpperCase(Locale.ROOT);
        if ("REJECT".equals(decision) && !"ALREADY_COVERED".equals(reason)
                && chunkText != null && STRONG_OBLIGATION.matcher(chunkText).find()) {
            // Small local models mislabel clear must/never clauses as unimportant — keep them
            warnings.add(warn("LLM_SUGGESTED_REJECT",
                    "LLM suggested this is not important, but the clause uses mandatory wording — kept"));
            decision = "ACCEPT";
        }
        if ("REJECT".equals(decision)) {
            then.put("minLevel", minLevel);
            rule.put("then", then);
            return "ALREADY_COVERED".equals(reason)
                    ? reject(rule, warnings, ruleId, "LLM_ALREADY_COVERED",
                            "LLM: already covered by another rule")
                    : reject(rule, warnings, ruleId, "LLM_NOT_IMPORTANT",
                            "LLM: clause is not important enough to enforce");
        }

        int cap = levelCap(chunkText, quote);
        // Floor first (raise), then cap (clamp down). Both may emit LEVEL_ADJUSTED.
        if (minLevel < floor) {
            log.info(
                    "rule_validation_flag ruleId={} check=LEVEL_ADJUSTED from={} to={} reason=below_floor",
                    ruleId, minLevel, floor
            );
            warnings.add(warn("LEVEL_ADJUSTED",
                    "minLevel " + minLevel + " raised to floor " + floor
                            + " (credential never-share/ask→3, must-not-accept unverified→3,"
                            + " approval/verification→2; floor=" + floor + ", cap=" + cap + ")"));
            minLevel = floor;
        }
        if (minLevel > cap) {
            log.info(
                    "rule_validation_flag ruleId={} check=LEVEL_ADJUSTED from={} to={} reason=above_cap",
                    ruleId, minLevel, cap
            );
            warnings.add(warn("LEVEL_ADJUSTED",
                    "minLevel " + minLevel + " clamped down to cap " + cap
                            + " (halt→4, must-never/must-not/block→3, advice/verify/approval→2;"
                            + " floor=" + floor + ", cap=" + cap + ")"));
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
     * Fix a condition tree instead of rejecting it: off-catalogue enum values are mapped to the
     * closest enum (or OTHER), scalar IN values are wrapped, and leaves that still cannot be
     * evaluated (unknown fact / unusable value) are dropped. Returns an empty map when nothing
     * usable remains.
     */
    public static Map<String, Object> repairWhen(Map<String, Object> when, List<Map<String, Object>> warnings) {
        if (when == null || when.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Object repaired = repairNode(when, warnings);
        return repaired instanceof Map<?, ?> ? asMap(repaired) : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static Object repairNode(Object node, List<Map<String, Object>> warnings) {
        if (!(node instanceof Map<?, ?> raw)) {
            return null;
        }
        Map<String, Object> n = new LinkedHashMap<>((Map<String, Object>) raw);
        for (String key : List.of("all", "any")) {
            if (n.get(key) instanceof List<?> kids) {
                List<Object> kept = new ArrayList<>();
                for (Object kid : kids) {
                    Object r = repairNode(kid, warnings);
                    if (r != null) {
                        kept.add(r);
                    }
                }
                if (kept.isEmpty()) {
                    return null;
                }
                if (kept.size() == 1) {
                    return kept.get(0);
                }
                n.put(key, kept);
                return n;
            }
        }
        if (n.containsKey("not")) {
            Object r = repairNode(n.get("not"), warnings);
            if (r == null) {
                return null;
            }
            n.put("not", r);
            return n;
        }
        if (n.containsKey("fact")) {
            return repairLeaf(n, warnings);
        }
        return null;
    }

    private static Map<String, Object> repairLeaf(Map<String, Object> leaf, List<Map<String, Object>> warnings) {
        String fact = String.valueOf(leaf.get("fact"));
        String op = leaf.get("op") == null ? "EQ" : String.valueOf(leaf.get("op")).toUpperCase(Locale.ROOT);
        leaf.put("op", op);
        if (!FactCatalogue.isKnown(fact)) {
            warnings.add(warn("CONDITION_DROPPED", "Dropped condition on unknown fact " + fact));
            return null;
        }
        Object value = leaf.get("value");
        if (("IN".equals(op) || "NOT_IN".equals(op)) && value != null && !(value instanceof List<?>)) {
            value = List.of(value);
            leaf.put("value", value);
        }
        if (FactCatalogue.validateValue(fact, op, value).isEmpty()) {
            return leaf;
        }
        Object fixed = coerceValue(fact, value);
        if (fixed != null && FactCatalogue.validateValue(fact, op, fixed).isEmpty()) {
            warnings.add(warn("VALUE_NORMALISED",
                    fact + " value " + value + " mapped to " + fixed));
            leaf.put("value", fixed);
            return leaf;
        }
        warnings.add(warn("CONDITION_DROPPED",
                "Dropped condition " + fact + " " + op + " " + value + " (value not usable)"));
        return null;
    }

    /**
     * Remove conditions the clause does not support: numeric thresholds whose number never
     * appears in the clause are dropped, and a WIRE_TRANSFER ask type on a clause that never
     * mentions moving money is re-mapped (payee/bank-detail wording → BENEFICIARY_CHANGE).
     */
    static Map<String, Object> groundInClause(
            Map<String, Object> when, String chunkText, List<Map<String, Object>> warnings
    ) {
        Object grounded = groundNode(when, chunkText, warnings);
        return grounded instanceof Map<?, ?> ? asMap(grounded) : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static Object groundNode(Object node, String chunkText, List<Map<String, Object>> warnings) {
        if (!(node instanceof Map<?, ?> raw)) {
            return null;
        }
        Map<String, Object> n = new LinkedHashMap<>((Map<String, Object>) raw);
        for (String key : List.of("all", "any")) {
            if (n.get(key) instanceof List<?> kids) {
                List<Object> kept = new ArrayList<>();
                for (Object kid : kids) {
                    Object r = groundNode(kid, chunkText, warnings);
                    if (r != null) {
                        kept.add(r);
                    }
                }
                if (kept.isEmpty()) {
                    return null;
                }
                if (kept.size() == 1) {
                    return kept.get(0);
                }
                n.put(key, kept);
                return n;
            }
        }
        if (n.containsKey("not")) {
            Object r = groundNode(n.get("not"), chunkText, warnings);
            if (r == null) {
                return null;
            }
            n.put("not", r);
            return n;
        }
        if (!n.containsKey("fact")) {
            return n;
        }
        String fact = String.valueOf(n.get("fact"));
        Object value = n.get("value");
        boolean pmHour = "time.hourLocal".equals(fact) && value instanceof Number h && h.intValue() > 12
                && SourceNumberParser.containsNumber(chunkText, h.intValue() - 12);
        if (value instanceof Number num && !pmHour && !SourceNumberParser.containsNumber(chunkText, num)) {
            warnings.add(warn("NUMBER_DROPPED",
                    "Dropped " + fact + " " + n.get("op") + " " + num + " — that number is not in the clause"));
            return null;
        }
        if ("ask.type".equals(fact) && "WIRE_TRANSFER".equals(String.valueOf(value))
                && !MONEY_MOVEMENT.matcher(chunkText).find()) {
            String mapped = PAYEE_CHANGE.matcher(chunkText).find() ? "BENEFICIARY_CHANGE" : "OTHER";
            warnings.add(warn("VALUE_NORMALISED",
                    "ask.type WIRE_TRANSFER mapped to " + mapped + " — clause does not mention moving money"));
            n.put("value", mapped);
        }
        return n;
    }

    private static Object coerceValue(String fact, Object value) {
        if (value instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                FactCatalogue.coerceEnumValue(fact, item).ifPresent(v -> {
                    if (!out.contains(v)) {
                        out.add(v);
                    }
                });
            }
            return out.isEmpty() ? null : out;
        }
        return FactCatalogue.coerceEnumValue(fact, value).orElse(null);
    }

    private static String clipQuote(String chunkText) {
        String s = WS.matcher(chunkText.strip()).replaceAll(" ");
        if (s.length() <= 200) {
            return s;
        }
        return s.substring(0, 200).replaceAll("\\s+\\S*$", "").strip();
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
        if (source == null
                || source.get("clauseRef") == null
                || String.valueOf(source.get("clauseRef")).isBlank()) {
            errors.add("source.clauseRef is required — pick a source chunk in the document viewer");
        }
        if (!quote.isBlank() && chunkText != null && !chunkText.isBlank()
                && !containsNormalised(chunkText, quote)) {
            warnings.add(warn("QUOTE_UNVERIFIED", "Quote was not found verbatim in the source clause"));
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
            warnings.add(warn("BROAD_CONDITION",
                    "Condition uses a single broad fact (" + leaves.get(0).get("fact") + ") — may fire often"));
        }
        for (Map<String, Object> leaf : leaves) {
            String fact = String.valueOf(leaf.get("fact"));
            String op = String.valueOf(leaf.get("op"));
            Object value = leaf.get("value");
            Optional<String> err = FactCatalogue.validateValue(fact, op, value);
            err.ifPresent(errors::add);
            if (chunkText != null && value instanceof Number n
                    && !SourceNumberParser.containsNumber(chunkText, n)) {
                warnings.add(warn("NUMBER_UNVERIFIED",
                        "Number " + n + " for " + fact + " was not found verbatim in the clause"));
            }
        }
        if (then != null) {
            Object ml = then.get("minLevel");
            if (!(ml instanceof Number n) || n.intValue() <= 0 || n.intValue() > 4) {
                errors.add("minLevel must be an integer 1..4");
            } else {
                int minLevel = n.intValue();
                int floor = levelFloor(chunkText, quote);
                int cap = levelCap(chunkText, quote);
                if (minLevel < floor || minLevel > cap) {
                    warnings.add(warn("LEVEL_ADJUSTED",
                            "minLevel " + minLevel + " is outside the level suggested by the clause wording"
                                    + " (floor=" + floor + ", cap=" + cap + ")"));
                }
            }
        }
        return new EditValidation(errors.isEmpty(), errors, warnings);
    }

    /**
     * Admin-directive rules have no document citation — skip quote/clauseRef/number-in-source.
     * Still enforce catalogue keys, types, non-discriminating leave, and level 1..4.
     */
    public static EditValidation validateEditAdmin(
            Map<String, Object> when,
            Map<String, Object> then,
            Map<String, Object> source
    ) {
        List<String> errors = new ArrayList<>();
        List<Map<String, Object>> warnings = new ArrayList<>();
        if (source != null) {
            String basis = source.get("basis") == null ? "" : String.valueOf(source.get("basis")).trim();
            if (basis.length() < 15) {
                errors.add("Admin directive basis must be at least 15 characters");
            }
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
            warnings.add(warn("BROAD_CONDITION",
                    "Condition uses a single broad fact (" + leaves.get(0).get("fact") + ") — may fire often"));
        }
        for (Map<String, Object> leaf : leaves) {
            String fact = String.valueOf(leaf.get("fact"));
            String op = String.valueOf(leaf.get("op"));
            Object value = leaf.get("value");
            Optional<String> err = FactCatalogue.validateValue(fact, op, value);
            err.ifPresent(errors::add);
        }
        if (then != null) {
            Object ml = then.get("minLevel");
            if (!(ml instanceof Number n) || n.intValue() <= 0 || n.intValue() > 4) {
                errors.add("minLevel must be an integer 1..4");
            }
        }
        warnings.add(warn("ADMIN_DIRECTIVE", "Rule origin is an admin directive (no document clause)"));
        return new EditValidation(errors.isEmpty(), errors, warnings);
    }

    /** Public floor/cap pair for Live Rules UI (same logic as validate). */
    public static LevelBand levelBand(String chunkText, String quote) {
        return new LevelBand(levelFloor(chunkText, quote), levelCap(chunkText, quote));
    }

    public record LevelBand(int floor, int cap) {
    }

    /** True when warnings include a quote/source hallucination that blocks Accept. */
    public static boolean blocksAcceptance(List<Map<String, Object>> warnings) {
        if (warnings == null) {
            return false;
        }
        for (Map<String, Object> w : warnings) {
            String code = String.valueOf(w.get("code"));
            if ("HALLUCINATED_QUOTE".equals(code)
                    || "VALUE_NOT_IN_SOURCE".equals(code)
                    || "REJECTED_VALUE_NOT_IN_SOURCE".equals(code)) {
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

    /**
     * Language floor for minLevel. 0 means no floor (do not raise).
     * Credential never-share/ask and must-not-accept-unverified → 3;
     * approval/verification-required → 2. Highest matching floor wins.
     */
    private static int levelFloor(String chunkText, String quote) {
        String text = ((chunkText == null ? "" : chunkText) + " " + (quote == null ? "" : quote))
                .toLowerCase(Locale.ROOT);
        int floor = 0;
        if (CREDENTIAL_PROHIBITION_FLOOR.matcher(text).find()
                || UNVERIFIED_ACCEPT_FLOOR.matcher(text).find()) {
            floor = Math.max(floor, 3);
        }
        if (APPROVAL_VERIFY_FLOOR.matcher(text).find()) {
            floor = Math.max(floor, 2);
        }
        return floor;
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
