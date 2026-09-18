package com.sentinelvoice.telemetry;

import com.sentinelvoice.fusion.EvidenceFamily;
import com.sentinelvoice.fusion.FamilyScore;
import com.sentinelvoice.fusion.FusionResult;
import com.sentinelvoice.fusion.ReasonGenerator;
import com.sentinelvoice.identity.model.IdentityAssessment;
import com.sentinelvoice.intervention.InterventionDecision;
import com.sentinelvoice.model.CallSession;
import com.sentinelvoice.model.FeatureFrame;
import com.sentinelvoice.model.InterventionLevel;
import com.sentinelvoice.model.TelemetryFrame;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Assembles a {@link TelemetryFrame} from session + FeatureFrame + fusion + FSM + reasons + identity.
 */
@Component
public class TelemetryFrameBuilder {

    public TelemetryFrame build(
            CallSession session,
            FeatureFrame frame,
            FusionResult fusion,
            InterventionDecision decision,
            InterventionLevel previousLevel,
            long nowMs,
            IdentityAssessment identity,
            List<ReasonGenerator.GeneratedReason> reasons
    ) {
        long callElapsedMs = Math.max(0L, nowMs - session.getCreatedAt().toEpochMilli());
        long changedAtMs = decision.changed() ? nowMs : session.getLevelChangedAtMs();

        TelemetryFrame.Risk risk = new TelemetryFrame.Risk(
                clamp01(fusion.instantaneous()),
                clamp01(fusion.smoothed()),
                fusion.trend().name(),
                fusion.state().name()
        );
        TelemetryFrame.Intervention intervention = new TelemetryFrame.Intervention(
                decision.level().name(),
                previousLevel.name(),
                Math.max(0L, changedAtMs),
                Math.max(0L, decision.dwellRemainingMs()),
                null,
                decision.actionsToFire() == null ? List.of() : decision.actionsToFire()
        );

        String auditHash = auditHash(
                session.getSessionId(),
                frame.seq(),
                nowMs,
                fusion.smoothed(),
                decision.level()
        );

        return new TelemetryFrame(
                TelemetryFrame.SCHEMA,
                session.getSessionId(),
                frame.seq(),
                nowMs,
                callElapsedMs,
                risk,
                mapFamilies(fusion.families()),
                mapCorroboration(fusion.corroboration()),
                intervention,
                mapIdentity(session, frame, identity),
                mapReasons(reasons),
                new TelemetryFrame.TranscriptDelta(callElapsedMs, "", List.of()),
                auditHash
        );
    }

    /**
     * Demo-safe failure frame: risk.state=DEGRADED plus a CRITICAL error reason.
     */
    public TelemetryFrame buildDegraded(
            CallSession session,
            FeatureFrame frame,
            long nowMs,
            String errorMessage
    ) {
        long callElapsedMs = Math.max(0L, nowMs - session.getCreatedAt().toEpochMilli());
        String msg = errorMessage == null || errorMessage.isBlank()
                ? "Pipeline error"
                : errorMessage;
        // Quantified sentence for contract consistency with ReasonGenerator style.
        String text = "Decision pipeline failed for frame seq " + frame.seq()
                + " (" + msg.replaceAll("[\\r\\n]+", " ") + ").";
        TelemetryFrame.FamilyScore unavailable = new TelemetryFrame.FamilyScore(0.0, 0.0, 0.0, false);
        return new TelemetryFrame(
                TelemetryFrame.SCHEMA,
                session.getSessionId(),
                frame.seq(),
                nowMs,
                callElapsedMs,
                new TelemetryFrame.Risk(0.0, clamp01(session.getSmoothedRisk()), "STABLE", "DEGRADED"),
                new TelemetryFrame.Families(
                        unavailable, unavailable, unavailable, unavailable, unavailable, unavailable
                ),
                new TelemetryFrame.Corroboration(List.of(), 2, false),
                new TelemetryFrame.Intervention(
                        session.getCurrentLevel().name(),
                        session.getCurrentLevel().name(),
                        session.getLevelChangedAtMs(),
                        0L,
                        null,
                        List.of()
                ),
                mapIdentity(session, frame, null),
                List.of(new TelemetryFrame.Reason("PIPELINE_ERROR", "CRITICAL", text)),
                new TelemetryFrame.TranscriptDelta(callElapsedMs, "", List.of()),
                auditHash(session.getSessionId(), frame.seq(), nowMs, session.getSmoothedRisk(),
                        session.getCurrentLevel())
        );
    }

    private static TelemetryFrame.Identity mapIdentity(
            CallSession session,
            FeatureFrame frame,
            IdentityAssessment identity
    ) {
        if (identity == null) {
            String cli = session.getCallerId() != null ? session.getCallerId() : "unknown";
            String claimedIdentity = frame.linguistic() != null ? frame.linguistic().claimedIdentity() : null;
            String claimedRole = frame.linguistic() != null ? frame.linguistic().claimedRole() : null;
            return new TelemetryFrame.Identity(
                    cli,
                    "UNKNOWN",
                    null,
                    claimedIdentity,
                    claimedRole,
                    null,
                    false,
                    new TelemetryFrame.VoicePassport(false, 0.0, "INCONCLUSIVE"),
                    null
            );
        }
        TelemetryFrame.DirectoryRecord claimDir = null;
        if (identity.directoryRecordForClaim() != null
                && identity.directoryRecordForClaim().get("employeeId") != null) {
            claimDir = new TelemetryFrame.DirectoryRecord(
                    String.valueOf(identity.directoryRecordForClaim().get("employeeId")),
                    String.valueOf(identity.directoryRecordForClaim().getOrDefault("role", ""))
            );
        }
        TelemetryFrame.VoicePassport passport = new TelemetryFrame.VoicePassport(
                identity.voicePassport() != null && identity.voicePassport().enrolled(),
                identity.voicePassport() != null && identity.voicePassport().cosine() != null
                        ? identity.voicePassport().cosine()
                        : 0.0,
                identity.voicePassport() != null && identity.voicePassport().verdict() != null
                        ? identity.voicePassport().verdict().name()
                        : "INCONCLUSIVE"
        );
        TelemetryFrame.PresenceConflict presence = null;
        if (identity.presenceConflict() != null) {
            presence = new TelemetryFrame.PresenceConflict(
                    identity.presenceConflict().expected(),
                    identity.presenceConflict().observed()
            );
        }
        return new TelemetryFrame.Identity(
                identity.cli(),
                identity.cliTrunk() != null ? identity.cliTrunk() : "UNKNOWN",
                identity.directoryMatch(),
                identity.claimedIdentity(),
                identity.claimedRole(),
                claimDir,
                identity.cliVsClaimMismatch(),
                passport,
                presence
        );
    }

    private static List<TelemetryFrame.Reason> mapReasons(List<ReasonGenerator.GeneratedReason> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return List.of();
        }
        List<TelemetryFrame.Reason> out = new ArrayList<>(reasons.size());
        for (ReasonGenerator.GeneratedReason r : reasons) {
            out.add(new TelemetryFrame.Reason(
                    r.code().name(),
                    r.severity().name(),
                    r.text()
            ));
        }
        return out;
    }

    private static TelemetryFrame.Families mapFamilies(Map<EvidenceFamily, FamilyScore> scores) {
        return new TelemetryFrame.Families(
                familyOrUnavailable(scores == null ? null : scores.get(EvidenceFamily.VOICE)),
                familyOrUnavailable(scores == null ? null : scores.get(EvidenceFamily.CHANNEL)),
                familyOrUnavailable(scores == null ? null : scores.get(EvidenceFamily.PROSODY)),
                familyOrUnavailable(scores == null ? null : scores.get(EvidenceFamily.LINGUISTIC)),
                familyOrUnavailable(scores == null ? null : scores.get(EvidenceFamily.TRANSACTION)),
                familyOrUnavailable(scores == null ? null : scores.get(EvidenceFamily.RELATIONSHIP))
        );
    }

    private static TelemetryFrame.FamilyScore familyOrUnavailable(FamilyScore score) {
        if (score == null) {
            return new TelemetryFrame.FamilyScore(0.0, 0.0, 0.0, false);
        }
        return new TelemetryFrame.FamilyScore(
                clamp01(score.score()),
                clamp01(score.weight()),
                clamp01(score.contribution()),
                score.available()
        );
    }

    private static TelemetryFrame.Corroboration mapCorroboration(FusionResult.CorroborationDetail detail) {
        if (detail == null) {
            return new TelemetryFrame.Corroboration(List.of(), 0, false);
        }
        List<String> above = new ArrayList<>();
        if (detail.familiesAboveThreshold() != null) {
            for (EvidenceFamily family : detail.familiesAboveThreshold()) {
                above.add(family.configKey());
            }
        }
        return new TelemetryFrame.Corroboration(
                above,
                Math.max(0, detail.independentFamiliesRequired()),
                detail.satisfied()
        );
    }

    private static String auditHash(
            String sessionId,
            int seq,
            long tsEpochMs,
            double smoothed,
            InterventionLevel level
    ) {
        String material = sessionId + "|" + seq + "|" + tsEpochMs + "|"
                + String.format(Locale.ROOT, "%.6f", smoothed) + "|" + level.name();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}
