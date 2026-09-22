package com.sentinelvoice.fusion;

import com.sentinelvoice.fusion.config.FusionConfigDocument;
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
 * Builds typed explainability reasons (F12). Text comes from {@link ReasonTemplates} bundles.
 */
@Component
public class ReasonGenerator {

    static final long NO_BREATH_MIN_SPEECH_MS = 15_000L;
    private static final double BREATH_HUMAN_MIN_PER_MIN = 8.0;
    private static final double BREATH_HUMAN_MAX_PER_MIN = 20.0;
    private static final double BREATH_ABSENT_MAX_PER_MIN = 2.0;
    private static final double JITTER_OVERSMOOTH_MAX_PCT = 0.2;
    private static final double SPOOF_SYNTHETIC_MIN = 0.60;
    private static final double DOUBLE_COMPRESSION_MIN = 0.55;
    private static final double VOICE_DRIFT_MIN = 0.15;
    private static final double URGENCY_FLAG_MIN = 0.70;
    private static final double RIR_PLAUSIBLE_MIN_T60_MS = 30.0;
    private static final int HIERARCHY_ANOMALY_MIN_DISTANCE = 3;
    private static final int TOP_N = 8;
    private static final double MATCH_COSINE_FLOOR = 0.70;

    private final ReasonTemplates templates;

    public ReasonGenerator(ReasonTemplates templates) {
        this.templates = templates;
    }

    public record Assessments(
            RelationshipAssessment relationship,
            boolean presenceConflict,
            String presenceExpected,
            String presenceObserved,
            boolean crossChannelPrecursor,
            int crossChannelSignalCount,
            boolean challengeLatencyFailed,
            long challengeLatencyMs,
            long challengeBudgetMs,
            String challengeFailCode,
            double challengeMetric
    ) {
        public Assessments(
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
            this(
                    relationship, presenceConflict, presenceExpected, presenceObserved,
                    crossChannelPrecursor, crossChannelSignalCount,
                    challengeLatencyFailed, challengeLatencyMs, challengeBudgetMs,
                    null, 0.0
            );
        }

        public static Assessments empty() {
            return new Assessments(null, false, null, null, false, 0, false, 0L, 0L, null, 0.0);
        }
    }

    public record GeneratedReason(
            ReasonCode code,
            String title,
            String text,
            EvidenceFamily family,
            double contribution,
            String ruleId,
            Integer policyVersion,
            Map<String, Object> sourceClause,
            Map<String, Object> evidence
    ) {
        public GeneratedReason(ReasonCode code, String title, String text, EvidenceFamily family, double contribution) {
            this(code, title, text, family, contribution, null, null, null, Map.of());
        }

        public ReasonCode.Severity severity() {
            return code.canonical().severity();
        }

        public ReasonCode canonicalCode() {
            return code.canonical();
        }
    }

    public List<GeneratedReason> generate(FusionContext context) {
        return generate(context, Map.of(), Assessments.empty(), null, Locale.ENGLISH);
    }

    public List<GeneratedReason> generate(
            FusionContext context,
            Map<EvidenceFamily, FamilyScore> familyScores,
            Assessments assessments
    ) {
        return generate(context, familyScores, assessments, null, Locale.ENGLISH);
    }

    public List<GeneratedReason> generate(
            FusionContext context,
            Map<EvidenceFamily, FamilyScore> familyScores,
            Assessments assessments,
            FusionConfigDocument config
    ) {
        return generate(context, familyScores, assessments, config, Locale.ENGLISH);
    }

    public List<GeneratedReason> generate(
            FusionContext context,
            Map<EvidenceFamily, FamilyScore> familyScores,
            Assessments assessments,
            FusionConfigDocument config,
            Locale locale
    ) {
        Locale loc = locale == null ? Locale.ENGLISH : locale;
        FeatureFrame frame = context.frame();
        List<Candidate> candidates = new ArrayList<>();
        Thresholds thresholds = Thresholds.from(config);

        evaluateIdentity(context, assessments, candidates, loc);
        evaluateVoice(frame, candidates, thresholds, loc);
        evaluateChannel(frame, candidates, loc);
        evaluateProsody(frame, candidates, loc);
        evaluateLinguistic(context, candidates, thresholds, loc);
        evaluateTransactionPolicy(context, candidates, loc);
        evaluateRelationship(assessments, candidates, loc);
        evaluateChallenge(assessments, candidates, loc);
        evaluateWatermark(frame, candidates, loc);

        Map<EvidenceFamily, Double> contributionByFamily = contributionIndex(familyScores);

        return candidates.stream()
                .map(c -> {
                    ReasonCode canon = c.code.canonical();
                    double contrib = contributionByFamily.getOrDefault(canon.family(), c.fallbackContribution);
                    return new GeneratedReason(
                            canon,
                            templates.title(canon, loc),
                            c.detail,
                            canon.family(),
                            contrib,
                            c.ruleId,
                            c.policyVersion,
                            c.sourceClause,
                            c.evidence == null ? Map.of() : c.evidence
                    );
                })
                .sorted(Comparator
                        .comparingInt((GeneratedReason r) -> r.severity().rank())
                        .thenComparing(Comparator.comparingDouble(GeneratedReason::contribution).reversed()))
                .limit(TOP_N)
                .toList();
    }

    private void evaluateIdentity(FusionContext context, Assessments assessments, List<Candidate> out, Locale loc) {
        FeatureFrame frame = context.frame();
        if (context.cliVsClaimMismatch()) {
            String role = claimedRole(frame);
            out.add(cand(ReasonCode.CLI_CLAIM_MISMATCH, templates.detail(ReasonCode.CLI_CLAIM_MISMATCH, loc, role), 1.0));
        }
        if (assessments.presenceConflict()) {
            out.add(cand(ReasonCode.PRESENCE_CONFLICT, templates.detail(
                    ReasonCode.PRESENCE_CONFLICT, loc,
                    nullToDash(assessments.presenceExpected()), nullToDash(assessments.presenceObserved())
            ), 0.9));
        }
        if (assessments.crossChannelPrecursor() && assessments.crossChannelSignalCount() > 0) {
            out.add(cand(ReasonCode.CROSS_CHANNEL_PRECURSOR, templates.detail(
                    ReasonCode.CROSS_CHANNEL_PRECURSOR, loc, assessments.crossChannelSignalCount()
            ), 0.85));
        }
    }

    private void evaluateVoice(FeatureFrame frame, List<Candidate> out, Thresholds thresholds, Locale loc) {
        FeatureFrame.VoiceFamily voice = frame.voice();
        if (voice != null && voice.available() && voice.spoofProbability() != null
                && voice.spoofProbability() >= SPOOF_SYNTHETIC_MIN) {
            out.add(cand(ReasonCode.SYNTHETIC_VOICE, templates.detail(
                    ReasonCode.SYNTHETIC_VOICE, loc,
                    pct(voice.spoofProbability()),
                    pct(voice.confidence() == null ? 0.0 : voice.confidence())
            ), voice.spoofProbability()));
        }

        FeatureFrame.SpeakerFamily speaker = frame.speaker();
        if (speaker != null
                && speaker.available()
                && speaker.enrolledProfileId() != null
                && !speaker.enrolledProfileId().isBlank()
                && speaker.cosineSimilarity() != null
                && speaker.cosineSimilarity() < thresholds.cosineMatchFloor()) {
            out.add(cand(ReasonCode.SPEAKER_MISMATCH, templates.detail(
                    ReasonCode.SPEAKER_MISMATCH, loc,
                    fmt(speaker.cosineSimilarity()), fmt(thresholds.cosineMatchFloor())
            ), 1.0 - speaker.cosineSimilarity()));
        }

        if (speaker != null && speaker.available() && speaker.intraCallDrift() != null
                && speaker.intraCallDrift() >= VOICE_DRIFT_MIN) {
            out.add(cand(ReasonCode.VOICE_DRIFT, templates.detail(
                    ReasonCode.VOICE_DRIFT, loc, fmt(speaker.intraCallDrift()), fmt(VOICE_DRIFT_MIN)
            ), speaker.intraCallDrift()));
        }
    }

    private void evaluateChannel(FeatureFrame frame, List<Candidate> out, Locale loc) {
        FeatureFrame.ChannelFamily channel = frame.channel();
        if (channel == null || !channel.available()) {
            return;
        }
        if (Boolean.FALSE.equals(channel.rirPlausible()) && channel.rirT60Ms() != null) {
            String note = "Room reverberation " + fmt(channel.rirT60Ms()) + " ms below floor "
                    + fmt(RIR_PLAUSIBLE_MIN_T60_MS) + " ms.";
            out.add(cand(ReasonCode.CHANNEL_INCONSISTENT, templates.detail(ReasonCode.CHANNEL_INCONSISTENT, loc, note), 1.0));
        }
        if (channel.doubleCompressionScore() != null
                && channel.doubleCompressionScore() >= DOUBLE_COMPRESSION_MIN) {
            String note = "Double-compression score " + fmt(channel.doubleCompressionScore())
                    + " above threshold " + fmt(DOUBLE_COMPRESSION_MIN) + ".";
            out.add(cand(ReasonCode.CHANNEL_INCONSISTENT, templates.detail(ReasonCode.CHANNEL_INCONSISTENT, loc, note),
                    channel.doubleCompressionScore()));
        }
    }

    private void evaluateProsody(FeatureFrame frame, List<Candidate> out, Locale loc) {
        FeatureFrame.ProsodyFamily prosody = frame.prosody();
        if (prosody == null || !prosody.available()) {
            return;
        }
        if (frame.cumulativeSpeechMs() >= NO_BREATH_MIN_SPEECH_MS
                && prosody.breathEventsPerMin() != null
                && prosody.breathEventsPerMin() <= BREATH_ABSENT_MAX_PER_MIN) {
            long seconds = Math.round(frame.cumulativeSpeechMs() / 1000.0);
            out.add(cand(ReasonCode.NO_BREATH, templates.detail(
                    ReasonCode.NO_BREATH, loc, seconds, (int) BREATH_HUMAN_MIN_PER_MIN, (int) BREATH_HUMAN_MAX_PER_MIN
            ), 1.0 - (prosody.breathEventsPerMin() / BREATH_HUMAN_MAX_PER_MIN)));
        }
        if (prosody.jitterLocalPct() != null && prosody.jitterLocalPct() < JITTER_OVERSMOOTH_MAX_PCT) {
            out.add(cand(ReasonCode.OVERSMOOTH_PROSODY, templates.detail(
                    ReasonCode.OVERSMOOTH_PROSODY, loc, fmt(prosody.jitterLocalPct())
            ), 1.0 - prosody.jitterLocalPct()));
        }
    }

    private void evaluateLinguistic(FusionContext context, List<Candidate> out, Thresholds thresholds, Locale loc) {
        LinguisticFamily linguistic = context.frame().linguistic();
        if (linguistic == null || !linguistic.available()) {
            return;
        }
        if (linguistic.secrecy() != null && linguistic.secrecy() > thresholds.secrecy()) {
            out.add(cand(ReasonCode.SECRECY_REQUESTED, templates.detail(
                    ReasonCode.SECRECY_REQUESTED, loc, fmt(linguistic.secrecy()), fmt(thresholds.secrecy())
            ), linguistic.secrecy()));
        }
        if (linguistic.urgency() != null && linguistic.urgency() >= URGENCY_FLAG_MIN) {
            out.add(cand(ReasonCode.URGENCY, templates.detail(
                    ReasonCode.URGENCY, loc, fmt(linguistic.urgency()), fmt(URGENCY_FLAG_MIN)
            ), linguistic.urgency()));
        }
        if (linguistic.authorityInvocation() != null && linguistic.authorityInvocation() > thresholds.authority()) {
            out.add(cand(ReasonCode.AUTHORITY_INVOCATION, templates.detail(
                    ReasonCode.AUTHORITY_INVOCATION, loc,
                    fmt(linguistic.authorityInvocation()), fmt(thresholds.authority())
            ), linguistic.authorityInvocation()));
        }
    }

    private void evaluateTransactionPolicy(FusionContext context, List<Candidate> out, Locale loc) {
        LinguisticFamily linguistic = context.frame().linguistic();
        if (linguistic == null || linguistic.ask() == null || linguistic.ask().amount() == null) {
            return;
        }
        Ask ask = linguistic.ask();
        double amount = ask.amount();
        if (amount > context.verbalAuthorityLimit()) {
            String currency = ask.currency() == null || ask.currency().isBlank() ? "INR" : ask.currency();
            String quote = "Requested " + formatMoney(amount) + " " + currency
                    + " exceeds verbal authority " + formatMoney(context.verbalAuthorityLimit());
            if (quote.length() > 200) {
                quote = quote.substring(0, 197) + "...";
            }
            Map<String, Object> clause = Map.of(
                    "title", "Verbal authority limit",
                    "quote", quote
            );
            out.add(new Candidate(
                    ReasonCode.POLICY_RULE_FIRED,
                    templates.detail(ReasonCode.POLICY_RULE_FIRED, loc, "AUTHORITY_LIMIT", "Verbal authority", quote),
                    1.0,
                    "AUTHORITY_LIMIT",
                    null,
                    clause,
                    Map.of("amount", amount, "currency", currency)
            ));
        }
    }

    private void evaluateRelationship(Assessments assessments, List<Candidate> out, Locale loc) {
        RelationshipAssessment rel = assessments.relationship();
        if (rel == null) {
            return;
        }
        if (rel.firstContact()) {
            out.add(cand(ReasonCode.NO_PRIOR_RELATIONSHIP, templates.detail(
                    ReasonCode.NO_PRIOR_RELATIONSHIP, loc, rel.interactionCount365d()
            ), 0.7));
        }
        if (rel.hierarchyDistance() >= HIERARCHY_ANOMALY_MIN_DISTANCE
                && rel.hierarchyDistance() != Integer.MAX_VALUE) {
            out.add(cand(ReasonCode.HIERARCHY_ANOMALY, templates.detail(
                    ReasonCode.HIERARCHY_ANOMALY, loc, rel.hierarchyDistance(), HIERARCHY_ANOMALY_MIN_DISTANCE
            ), Math.min(1.0, rel.hierarchyDistance() / 10.0)));
        }
    }

    private void evaluateChallenge(Assessments assessments, List<Candidate> out, Locale loc) {
        String code = assessments.challengeFailCode();
        if (code == null || code.isBlank()) {
            if (!assessments.challengeLatencyFailed()) {
                return;
            }
            out.add(cand(ReasonCode.CHALLENGE_LATENCY_FAIL, templates.detail(
                    ReasonCode.CHALLENGE_LATENCY_FAIL, loc,
                    assessments.challengeLatencyMs(), assessments.challengeBudgetMs()
            ), 1.0));
            return;
        }
        try {
            ReasonCode rc = ReasonCode.valueOf(code).canonical();
            String detail = switch (rc) {
                case CHALLENGE_LATENCY_FAIL -> templates.detail(rc, loc,
                        assessments.challengeLatencyMs(), assessments.challengeBudgetMs());
                case CHALLENGE_CONTENT_FAIL -> templates.detail(rc, loc,
                        String.format(Locale.ROOT, "%.2f", assessments.challengeMetric()));
                case CHALLENGE_ACOUSTIC_FAIL -> templates.detail(rc, loc,
                        String.format(Locale.ROOT, "%.2f", assessments.challengeMetric()), "0.55");
                default -> templates.detail(rc, loc, assessments.challengeMetric());
            };
            out.add(cand(rc, detail, 1.0));
        } catch (Exception ex) {
            if (assessments.challengeLatencyFailed()) {
                out.add(cand(ReasonCode.CHALLENGE_LATENCY_FAIL, templates.detail(
                        ReasonCode.CHALLENGE_LATENCY_FAIL, loc,
                        assessments.challengeLatencyMs(), assessments.challengeBudgetMs()
                ), 1.0));
            }
        }
    }

    private void evaluateWatermark(FeatureFrame frame, List<Candidate> out, Locale loc) {
        FeatureFrame.WatermarkFamily watermark = frame.watermark();
        if (watermark == null) {
            return;
        }
        boolean available = watermark.available() == null || Boolean.TRUE.equals(watermark.available());
        if (available && Boolean.TRUE.equals(watermark.detected())) {
            String provider = watermark.provider() == null || watermark.provider().isBlank()
                    ? "unknown" : watermark.provider();
            out.add(cand(ReasonCode.WATERMARK_DETECTED, templates.detail(
                    ReasonCode.WATERMARK_DETECTED, loc, provider,
                    pct(watermark.confidence() == null ? 0.0 : watermark.confidence())
            ), watermark.confidence() == null ? 0.5 : watermark.confidence()));
        }
    }

    private static Candidate cand(ReasonCode code, String detail, double contrib) {
        return new Candidate(code, detail, contrib, null, null, null, Map.of());
    }

    private static Map<EvidenceFamily, Double> contributionIndex(Map<EvidenceFamily, FamilyScore> familyScores) {
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

    private record Thresholds(double cosineMatchFloor, double secrecy, double authority) {
        static Thresholds from(FusionConfigDocument config) {
            if (config == null || config.emergency().rules().isEmpty()) {
                return new Thresholds(MATCH_COSINE_FLOOR, 0.85, 0.85);
            }
            FusionConfigDocument.EmergencyRule rule = config.emergency().rules().get(0);
            double matchFloor = Math.max(0.0, 1.0 - rule.cosineMismatchMin());
            return new Thresholds(matchFloor, rule.secrecyMin(), rule.authorityMin());
        }
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

    private record Candidate(
            ReasonCode code,
            String detail,
            double fallbackContribution,
            String ruleId,
            Integer policyVersion,
            Map<String, Object> sourceClause,
            Map<String, Object> evidence
    ) {
    }
}
