package com.sentinelvoice.directory;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Deterministic token Jaccard similarity for fuzzy name match.
 */
public final class NameSimilarity {

    private NameSimilarity() {
    }

    public static double tokenJaccard(String a, String b) {
        Set<String> ta = tokens(a);
        Set<String> tb = tokens(b);
        if (ta.isEmpty() || tb.isEmpty()) {
            return 0.0;
        }
        Set<String> inter = new LinkedHashSet<>(ta);
        inter.retainAll(tb);
        Set<String> union = new LinkedHashSet<>(ta);
        union.addAll(tb);
        return (double) inter.size() / (double) union.size();
    }

    public static String normaliseRole(String role) {
        if (role == null) {
            return "";
        }
        return role.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private static Set<String> tokens(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9\\s]", " ")
                        .trim()
                        .split("\\s+"))
                .filter(t -> t.length() > 1)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
