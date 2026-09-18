package com.sentinelvoice.identity;

import com.sentinelvoice.config.SentinelProperties;
import com.sentinelvoice.fusion.ReasonCode;
import com.sentinelvoice.identity.model.DirectoryRecord;
import com.sentinelvoice.identity.model.IdentityAssessment;
import com.sentinelvoice.model.CallSession;
import com.sentinelvoice.model.ChannelProfile;
import com.sentinelvoice.model.FeatureFrame;
import com.sentinelvoice.model.LinguisticFamily;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Five-stage identity pipeline (Context §12): CLI → directory → spoken claim → voiceprint → presence.
 */
@Service
public class IdentityResolutionService {

    private final DirectoryService directoryService;
    private final TrunkClassifier trunkClassifier;
    private final SentinelProperties.Identity identityProps;

    public IdentityResolutionService(
            DirectoryService directoryService,
            TrunkClassifier trunkClassifier,
            SentinelProperties properties
    ) {
        this.directoryService = directoryService;
        this.trunkClassifier = trunkClassifier;
        this.identityProps = properties.identity();
    }

    public IdentityAssessment resolve(CallSession session, FeatureFrame featureFrame) {
        String cli = session.getCallerId();
        TrunkClassifier.TrunkProvenance trunk = trunkClassifier.classify(cli);
        Optional<DirectoryRecord> cliMatch = directoryService.findByCli(cli);

        LinguisticFamily linguistic = featureFrame != null ? featureFrame.linguistic() : null;
        String claimedIdentity = linguistic != null ? linguistic.claimedIdentity() : null;
        String claimedRole = linguistic != null ? linguistic.claimedRole() : null;

        Optional<DirectoryRecord> claimRecord =
                directoryService.findByClaimedIdentity(claimedIdentity, claimedRole);

        boolean hasClaimedRole = claimedRole != null && !claimedRole.isBlank();
        boolean cliVsClaimMismatch = false;
        List<ReasonCode> criticalReasons = new ArrayList<>();

        // CORE CHECK (Context §12): claimed role vs CLI directory identity.
        if (hasClaimedRole) {
            if (cliMatch.isEmpty()) {
                cliVsClaimMismatch = true;
            } else if (!DirectoryService.rolesMatch(claimedRole, cliMatch.get().getRole())) {
                cliVsClaimMismatch = true;
            }
            if (trunk == TrunkClassifier.TrunkProvenance.UNREGISTERED_SIP) {
                cliVsClaimMismatch = true;
            }
        }
        if (cliVsClaimMismatch) {
            criticalReasons.add(ReasonCode.CLI_CLAIM_MISMATCH);
        }

        IdentityAssessment.VoicePassport voicePassport = resolveVoicePassport(
                claimRecord.orElse(null),
                featureFrame,
                session.getChannelProfile()
        );
        if (voicePassport.verdict() == IdentityVerdict.IMPERSONATION_HUMAN
                || voicePassport.verdict() == IdentityVerdict.IMPERSONATION_SYNTHETIC) {
            criticalReasons.add(ReasonCode.VOICEPRINT_FAIL);
        }

        IdentityAssessment.PresenceConflict presenceConflict = resolvePresence(
                claimRecord.orElse(cliMatch.orElse(null)),
                trunk
        );
        if (presenceConflict != null) {
            criticalReasons.add(ReasonCode.PRESENCE_CONFLICT);
        }

        double identityRiskScore = score(
                cliVsClaimMismatch,
                voicePassport,
                presenceConflict,
                trunk,
                hasClaimedRole
        );

        Double authorityLimit = claimRecord
                .map(DirectoryRecord::getVerbalAuthorityLimitInr)
                .or(() -> cliMatch.map(DirectoryRecord::getVerbalAuthorityLimitInr))
                .orElse(null);

        return new IdentityAssessment(
                cli,
                trunk.name(),
                cliMatch.map(this::directorySummary).orElse(null),
                claimedIdentity,
                claimedRole,
                claimRecord.map(this::directorySummary).orElse(null),
                cliVsClaimMismatch,
                voicePassport,
                presenceConflict,
                clamp01(identityRiskScore),
                authorityLimit,
                List.copyOf(criticalReasons)
        );
    }

    /**
     * Context §12 cosine × spoof matrix. Exposed for the verdict table tests.
     *
     * <ul>
     *   <li>cosine low + spoof low → {@link IdentityVerdict#IMPERSONATION_HUMAN}</li>
     *   <li>cosine low + spoof high → {@link IdentityVerdict#IMPERSONATION_SYNTHETIC}</li>
     *   <li>cosine high + spoof high → {@link IdentityVerdict#IMPERSONATION_SYNTHETIC}</li>
     *   <li>cosine high + spoof low → {@link IdentityVerdict#VERIFIED}</li>
     *   <li>insufficient data / channel mismatch → {@link IdentityVerdict#INCONCLUSIVE}</li>
     * </ul>
     */
    public IdentityVerdict verdictOf(
            Double cosine,
            Double spoofProbability,
            boolean enrolled,
            boolean channelMismatch
    ) {
        if (!enrolled || channelMismatch || cosine == null || spoofProbability == null) {
            return IdentityVerdict.INCONCLUSIVE;
        }
        boolean cosineHigh = cosine >= identityProps.cosineMatchMin();
        boolean cosineLow = cosine < identityProps.cosineMismatchMax();
        if (!cosineHigh && !cosineLow) {
            // Mid band 0.50–0.70
            return IdentityVerdict.INCONCLUSIVE;
        }
        boolean spoofHigh = spoofProbability >= identityProps.spoofHighThreshold();
        if (cosineHigh && !spoofHigh) {
            return IdentityVerdict.VERIFIED;
        }
        if (cosineHigh && spoofHigh) {
            return IdentityVerdict.IMPERSONATION_SYNTHETIC;
        }
        if (cosineLow && spoofHigh) {
            return IdentityVerdict.IMPERSONATION_SYNTHETIC;
        }
        // cosine low + spoof low
        return IdentityVerdict.IMPERSONATION_HUMAN;
    }

    private IdentityAssessment.VoicePassport resolveVoicePassport(
            DirectoryRecord claimed,
            FeatureFrame frame,
            ChannelProfile channelProfile
    ) {
        if (claimed == null || !claimed.isPassportEnrolled()) {
            return new IdentityAssessment.VoicePassport(false, null, IdentityVerdict.INCONCLUSIVE);
        }
        if (frame == null || frame.speaker() == null || !frame.speaker().available()
                || frame.speaker().cosineSimilarity() == null) {
            return new IdentityAssessment.VoicePassport(true, null, IdentityVerdict.INCONCLUSIVE);
        }
        Double cosine = frame.speaker().cosineSimilarity();
        Double spoof = frame.voice() != null && frame.voice().available()
                ? frame.voice().spoofProbability()
                : null;
        // Channel-mismatch: enrolled passport assumed wideband; PSTN narrowband is undecidable (§12).
        boolean channelMismatch = channelProfile == ChannelProfile.PSTN_NARROWBAND;
        IdentityVerdict verdict = verdictOf(cosine, spoof, true, channelMismatch);
        return new IdentityAssessment.VoicePassport(true, cosine, verdict);
    }

    private IdentityAssessment.PresenceConflict resolvePresence(
            DirectoryRecord record,
            TrunkClassifier.TrunkProvenance trunk
    ) {
        if (record == null || record.getCalendarLocation() == null || record.getCalendarLocation().isBlank()) {
            return null;
        }
        // External / unregistered trunks while calendar places the person in a fixed office/region.
        if (trunk == TrunkClassifier.TrunkProvenance.UNREGISTERED_SIP
                || trunk == TrunkClassifier.TrunkProvenance.WITHHELD) {
            String expected = record.getCalendarLocation().contains("calendar")
                    ? record.getCalendarLocation()
                    : record.getCalendarLocation() + " (calendar)";
            return new IdentityAssessment.PresenceConflict(
                    expected,
                    trunkClassifier.observedRegion(trunk)
            );
        }
        return null;
    }

    private double score(
            boolean cliVsClaimMismatch,
            IdentityAssessment.VoicePassport passport,
            IdentityAssessment.PresenceConflict presenceConflict,
            TrunkClassifier.TrunkProvenance trunk,
            boolean hasClaimedRole
    ) {
        double score = 0.0;
        if (cliVsClaimMismatch) {
            score = Math.max(score, 0.95);
        }
        if (passport.verdict() == IdentityVerdict.IMPERSONATION_SYNTHETIC) {
            score = Math.max(score, 0.92);
        } else if (passport.verdict() == IdentityVerdict.IMPERSONATION_HUMAN) {
            score = Math.max(score, 0.85);
        } else if (passport.verdict() == IdentityVerdict.INCONCLUSIVE && passport.enrolled()) {
            score = Math.max(score, 0.45);
        } else if (passport.verdict() == IdentityVerdict.VERIFIED) {
            score = Math.max(score, 0.05);
        }
        if (presenceConflict != null) {
            score = Math.max(score, 0.75);
        }
        if (hasClaimedRole && trunk == TrunkClassifier.TrunkProvenance.UNREGISTERED_SIP) {
            score = Math.max(score, 0.90);
        }
        return score;
    }

    private Map<String, Object> directorySummary(DirectoryRecord record) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("employeeId", record.getEmployeeId());
        map.put("role", record.getRole());
        map.put("name", record.getName());
        map.put("hierarchyLevel", record.getHierarchyLevel());
        map.put("verbalAuthorityLimitInr", record.getVerbalAuthorityLimitInr());
        map.put("calendarLocation", record.getCalendarLocation());
        map.put("passportEnrolled", record.isPassportEnrolled());
        return map;
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}
