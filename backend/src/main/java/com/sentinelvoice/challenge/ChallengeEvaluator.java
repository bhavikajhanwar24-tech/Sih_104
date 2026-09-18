package com.sentinelvoice.challenge;

import com.sentinelvoice.challenge.model.ChallengeEvaluation;
import com.sentinelvoice.challenge.model.ChallengeVerdict;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Combines latency, fuzzy content overlap, and acoustic consistency into a verdict.
 */
@Component
public class ChallengeEvaluator {

    private final ChallengeProperties properties;

    public ChallengeEvaluator(ChallengeProperties properties) {
        this.properties = properties;
    }

    public ChallengeEvaluation evaluate(
            String expectedPhrase,
            Long latencyMs,
            String transcript,
            Double acousticCosine
    ) {
        long latency = latencyMs == null ? Long.MAX_VALUE / 4 : Math.max(0L, latencyMs);
        double overlap = contentOverlap(expectedPhrase, transcript == null ? "" : transcript);
        double cosine = acousticCosine == null ? 0.0 : clamp01(acousticCosine);

        boolean latencyOk = latency <= properties.suspiciousLatencyMs();
        boolean latencyHuman = latency <= properties.humanLatencyMs();
        boolean contentPass = overlap >= properties.contentOverlapMin();
        boolean acousticPass = cosine >= properties.acousticCosineMin();

        ChallengeVerdict verdict;
        if (!latencyOk) {
            verdict = ChallengeVerdict.FAIL_LATENCY;
        } else if (!contentPass) {
            verdict = ChallengeVerdict.FAIL_CONTENT;
        } else if (!acousticPass) {
            verdict = ChallengeVerdict.FAIL_ACOUSTIC;
        } else {
            verdict = ChallengeVerdict.PASS;
        }

        return new ChallengeEvaluation(
                verdict,
                latency == Long.MAX_VALUE / 4 ? 0L : latency,
                overlap,
                cosine,
                latencyHuman,
                contentPass,
                acousticPass,
                expectedPhrase,
                transcript == null ? "" : transcript,
                properties.humanLatencyMs(),
                properties.suspiciousLatencyMs()
        );
    }

    /**
     * Normalised token overlap with fuzzy matching. Accepts fillers ("uh"), mispronunciations,
     * and spoken digit forms ("seven two" ≈ "72").
     */
    public double contentOverlap(String expected, String actual) {
        List<String> exp = tokenize(expected);
        List<String> act = tokenize(actual);
        if (exp.isEmpty()) {
            return 0.0;
        }
        if (act.isEmpty()) {
            return 0.0;
        }
        int matched = 0;
        boolean[] used = new boolean[act.size()];
        for (String e : exp) {
            int best = -1;
            double bestScore = 0;
            for (int i = 0; i < act.size(); i++) {
                if (used[i]) {
                    continue;
                }
                double score = tokenSimilarity(e, act.get(i));
                if (score > bestScore) {
                    bestScore = score;
                    best = i;
                }
            }
            if (best >= 0 && bestScore >= 0.72) {
                used[best] = true;
                matched++;
            }
        }
        return matched / (double) exp.size();
    }

    private static List<String> tokenize(String text) {
        String normalised = text.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\s]", " ")
                .trim();
        if (normalised.isEmpty()) {
            return List.of();
        }
        List<String> raw = new ArrayList<>(List.of(normalised.split("\\s+")));
        // Collapse spoken number sequences into digits so "seven two" ≈ "72".
        raw = collapseSpokenNumbers(raw);
        List<String> out = new ArrayList<>();
        for (String t : raw) {
            if (t.isBlank() || isFiller(t)) {
                continue;
            }
            out.add(t);
        }
        return out;
    }

    private static List<String> collapseSpokenNumbers(List<String> tokens) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            Integer asNum = spokenToNumber(t);
            if (asNum != null && asNum >= 10 && asNum < 20) {
                out.add(String.valueOf(asNum));
                continue;
            }
            // "seventy two" → 72
            if (asNum != null && asNum >= 20 && asNum % 10 == 0 && i + 1 < tokens.size()) {
                Integer next = spokenToNumber(tokens.get(i + 1));
                if (next != null && next > 0 && next < 10) {
                    out.add(String.valueOf(asNum + next));
                    i++;
                    continue;
                }
                out.add(String.valueOf(asNum));
                continue;
            }
            // digit-by-digit: "seven" "two" → "72"
            if (asNum != null && asNum > 0 && asNum < 10 && i + 1 < tokens.size()) {
                Integer next = spokenToNumber(tokens.get(i + 1));
                if (next != null && next < 10) {
                    out.add(String.valueOf(asNum * 10 + next));
                    i++;
                    continue;
                }
            }
            out.add(t);
        }
        return out;
    }

    private static Integer spokenToNumber(String word) {
        return switch (word) {
            case "zero" -> 0;
            case "one" -> 1;
            case "two" -> 2;
            case "three" -> 3;
            case "four" -> 4;
            case "five" -> 5;
            case "six" -> 6;
            case "seven" -> 7;
            case "eight" -> 8;
            case "nine" -> 9;
            case "ten" -> 10;
            case "eleven" -> 11;
            case "twelve" -> 12;
            case "thirteen" -> 13;
            case "fourteen" -> 14;
            case "fifteen" -> 15;
            case "sixteen" -> 16;
            case "seventeen" -> 17;
            case "eighteen" -> 18;
            case "nineteen" -> 19;
            case "twenty" -> 20;
            case "thirty" -> 30;
            case "forty" -> 40;
            case "fifty" -> 50;
            case "sixty" -> 60;
            case "seventy" -> 70;
            case "eighty" -> 80;
            case "ninety" -> 90;
            default -> null;
        };
    }

    private static boolean isFiller(String t) {
        return switch (t) {
            case "uh", "um", "erm", "like", "the", "a", "an", "please", "okay", "ok" -> true;
            default -> false;
        };
    }

    private static double tokenSimilarity(String a, String b) {
        if (a.equals(b)) {
            return 1.0;
        }
        int dist = levenshtein(a, b);
        int max = Math.max(a.length(), b.length());
        if (max == 0) {
            return 1.0;
        }
        return 1.0 - (dist / (double) max);
    }

    private static int levenshtein(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            dp[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(
                        Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost
                );
            }
        }
        return dp[a.length()][b.length()];
    }

    private static double clamp01(double v) {
        if (v < 0) {
            return 0;
        }
        if (v > 1) {
            return 1;
        }
        return v;
    }
}
