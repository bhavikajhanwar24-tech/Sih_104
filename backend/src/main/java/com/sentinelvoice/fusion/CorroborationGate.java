package com.sentinelvoice.fusion;

import com.sentinelvoice.config.SentinelProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Cross-group corroboration gate (Context §9.3).
 * Escalation above L2 requires ≥1 ACOUSTIC and ≥1 CONTEXTUAL family above its own threshold.
 */
@Component
public class CorroborationGate {

    /** One family from each of the two independent groups. */
    public static final int INDEPENDENT_FAMILIES_REQUIRED = 2;

    private final SentinelProperties.Fusion fusion;

    public CorroborationGate(SentinelProperties properties) {
        this.fusion = properties.fusion();
    }

    /**
     * @param availableScores map of available families to their calibrated scores S_i
     */
    public FusionResult.CorroborationDetail evaluate(Map<EvidenceFamily, Double> availableScores) {
        List<EvidenceFamily> qualifying = new ArrayList<>();
        boolean acousticHit = false;
        boolean contextualHit = false;

        for (Map.Entry<EvidenceFamily, Double> entry : availableScores.entrySet()) {
            EvidenceFamily family = entry.getKey();
            double score = entry.getValue();
            double threshold = family.corroborationThreshold(fusion);
            if (score > threshold) {
                qualifying.add(family);
                if (family.group() == EvidenceFamily.Group.ACOUSTIC) {
                    acousticHit = true;
                } else {
                    contextualHit = true;
                }
            }
        }

        boolean satisfied = acousticHit && contextualHit;
        return new FusionResult.CorroborationDetail(
                satisfied,
                List.copyOf(qualifying),
                INDEPENDENT_FAMILIES_REQUIRED
        );
    }

    /** Convenience for tests: build a score map from FamilyScore results. */
    public static Map<EvidenceFamily, Double> availableScoreMap(Map<EvidenceFamily, FamilyScore> families) {
        Map<EvidenceFamily, Double> scores = new EnumMap<>(EvidenceFamily.class);
        for (Map.Entry<EvidenceFamily, FamilyScore> entry : families.entrySet()) {
            FamilyScore fs = entry.getValue();
            if (fs.available()) {
                scores.put(entry.getKey(), fs.score());
            }
        }
        return scores;
    }
}
