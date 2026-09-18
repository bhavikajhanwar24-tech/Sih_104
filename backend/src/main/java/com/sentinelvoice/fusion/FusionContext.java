package com.sentinelvoice.fusion;

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
}
