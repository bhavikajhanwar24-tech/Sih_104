package com.sentinelvoice.fusion;

import com.sentinelvoice.config.SentinelProperties;
import com.sentinelvoice.model.Ask;
import com.sentinelvoice.model.ChannelProfile;
import com.sentinelvoice.model.FeatureFrame;
import com.sentinelvoice.model.LinguisticFamily;
import com.sentinelvoice.context.model.RelationshipAssessment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ReasonGeneratorTest {

    private static final Pattern CONTAINS_DIGIT = Pattern.compile(".*\\d.*");

    private ReasonGenerator generator;

    @BeforeEach
    void setUp() {
        SentinelProperties properties = FusionEngineTest.testProperties();
        generator = new ReasonGenerator(properties);
    }

    @Test
    void syntheticAudioSession_producesAtLeastThreeDistinctQuantifiedReasons() {
        FeatureFrame frame = syntheticFrame();
        FusionContext ctx = new FusionContext(frame, 0.9, true, 0.8, true, true, 0.0);
        ReasonGenerator.Assessments assessments = new ReasonGenerator.Assessments(
                new RelationshipAssessment(0, true, 4, false, false, 0.87, List.of("FIRST_CONTACT")),
                true,
                "London (calendar)",
                "SIP trunk / APAC",
                true,
                3,
                false,
                0L,
                0L
        );

        List<ReasonGenerator.GeneratedReason> reasons = generator.generate(ctx, null, assessments);

        assertThat(reasons).hasSizeGreaterThanOrEqualTo(3);
        assertThat(reasons.stream().map(ReasonGenerator.GeneratedReason::code).distinct().count())
                .isGreaterThanOrEqualTo(3);
        assertThat(reasons).allSatisfy(this::assertQuantifiedSentence);
        assertThat(reasons).allSatisfy(r -> assertThat(r.family()).isNotNull());
    }

    @Test
    void genuineAudioSession_producesZeroReasonsAboveMediumSeverity() {
        FeatureFrame frame = genuineFrame();
        FusionContext ctx = FusionContext.ofFrame(frame);

        List<ReasonGenerator.GeneratedReason> reasons =
                generator.generate(ctx, null, ReasonGenerator.Assessments.empty());

        assertThat(reasons)
                .filteredOn(r -> r.severity().rank() < ReasonCode.Severity.MEDIUM.rank())
                .as("genuine audio must not raise HIGH or CRITICAL reasons")
                .isEmpty();
        assertThat(reasons).allSatisfy(this::assertQuantifiedSentence);
    }

    @Test
    void noBreath_requiresFifteenSecondsOfSpeech() {
        FeatureFrame shortSpeech = prosodyBreathFrame(14_000L, 0.0);
        FeatureFrame longSpeech = prosodyBreathFrame(24_000L, 0.0);

        List<ReasonGenerator.GeneratedReason> before =
                generator.generate(FusionContext.ofFrame(shortSpeech));
        List<ReasonGenerator.GeneratedReason> after =
                generator.generate(FusionContext.ofFrame(longSpeech));

        assertThat(before).noneMatch(r -> r.code() == ReasonCode.NO_BREATH);
        assertThat(after).anyMatch(r -> r.code() == ReasonCode.NO_BREATH);
        ReasonGenerator.GeneratedReason breath = after.stream()
                .filter(r -> r.code() == ReasonCode.NO_BREATH)
                .findFirst()
                .orElseThrow();
        assertThat(breath.text()).contains("24");
        assertQuantifiedSentence(breath);
    }

    @Test
    void reasonsSortedBySeverityThenContribution_andCappedAtFive() {
        FeatureFrame frame = syntheticFrame();
        FusionContext ctx = new FusionContext(frame, 0.95, true, 0.9, true, true, 0.0);
        ReasonGenerator.Assessments assessments = new ReasonGenerator.Assessments(
                new RelationshipAssessment(0, true, 5, false, false, 0.9, List.of("FIRST_CONTACT")),
                true,
                "HQ",
                "APAC trunk",
                true,
                4,
                true,
                4200L,
                1500L
        );

        List<ReasonGenerator.GeneratedReason> reasons = generator.generate(ctx, null, assessments);

        assertThat(reasons).hasSizeLessThanOrEqualTo(5);
        for (int i = 1; i < reasons.size(); i++) {
            assertThat(reasons.get(i).severity().rank())
                    .isGreaterThanOrEqualTo(reasons.get(i - 1).severity().rank());
        }
        assertThat(reasons.get(0).severity())
                .isIn(ReasonCode.Severity.CRITICAL, ReasonCode.Severity.HIGH);
    }

    @Test
    void everyEmittedMessage_isCompleteSentenceWithANumber() {
        FeatureFrame frame = syntheticFrame();
        FusionContext ctx = new FusionContext(frame, 0.9, true, 0.8, true, true, 0.0);
        List<ReasonGenerator.GeneratedReason> reasons = generator.generate(ctx);

        assertThat(reasons).isNotEmpty();
        assertThat(reasons).allSatisfy(this::assertQuantifiedSentence);
    }

    private void assertQuantifiedSentence(ReasonGenerator.GeneratedReason reason) {
        assertThat(reason.text())
                .as("%s text", reason.code())
                .isNotBlank()
                .matches(".*[.!?]$");
        assertThat(CONTAINS_DIGIT.matcher(reason.text()).matches())
                .as("%s must contain at least one number: %s", reason.code(), reason.text())
                .isTrue();
        assertThat(reason.family()).isEqualTo(reason.code().family());
    }

    private static FeatureFrame syntheticFrame() {
        return new FeatureFrame(
                "sentinelvoice.FeatureFrame/1",
                "synth-1",
                47,
                23_500L,
                24_000L,
                ChannelProfile.WEBRTC_WIDEBAND,
                true,
                24_000L,
                new FeatureFrame.VoiceFamily(true, 0.71, "aasist-lfcc-v3-codecaug", 0.62),
                new FeatureFrame.ChannelFamily(true, 18.0, false, 0.66, 0.91, 0.0004),
                new FeatureFrame.ProsodyFamily(
                        true, 118.4, 4.1, 0.08, 1.9, 27.8, 0.0, 0.0, 5.9, 0.74),
                new FeatureFrame.SpeakerFamily(true, "emb-1", "EMP-10492", 0.41, 0.18),
                new FeatureFrame.WatermarkFamily(false, "audioseal-v1", false, null, 0.03),
                new LinguisticFamily(
                        true,
                        900L,
                        "hi-en",
                        0.93,
                        0.88,
                        0.90,
                        0.71,
                        true,
                        new Ask("WIRE_TRANSFER", 5_000_000.0, "INR", "vendor 4471", "immediate"),
                        "Rajesh Kumar",
                        "CFO",
                        "…"
                ),
                new FeatureFrame.LatencyMs(94, 812)
        );
    }

    private static FeatureFrame genuineFrame() {
        return new FeatureFrame(
                "sentinelvoice.FeatureFrame/1",
                "genuine-1",
                10,
                0L,
                500L,
                ChannelProfile.WEBRTC_WIDEBAND,
                true,
                20_000L,
                new FeatureFrame.VoiceFamily(true, 0.12, "aasist-lfcc-v3-codecaug", 0.91),
                new FeatureFrame.ChannelFamily(true, 220.0, true, 0.18, 0.40, 0.0001),
                new FeatureFrame.ProsodyFamily(
                        true, 140.0, 18.0, 0.9, 4.2, 18.0, 12.0, 0.08, 4.5, 0.15),
                new FeatureFrame.SpeakerFamily(true, "emb-g", "EMP-1", 0.88, 0.04),
                new FeatureFrame.WatermarkFamily(true, "audioseal-v1", false, null, 0.02),
                new LinguisticFamily(
                        true, 200L, "en", 0.20, 0.10, 0.15, 0.10,
                        false, null, null, null, ""),
                new FeatureFrame.LatencyMs(40, 180)
        );
    }

    private static FeatureFrame prosodyBreathFrame(long cumulativeSpeechMs, double breathsPerMin) {
        return new FeatureFrame(
                "sentinelvoice.FeatureFrame/1",
                "breath",
                1,
                0L,
                500L,
                ChannelProfile.WEBRTC_WIDEBAND,
                true,
                cumulativeSpeechMs,
                new FeatureFrame.VoiceFamily(true, 0.20, "test", 0.9),
                new FeatureFrame.ChannelFamily(true, 200.0, true, 0.20, 0.4, 0.0),
                new FeatureFrame.ProsodyFamily(
                        true, 120.0, 10.0, 0.8, 3.0, 18.0, breathsPerMin, 0.05, 4.0, 0.2),
                new FeatureFrame.SpeakerFamily(false, null, null, null, null),
                new FeatureFrame.WatermarkFamily(false, null, null, null, null),
                new LinguisticFamily(
                        false, null, null, null, null, null, null, null, null, null, null, null),
                new FeatureFrame.LatencyMs(50, 200)
        );
    }
}
