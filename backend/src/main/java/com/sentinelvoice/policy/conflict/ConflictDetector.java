package com.sentinelvoice.policy.conflict;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.sentinelvoice.policy.conflict.ConditionConstraintNormalizer.ModalityClass;
import static com.sentinelvoice.policy.conflict.ConditionConstraintNormalizer.NormalizedRule;
import static com.sentinelvoice.policy.conflict.ConditionConstraintNormalizer.NumericInterval;
import static com.sentinelvoice.policy.conflict.ConditionConstraintNormalizer.conditionsEquivalent;
import static com.sentinelvoice.policy.conflict.ConditionConstraintNormalizer.conditionsOverlap;
import static com.sentinelvoice.policy.conflict.ConditionConstraintNormalizer.contradictingFacts;
import static com.sentinelvoice.policy.conflict.ConditionConstraintNormalizer.numericFacts;
import static com.sentinelvoice.policy.conflict.ConditionConstraintNormalizer.overlapSummary;
import static com.sentinelvoice.policy.conflict.ConditionConstraintNormalizer.subsumption;

/**
 * Deterministic pairwise conflict detection. Registry keys only; no LLM.
 */
public final class ConflictDetector {

    public enum ConflictType {
        THRESHOLD_CONFLICT,
        LEVEL_CONFLICT,
        DUPLICATE,
        SUBSUMED,
        OPPOSING
    }

    public record DetectedConflict(
            ConflictType type,
            NormalizedRule ruleA,
            NormalizedRule ruleB,
            String summary,
            Map<String, Object> detail
    ) {
    }

    private ConflictDetector() {
    }

    /**
     * Compare {@code candidate} against each peer. Returns all conflicts found (may be multiple types
     * for one pair — pick the strongest: DUPLICATE &gt; OPPOSING &gt; THRESHOLD &gt; SUBSUMED &gt; LEVEL).
     */
    public static List<DetectedConflict> detectAgainst(
            NormalizedRule candidate,
            List<NormalizedRule> peers
    ) {
        List<DetectedConflict> out = new ArrayList<>();
        if (candidate == null || peers == null) {
            return out;
        }
        for (NormalizedRule peer : peers) {
            if (peer == null) {
                continue;
            }
            if (Objects.equals(candidate.ruleId(), peer.ruleId())) {
                continue;
            }
            if (candidate.uuid() != null && candidate.uuid().equals(peer.uuid())) {
                continue;
            }
            if (!candidate.sharesScope(peer)) {
                continue;
            }
            DetectedConflict c = classify(candidate, peer);
            if (c != null) {
                out.add(c);
            }
        }
        return out;
    }

    public static DetectedConflict classify(NormalizedRule a, NormalizedRule b) {
        // OPPOSING: contradictory facts or opposing modalities on overlapping scope
        List<String> contra = contradictingFacts(a, b);
        boolean modalityOpposes = a.modality().opposes(b.modality()) && conditionsOverlap(a, b);
        if (!contra.isEmpty() || modalityOpposes) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("contradictingFacts", contra);
            detail.put("modalityA", a.modality().name());
            detail.put("modalityB", b.modality().name());
            String summary = !contra.isEmpty()
                    ? "contradictory constraints on " + String.join(", ", contra)
                    : "mutually exclusive modalities (" + a.modality() + " vs " + b.modality()
                    + ") when " + overlapSummary(a, b);
            return new DetectedConflict(ConflictType.OPPOSING, a, b, summary, detail);
        }

        if (conditionsEquivalent(a, b)) {
            if (a.minLevel() == b.minLevel()) {
                return new DetectedConflict(
                        ConflictType.DUPLICATE, a, b,
                        "equivalent conditions and the same level (" + a.minLevel() + ")",
                        Map.of("minLevel", a.minLevel())
                );
            }
            return new DetectedConflict(
                    ConflictType.LEVEL_CONFLICT, a, b,
                    overlapSummary(a, b) + " but minLevel " + a.minLevel() + " vs " + b.minLevel(),
                    Map.of("minLevelA", a.minLevel(), "minLevelB", b.minLevel())
            );
        }

        // THRESHOLD: same action + overlapping other constraints, different numeric thresholds on same fact
        DetectedConflict threshold = thresholdConflict(a, b);
        if (threshold != null) {
            return threshold;
        }

        String sub = subsumption(a, b);
        if (sub != null && conditionsOverlap(a, b)) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("covers", sub.equals("A") ? "A_COVERS_B" : "B_COVERS_A");
            String summary = sub.equals("A")
                    ? "rule A subsumes rule B (" + overlapSummary(a, b) + ")"
                    : "rule B subsumes rule A (" + overlapSummary(a, b) + ")";
            return new DetectedConflict(ConflictType.SUBSUMED, a, b, summary, detail);
        }

        if (conditionsOverlap(a, b) && a.minLevel() != b.minLevel()) {
            return new DetectedConflict(
                    ConflictType.LEVEL_CONFLICT, a, b,
                    overlapSummary(a, b) + "; minLevel " + a.minLevel() + " vs " + b.minLevel(),
                    Map.of("minLevelA", a.minLevel(), "minLevelB", b.minLevel())
            );
        }

        return null;
    }

    private static DetectedConflict thresholdConflict(NormalizedRule a, NormalizedRule b) {
        if (a.actionTypes().isEmpty() || b.actionTypes().isEmpty()) {
            return null;
        }
        if (java.util.Collections.disjoint(a.actionTypes(), b.actionTypes())) {
            return null;
        }
        // Same requirement band roughly: other non-numeric facts must overlap
        for (String fact : a.factKeys()) {
            if ("ask.type".equals(fact)) {
                continue;
            }
            if (a.facts().get(fact) instanceof NumericInterval) {
                continue;
            }
            if (b.facts().containsKey(fact)) {
                var ca = a.facts().get(fact);
                var cb = b.facts().get(fact);
                if (ca != null && cb != null && !ca.overlaps(cb)) {
                    return null;
                }
            }
        }
        Map<String, NumericInterval> na = numericFacts(a);
        Map<String, NumericInterval> nb = numericFacts(b);
        for (String fact : na.keySet()) {
            if (!nb.containsKey(fact)) {
                continue;
            }
            NumericInterval ia = na.get(fact);
            NumericInterval ib = nb.get(fact);
            if (ia.equivalent(ib)) {
                continue;
            }
            // Different thresholds on same fact + same action → THRESHOLD_CONFLICT
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("fact", fact);
            detail.put("thresholdA", Map.of("lo", ia.lo(), "hi", ia.hi()));
            detail.put("thresholdB", Map.of("lo", ib.lo(), "hi", ib.hi()));
            String summary = "same action and requirement, different " + fact + " thresholds — "
                    + overlapSummary(a, b);
            return new DetectedConflict(ConflictType.THRESHOLD_CONFLICT, a, b, summary, detail);
        }
        return null;
    }
}
