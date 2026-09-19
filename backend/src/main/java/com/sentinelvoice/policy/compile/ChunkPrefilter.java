package com.sentinelvoice.policy.compile;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Cheap deterministic pre-filter — only obligation-like chunks go to the LLM.
 */
public final class ChunkPrefilter {

    private static final Pattern KEYWORD = Pattern.compile(
            "(?i)\\b(must|shall|shall not|must not|prohibited|not permitted|forbidden|"
                    + "required|require|above|below|exceeds|exceed|less than|greater than|"
                    + "approval|approve|verify|verification|authenticate|otp|pin|password|"
                    + "urgent|urgency|confidential|secrecy|do not tell|wire|transfer|"
                    + "beneficiary|authority|limit|inr|rupees?)\\b"
    );

    private ChunkPrefilter() {
    }

    public static boolean isCandidate(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String t = text.strip();
        if (t.length() < 40) {
            return false;
        }
        // Tables often carry thresholds
        if (t.contains(" | ") && KEYWORD.matcher(t).find()) {
            return true;
        }
        return KEYWORD.matcher(t).find();
    }

    public static Set<String> matchedCategories(String text) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        java.util.LinkedHashSet<String> cats = new java.util.LinkedHashSet<>();
        if (lower.matches("(?s).*(urgent|urgency|asap|immediate).*")) {
            cats.add("URGENCY");
        }
        if (lower.matches("(?s).*(confidential|secrecy|do not tell|secret).*")) {
            cats.add("SECRECY");
        }
        if (lower.matches("(?s).*(authority|authoris|approv|cfo|cro).*")) {
            cats.add("AUTHORITY");
        }
        if (lower.matches("(?s).*(wire|transfer|payment|beneficiary|inr|rupee).*")) {
            cats.add("PAYMENT");
        }
        if (lower.matches("(?s).*(otp|pin|password|credential).*")) {
            cats.add("CREDENTIAL");
        }
        return cats;
    }
}
