package com.sentinelvoice.fusion;

import com.sentinelvoice.config.SentinelProperties;
import com.sentinelvoice.model.Ask;
import com.sentinelvoice.model.FeatureFrame;
import com.sentinelvoice.model.LinguisticFamily;
import com.sentinelvoice.context.model.RelationshipAssessment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds TelemetryFrame.topReasons from a FeatureFrame plus Decision-plane context assessments
 * (Context §8.2). Messages are quantified plain English; NO_BREATH is gated in this class.
 */
@Component
public class ReasonGenerator {

    /** Context §8.1 / P5.3 — breath absence is meaningless before this much speech. */
    static final long NO_BREATH_MIN_SPEECH_MS = 15_000L;
    /** Context §8.1 — human conversational breath rate. */
    private static final double BREATH_HUMAN_MIN_PER_MIN = 8.0;
    private static final double BREATH_HUMAN_MAX_PER_MIN = 20.0;
    private static final double BREATH_ABSENT_MAX_PER_MIN = 2.0;
    /** Context §8.1 — jitter below this % is over-smooth / synthetic. */
    private static final double JITTER_OVERSMOOTH_MAX_PCT = 0.2;
    private static final double SPOOF_SYNTHETIC_MIN = 0.60;
    private static final double DOUBLE_COMPRESSION_MIN = 0.55;
    private static final double VOICE_DRIFT_MIN = 0.15;
    private static final double URGENCY_FLAG_MIN = 0.70;
    private static final double RIR_PLAUSIBLE_MIN_T60_MS = 30.0;
    private static final int HIERARCHY_ANOMALY_MIN_DISTANCE = 3;
    private static final int TOP_N = 5;
    private static final double MATCH_COSINE_FLOOR = 0.70;

    private final SentinelProperties.Emergency emergency;

    public ReasonGenerator(SentinelProperties properties) {
        this.emergency = properties.fusion().emergency();
    }

    /**
     * Context assessments that live outside the FeatureFrame (identity, graph, challenge, etc.).
     */
    public record Assessments(
            RelationshipAssessment relationship,
            boolean presenceConflict,
            String presenceExpected,
            String presenceObserved,
            boolean crossChannelPrecursor,
            int crossChannelSignalCount,
            boolean challengeLatencyFailed,
            long challengeLatencyMs,
            long challengeBudgetMs
    ) {
        public static Assessments empty() {
            return new Assessments(null, false, null, null, false, 0, false, 0L, 0L);
        }
    }

    /**
     * One emitted reason for the analyst UI / TelemetryFrame.topReasons.
     */
    public record GeneratedReason(
            ReasonCode code,
            String text,
            EvidenceFamily family,
            double contribution
    ) {
        public ReasonCode.Severity severity() {
            return code.severity();
        }
    }

    public List<GeneratedReason> generate(FusionContext context) {
        return generate(context, Map.of(), Assessments.empty());
    }

    public List<GeneratedReason> generate(
            FusionContext context,
            Map<EvidenceFamily, FamilyScore> familyScores,
            Assessments assessments
    ) {
        FeatureFrame frame = context.frame();
        List<Candidate> candidates = new ArrayList<>();

        evaluateIdentity(context, assessments, candidates);
        evaluateVoice(frame, candidates);
        evaluateChannel(frame, candidates);
        evaluateProsody(frame, candidates);
        evaluateLinguistic(context, candidates);
        evaluateTransactionPolicy(context, candidates);
        evaluateRelationship(assessments, candidates);
        evaluateChallenge(assessments, candidates);
        evaluateWatermark(frame, candidates);

        Map<EvidenceFamily, Double> contributionByFamily = contributionIndex(familyScores);

        return candidates.stream()
                .map(c -> {
                    double contrib = contributionByFamily.getOrDefault(c.code.family(), c.fallbackContribution);
                    return new GeneratedReason(c.code, c.text, c.code.family(), contrib);
                })
                .sorted(Comparator
                        .comparingInt((GeneratedReason r) -> r.severity().rank())
                        .thenComparing(Comparator.comparingDouble(GeneratedReason::contribution).reversed()))
                .limit(TOP_N)
                .toList();
    }

    private void evaluateIdentity(
            FusionContext context,
            Assessments assessments,
            List<Candidate> out
    ) {
        FeatureFrame frame = context.frame();
        if (context.cliVsClaimMismatch()) {
            String role = claimedRole(frame);
            out.add(new Candidate(
                    ReasonCode.CLI_CLAIM_MISMATCH,
                    ReasonCode.CLI_CLAIM_MISMATCH.format(role),
                    1.0
            ));
        }

        if (assessments.presenceConflict()) {
            String expected = nullToDash(assessments.presenceExpected());
            String observed = nullToDash(assessments.presenceObserved());
            out.add(new Candidate(
                    ReasonCode.PRESENCE_CONFLICT,
                    ReasonCode.PRESENCE_CONFLICT.format(expected, observed),
                    0.9
            ));
        }

        if (assessments.crossChannelPrecursor() && assessments.crossChannelSignalCount() > 0) {
            out.add(new Candidate(
                    ReasonCode.CROSS_CHANNEL_PRECURSOR,
                    ReasonCode.CROSS_CHANNEL_PRECURSOR.format(assessments.crossChannelSignalCount()),
                    0.85
            ));
        }
    }

    private void evaluateVoice(FeatureFrame frame, List<Candidate> out) {
        FeatureFrame.VoiceFamily voice = frame.voice();
        if (voice != null && voice.available() && voice.spoofProbability() != null
                && voice.spoofProbability() >= SPOOF_SYNTHETIC_MIN) {
            int spoofPct = pct(voice.spoofProbability());
            int confPct = pct(voice.confidence() == null ? 0.0 : voice.confidence());
            out.add(new Candidate(
                    ReasonCode.SYNTHETIC_ARTIFACTS,
                    ReasonCode.SYNTHETIC_ARTIFACTS.format(spoofPct, confPct),
                    voice.spoofProbability()
            ));
        }

        FeatureFrame.SpeakerFamily speaker = frame.speaker();
        if (speaker != null
                && speaker.available()
                && speaker.enrolledProfileId() != null
                && !speaker.enrolledProfileId().isBlank()
                && speaker.cosineSimilarity() != null
                && speaker.cosineSimilarity() < emergency.cosineMismatchThreshold()) {
            out.add(new Candidate(
                    ReasonCode.VOICEPRINT_FAIL,
                    ReasonCode.VOICEPRINT_FAIL.format(
                            fmt(speaker.cosineSimilarity()),
                            fmt(MATCH_COSINE_FLOOR)
                    ),
                    1.0 - speaker.cosineSimilarity()
            ));
        }

        if (speaker != null
                && speaker.available()
                && speaker.intraCallDrift() != null
                && speaker.intraCallDrift() >= VOICE_DRIFT_MIN) {
            out.add(new Candidate(
                    ReasonCode.VOICE_DRIFT,
                    ReasonCode.VOICE_DRIFT.format(fmt(speaker.intraCallDrift()), fmt(VOICE_DRIFT_MIN)),
                    speaker.intraCallDrift()
            ));
        }
    }

    private void evaluateChannel(FeatureFrame frame, List<Candidate> out) {
        FeatureFrame.ChannelFamily channel = frame.channel();
        if (channel == null || !channel.available()) {
            return;
        }
        if (Boolean.FALSE.equals(channel.rirPlausible()) && channel.rirT60Ms() != null) {
            out.add(new Candidate(
                    ReasonCode.NO_ROOM_ACOUSTICS,
                    ReasonCode.NO_ROOM_ACOUSTICS.format(
                            fmt(channel.rirT60Ms()),
                            fmt(RIR_PLAUSIBLE_MIN_T60_MS)
                    ),
                    1.0
            ));
        }
        if (channel.doubleCompressionScore() != null
                && channel.doubleCompressionScore() >= DOUBLE_COMPRESSION_MIN) {
            out.add(new Candidate(
                    ReasonCode.DOUBLE_COMPRESSION,
                    ReasonCode.DOUBLE_COMPRESSION.format(
                            fmt(channel.doubleCompressionScore()),
                            fmt(DOUBLE_COMPRESSION_MIN)
                    ),
                    channel.doubleCompressionScore()
            ));
        }
    }

    private void evaluateProsody(FeatureFrame frame, List<Candidate> out) {
        FeatureFrame.ProsodyFamily prosody = frame.prosody();
        if (prosody == null || !prosody.available()) {
            return;
        }

        // Guard lives here — UI must not decide when breath absence is meaningful.
        if (frame.cumulativeSpeechMs() >= NO_BREATH_MIN_SPEECH_MS
                && prosody.breathEventsPerMin() != null
                && prosody.breathEventsPerMin() <= BREATH_ABSENT_MAX_PER_MIN) {
            long seconds = Math.round(frame.cumulativeSpeechMs() / 1000.0);
            out.add(new Candidate(
                    ReasonCode.NO_BREATH,
                    ReasonCode.NO_BREATH.format(
                            seconds,
                            (int) BREATH_HUMAN_MIN_PER_MIN,
                            (int) BREATH_HUMAN_MAX_PER_MIN
                    ),
                    1.0 - (prosody.breathEventsPerMin() / BREATH_HUMAN_MAX_PER_MIN)
            ));
        }

        if (prosody.jitterLocalPct() != null
                && prosody.jitterLocalPct() < JITTER_OVERSMOOTH_MAX_PCT) {
            out.add(new Candidate(
                    ReasonCode.OVERSMOOTH_PROSODY,
                    ReasonCode.OVERSMOOTH_PROSODY.format(fmt(prosody.jitterLocalPct())),
                    1.0 - prosody.jitterLocalPct()
            ));
        }
    }

    private void evaluateLinguistic(FusionContext context, List<Candidate> out) {
        LinguisticFamily linguistic = context.frame().linguistic();
        if (linguistic == null || !linguistic.available()) {
            return;
        }
        if (linguistic.secrecy() != null && linguistic.secrecy() > emergency.secrecyThreshold()) {
            out.add(new Candidate(
                    ReasonCode.SECRECY_DEMAND,
                    ReasonCode.SECRECY_DEMAND.format(
                            fmt(linguistic.secrecy()),
                            fmt(emergency.secrecyThreshold())
                    ),
                    linguistic.secrecy()
            ));
        }
        if (linguistic.urgency() != null && linguistic.urgency() >= URGENCY_FLAG_MIN) {
            out.add(new Candidate(
                    ReasonCode.URGENCY_PRESSURE,
                    ReasonCode.URGENCY_PRESSURE.format(
                            fmt(linguistic.urgency()),
                            fmt(URGENCY_FLAG_MIN)
                    ),
                    linguistic.urgency()
            ));
        }
        if (linguistic.authorityInvocation() != null
                && linguistic.authorityInvocation() > emergency.authorityThreshold()) {
            out.add(new Candidate(
                    ReasonCode.AUTHORITY_INVOCATION,
                    ReasonCode.AUTHORITY_INVOCATION.format(
                            fmt(linguistic.authorityInvocation()),
                            fmt(emergency.authorityThreshold())
                    ),
                    linguistic.authorityInvocation()
            ));
        }
    }

    private void evaluateTransactionPolicy(FusionContext context, List<Candidate> out) {
        LinguisticFamily linguistic = context.frame().linguistic();
        if (linguistic == null || linguistic.ask() == null || linguistic.ask().amount() == null) {
            return;
        }
        Ask ask = linguistic.ask();
        double amount = ask.amount();
        if (amount > context.verbalAuthorityLimit()) {
            String currency = ask.currency() == null || ask.currency().isBlank() ? "INR" : ask.currency();
            out.add(new Candidate(
                    ReasonCode.POLICY_VIOLATION,
                    ReasonCode.POLICY_VIOLATION.format(
                            formatMoney(amount),
                            currency,
                            formatMoney(context.verbalAuthorityLimit())
                    ),
                    1.0
            ));
        }
    }

    private void evaluateRelationship(Assessments assessments, List<Candidate> out) {
        RelationshipAssessment rel = assessments.relationship();
        if (rel == null) {
            return;
        }
        if (rel.firstContact()) {
            out.add(new Candidate(
                    ReasonCode.FIRST_CONTACT,
                    ReasonCode.FIRST_CONTACT.format(rel.interactionCount365d()),
                    0.7
            ));
        }
        if (rel.hierarchyDistance() >= HIERARCHY_ANOMALY_MIN_DISTANCE
                && rel.hierarchyDistance() != Integer.MAX_VALUE) {
            out.add(new Candidate(
                    ReasonCode.HIERARCHY_ANOMALY,
                    ReasonCode.HIERARCHY_ANOMALY.format(
                            rel.hierarchyDistance(),
                            HIERARCHY_ANOMALY_MIN_DISTANCE
                    ),
                    Math.min(1.0, rel.hierarchyDistance() / 10.0)
            ));
        }
    }

    private void evaluateChallenge(Assessments assessments, List<Candidate> out) {
        if (!assessments.challengeLatencyFailed()) {
            return;
        }
        out.add(new Candidate(
                ReasonCode.CHALLENGE_LATENCY_FAIL,
                ReasonCode.CHALLENGE_LATENCY_FAIL.format(
                        assessments.challengeLatencyMs(),
                        assessments.challengeBudgetMs()
                ),
                1.0
        ));
    }

    private void evaluateWatermark(FeatureFrame frame, List<Candidate> out) {
        FeatureFrame.WatermarkFamily watermark = frame.watermark();
        if (watermark == null) {
            return;
        }
        boolean available = watermark.available() == null || Boolean.TRUE.equals(watermark.available());
        if (available && Boolean.TRUE.equals(watermark.detected())) {
            String provider = watermark.provider() == null || watermark.provider().isBlank()
                    ? "unknown"
                    : watermark.provider();
            int confPct = pct(watermark.confidence() == null ? 0.0 : watermark.confidence());
            out.add(new Candidate(
                    ReasonCode.WATERMARK_DETECTED,
                    ReasonCode.WATERMARK_DETECTED.format(provider, confPct),
                    watermark.confidence() == null ? 0.5 : watermark.confidence()
            ));
        }
    }

    private static Map<EvidenceFamily, Double> contributionIndex(
            Map<EvidenceFamily, FamilyScore> familyScores
    ) {
        Map<EvidenceFamily, Double> map = new EnumMap<>(EvidenceFamily.class);
        if (familyScores == null) {
            return map;
        }
        for (Map.Entry<EvidenceFamily, FamilyScore> e : familyScores.entrySet()) {
            FamilyScore fs = e.getValue();
            if (fs != null && fs.available()) {
                map.put(e.getKey(), fs.contribution());
            }
        }
        return map;
    }

    private static String claimedRole(FeatureFrame frame) {
        if (frame.linguistic() == null
                || frame.linguistic().claimedRole() == null
                || frame.linguistic().claimedRole().isBlank()) {
            return "an executive role";
        }
        return frame.linguistic().claimedRole();
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private static int pct(double unitInterval) {
        return (int) Math.round(unitInterval * 100.0);
    }

    private static String fmt(double value) {
        if (Math.rint(value) == value && Math.abs(value) < 1_000_000) {
            return String.format(Locale.ROOT, "%.0f", value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String formatMoney(double amount) {
        if (Double.isInfinite(amount) || amount >= 1.0E15) {
            return "unlimited";
        }
        if (Math.rint(amount) == amount) {
            return String.format(Locale.ROOT, "%,.0f", amount);
        }
        return String.format(Locale.ROOT, "%,.2f", amount);
    }

    private record Candidate(ReasonCode code, String text, double fallbackContribution) {
    }
}
