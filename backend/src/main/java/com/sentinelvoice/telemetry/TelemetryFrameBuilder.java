package com.sentinelvoice.telemetry;

import com.sentinelvoice.fusion.EvidenceFamily;
import com.sentinelvoice.fusion.FamilyScore;
import com.sentinelvoice.fusion.FusionResult;
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
 * Assembles a {@link TelemetryFrame} from session state, the current FeatureFrame,
 * fusion output, and the intervention ladder decision.
 *
 * <p>Identity / topReasons stay minimal here — P8 / P5 deepen them. Fusion is whatever
 * {@link com.sentinelvoice.fusion.FusionEngineService} returns (P5 owns the math).
 */
@Component
public class TelemetryFrameBuilder {

    public TelemetryFrame build(
            CallSession session,
            FeatureFrame frame,
            FusionResult fusion,
            InterventionDecision decision,
            InterventionLevel previousLevel,
            long nowMs
    ) {
        long callElapsedMs = Math.max(0L, nowMs - session.getCreatedAt().toEpochMilli());
        long changedAtMs = decision.changed() ? nowMs : session.getLevelChangedAtMs();

        TelemetryFrame.Families families = mapFamilies(fusion.families());
        TelemetryFrame.Corroboration corroboration = mapCorroboration(fusion.corroboration());
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
        TelemetryFrame.Identity identity = placeholderIdentity(session, frame);
        TelemetryFrame.TranscriptDelta transcriptDelta = new TelemetryFrame.TranscriptDelta(
                callElapsedMs,
                "",
                List.of()
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
                families,
                corroboration,
                intervention,
                identity,
                List.of(),
                transcriptDelta,
                auditHash
        );
    }

    private static TelemetryFrame.Families mapFamilies(Map<EvidenceFamily, FamilyScore> scores) {
        return new TelemetryFrame.Families(
                familyOrUnavailable(scores.get(EvidenceFamily.VOICE)),
                familyOrUnavailable(scores.get(EvidenceFamily.CHANNEL)),
                familyOrUnavailable(scores.get(EvidenceFamily.PROSODY)),
                familyOrUnavailable(scores.get(EvidenceFamily.LINGUISTIC)),
                familyOrUnavailable(scores.get(EvidenceFamily.TRANSACTION)),
                familyOrUnavailable(scores.get(EvidenceFamily.RELATIONSHIP))
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

    /**
     * Placeholder identity until P8.1 IdentityResolutionService feeds real values.
     */
    private static TelemetryFrame.Identity placeholderIdentity(CallSession session, FeatureFrame frame) {
        String cli = session.getCallerId() != null ? session.getCallerId() : "unknown";
        String claimedIdentity = null;
        String claimedRole = null;
        if (frame.linguistic() != null) {
            claimedIdentity = frame.linguistic().claimedIdentity();
            claimedRole = frame.linguistic().claimedRole();
        }
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
