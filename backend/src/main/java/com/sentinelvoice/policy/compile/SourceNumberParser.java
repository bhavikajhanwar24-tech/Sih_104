package com.sentinelvoice.policy.compile;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts numeric literals from policy clause text — Western, Indian grouping, and
 * common English amount words (lakh / crore).
 */
public final class SourceNumberParser {

    private static final Pattern DIGIT_GROUP = Pattern.compile(
            // Indian: 1,00,00,000 (pairs then final triple) | Western: 1,000,000 | plain digits
            "(?<![\\d.])("
                    + "\\d{1,3}(?:,\\d{2})*,\\d{3}"
                    + "|\\d{1,3}(?:,\\d{3})+"
                    + "|\\d+"
                    + ")(?:\\.(\\d+))?(?![\\d])"
    );

    private static final Pattern WORD_AMOUNT = Pattern.compile(
            "(?i)\\b(?:(one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|"
                    + "thirteen|fourteen|fifteen|twenty|thirty|forty|fifty|sixty|seventy|"
                    + "eighty|ninety|hundred|a)\\s+)?"
                    + "(ten\\s+)?(lakh|lakhs|crore|crores)\\b"
                    + "|\\b(\\d+(?:\\.\\d+)?)\\s*(lakh|lakhs|crore|crores)\\b"
    );

    private static final Map<String, Integer> WORD_TO_INT = Map.ofEntries(
            Map.entry("a", 1),
            Map.entry("one", 1),
            Map.entry("two", 2),
            Map.entry("three", 3),
            Map.entry("four", 4),
            Map.entry("five", 5),
            Map.entry("six", 6),
            Map.entry("seven", 7),
            Map.entry("eight", 8),
            Map.entry("nine", 9),
            Map.entry("ten", 10),
            Map.entry("eleven", 11),
            Map.entry("twelve", 12),
            Map.entry("thirteen", 13),
            Map.entry("fourteen", 14),
            Map.entry("fifteen", 15),
            Map.entry("twenty", 20),
            Map.entry("thirty", 30),
            Map.entry("forty", 40),
            Map.entry("fifty", 50),
            Map.entry("sixty", 60),
            Map.entry("seventy", 70),
            Map.entry("eighty", 80),
            Map.entry("ninety", 90),
            Map.entry("hundred", 100)
    );

    private SourceNumberParser() {
    }

    /** All distinct numeric values found in the clause (as long where integral). */
    public static Set<Long> extractNumbers(String text) {
        Set<Long> out = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        Matcher dm = DIGIT_GROUP.matcher(text);
        while (dm.find()) {
            String whole = dm.group(1).replace(",", "");
            try {
                if (dm.group(2) != null) {
                    double v = Double.parseDouble(whole + "." + dm.group(2));
                    out.add(Math.round(v));
                } else {
                    out.add(Long.parseLong(whole));
                }
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        Matcher wm = WORD_AMOUNT.matcher(text);
        while (wm.find()) {
            Long v = parseWordAmount(wm);
            if (v != null) {
                out.add(v);
            }
        }
        // Bare "ten lakh" without leading word already covered; also "1 crore"
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("ten lakh") || lower.contains("10 lakh")) {
            out.add(1_000_000L);
        }
        if (lower.contains("one crore") || lower.contains("1 crore") || lower.contains("a crore")) {
            out.add(10_000_000L);
        }
        return out;
    }

    public static boolean containsNumber(String text, Number expected) {
        if (expected == null) {
            return false;
        }
        long target = Math.round(expected.doubleValue());
        Set<Long> found = extractNumbers(text);
        if (found.contains(target)) {
            return true;
        }
        // Allow close floating compare for small decimals
        for (Long f : found) {
            if (Math.abs(f - target) == 0) {
                return true;
            }
        }
        return false;
    }

    /** Parse Indian or Western digit grouping to a long (commas stripped). */
    public static long parseGrouped(String raw) {
        String s = raw == null ? "" : raw.replace(",", "").strip();
        return Long.parseLong(s);
    }

    private static Long parseWordAmount(Matcher wm) {
        try {
            if (wm.group(4) != null && wm.group(5) != null) {
                double n = Double.parseDouble(wm.group(4));
                long unit = wm.group(5).toLowerCase(Locale.ROOT).startsWith("crore") ? 10_000_000L : 100_000L;
                return Math.round(n * unit);
            }
            int multiplier = 1;
            if (wm.group(1) != null) {
                String w = wm.group(1).toLowerCase(Locale.ROOT);
                multiplier = WORD_TO_INT.getOrDefault(w, 1);
            }
            if (wm.group(2) != null) {
                multiplier = 10;
            }
            String unitWord = wm.group(3) == null ? "" : wm.group(3).toLowerCase(Locale.ROOT);
            long unit = unitWord.startsWith("crore") ? 10_000_000L : 100_000L;
            if (unitWord.isBlank()) {
                return null;
            }
            return (long) multiplier * unit;
        } catch (Exception e) {
            return null;
        }
    }
}
