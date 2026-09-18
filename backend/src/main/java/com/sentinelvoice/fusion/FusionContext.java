package com.sentinelvoice.fusion;

import com.sentinelvoice.identity.model.IdentityAssessment;
import com.sentinelvoice.model.FeatureFrame;

/**
 * Inputs to {@link FusionEngineService} beyond the FeatureFrame itself.
 * Transaction / relationship scores and identity flags come from Decision Plane services.
 */
public record FusionContext(
        FeatureFrame frame,
        double transactionScore,
        boolean transactionAvailable,
        double relationshipScore,
        boolean relationshipAvailable,
        boolean cliVsClaimMismatch,
        double verbalAuthorityLimit
) {
    public static FusionContext ofFrame(FeatureFrame frame) {
        return new FusionContext(frame, 0.0, false, 0.0, false, false, Double.MAX_VALUE);
    }

    /**
     * Builds a fusion context that folds {@code identityRiskScore} into the RELATIONSHIP family
     * via {@code max(graphScore, identityRiskScore)}.
     *
     * <p><b>Why RELATIONSHIP (not a 7th weight):</b> Context §9.1–§9.2 places CLI/claim/passport
     * provenance with the interaction graph under the contextual corroboration group. Adding a
     * separate family would force weight-map and TelemetryFrame schema changes. {@code max}
     * keeps hard identity mismatches from being diluted by a benign graph score.
     */
    public static FusionContext withIdentity(
            FeatureFrame frame,
            double transactionScore,
            boolean transactionAvailable,
            double relationshipGraphScore,
            boolean relationshipGraphAvailable,
            IdentityAssessment identity
    ) {
        double identityRisk = identity != null ? identity.identityRiskScore() : 0.0;
        boolean identityAvailable = identity != null && identity.available();
        boolean relAvailable = relationshipGraphAvailable || identityAvailable;
        double relScore;
        if (relationshipGraphAvailable && identityAvailable) {
            relScore = Math.max(relationshipGraphScore, identityRisk);
        } else if (identityAvailable) {
            relScore = identityRisk;
        } else {
            relScore = relationshipGraphScore;
        }
        boolean mismatch = identity != null && identity.cliVsClaimMismatch();
        double authority = identity != null && identity.verbalAuthorityLimitInr() != null
                ? identity.verbalAuthorityLimitInr()
                : Double.MAX_VALUE;
        return new FusionContext(
                frame,
                transactionScore,
                transactionAvailable,
                relScore,
                relAvailable,
                mismatch,
                authority
        );
    }
}
