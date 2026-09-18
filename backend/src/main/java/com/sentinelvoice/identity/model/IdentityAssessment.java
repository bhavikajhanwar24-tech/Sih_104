package com.sentinelvoice.identity.model;

import com.sentinelvoice.fusion.ReasonCode;
import com.sentinelvoice.identity.IdentityVerdict;

import java.util.List;
import java.util.Map;

/**
 * Full Context §8.2 {@code identity} block plus {@code identityRiskScore} for fusion.
 */
public record IdentityAssessment(
        String cli,
        String cliTrunk,
        Map<String, Object> directoryMatch,
        String claimedIdentity,
        String claimedRole,
        Map<String, Object> directoryRecordForClaim,
        boolean cliVsClaimMismatch,
        VoicePassport voicePassport,
        PresenceConflict presenceConflict,
        double identityRiskScore,
        Double verbalAuthorityLimitInr,
        List<ReasonCode> criticalReasons
) {
    public record VoicePassport(boolean enrolled, Double cosine, IdentityVerdict verdict) {
    }

    public record PresenceConflict(String expected, String observed) {
    }

    /** True when enough identity signals exist to contribute to the RELATIONSHIP family. */
    public boolean available() {
        return cli != null && !cli.isBlank();
    }
}
