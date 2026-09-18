package com.sentinelvoice.fusion;

import com.sentinelvoice.config.SentinelProperties;
import com.sentinelvoice.model.Ask;
import com.sentinelvoice.model.ChannelProfile;
import com.sentinelvoice.model.FeatureFrame;
import com.sentinelvoice.model.LinguisticFamily;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

public class FusionEngineTest {

    private SentinelProperties properties;
    private EmaSmoother emaSmoother;
    private FusionEngineService engine;

    @BeforeEach
    void setUp() {
        properties = testProperties();
        emaSmoother = new EmaSmoother(properties);
        engine = new FusionEngineService(
                properties,
                emaSmoother,
                new CorroborationGate(properties),
                new EmergencyTrigger(properties)
        );
    }

    @Test
    void availabilityRenormalisation_singleLinguisticFamilyYieldsItsScore() {
        // Only linguistic available at 0.9 → S(t) must be 0.9, not 0.9 * 0.25
        FeatureFrame frame = baseFrameBuilder()
                .speechPresent(true)
                .cumulativeSpeechMs(5000)
                .voiceUnavailable()
                .channelUnavailable()
                .prosodyUnavailable()
                .linguistic(0.9, 0L)
                .build();

        FusionResult result = engine.evaluate("s1", FusionContext.ofFrame(frame));

        assertThat(result.instantaneous()).isCloseTo(0.9, within(1e-9));
        assertThat(result.families().get(EvidenceFamily.LINGUISTIC).available()).isTrue();
        assertThat(result.families().get(EvidenceFamily.VOICE).available()).isFalse();
    }

    @Test
    void weightsSumToOneInBothProfiles() {
        Map<String, Double> wide = properties.fusion().weights().wideband();
        Map<String, Double> narrow = properties.fusion().weights().narrowband();
        assertThat(sum(wide)).isCloseTo(1.0, within(0.001));
        assertThat(sum(narrow)).isCloseTo(1.0, within(0.001));
    }

    @Test
    void asymmetricEma_riseReachesTargetFasterThanFall() {
        EmaSmoother smoother = new EmaSmoother(properties);
        String riseSession = "rise";
        String fallSession = "fall";

        smoother.update(riseSession, 0.1, true, false);
        smoother.update(fallSession, 0.9, true, false);

        int riseWindows = 0;
        double rise = 0.1;
        while (rise < 0.8) {
            rise = smoother.update(riseSession, 0.9, true, false);
            riseWindows++;
            assertThat(riseWindows).isLessThan(100);
        }

        int fallWindows = 0;
        double fall = 0.9;
        while (fall > 0.2) {
            fall = smoother.update(fallSession, 0.1, true, false);
            fallWindows++;
            assertThat(fallWindows).isLessThan(200);
        }

        assertThat(riseWindows).isLessThan(fallWindows);
    }

    @Test
    void emaFrozenDuringSilence() {
        EmaSmoother smoother = new EmaSmoother(properties);
        smoother.update("sil", 0.7, true, false);
        double frozen = smoother.update("sil", 0.1, false, false);
        assertThat(frozen).isCloseTo(0.7, within(1e-12));
        assertThat(smoother.peek("sil")).isCloseTo(0.7, within(1e-12));
    }

    @Test
    void emergencyCliClaimPolicy() {
        FeatureFrame frame = baseFrameBuilder()
                .cumulativeSpeechMs(5000)
                .linguisticWithAsk(0.5, 5_000_000.0)
                .build();
        FusionContext ctx = new FusionContext(frame, 0.5, true, 0.5, true, true, 0.0);

        FusionResult result = engine.evaluate("em1", ctx);

        assertThat(result.emergencyReason()).isEqualTo(EmergencyTrigger.REASON_CLI_CLAIM_POLICY);
    }

    @Test
    void emergencyVoiceprintAsk() {
        FeatureFrame frame = baseFrameBuilder()
                .cumulativeSpeechMs(5000)
                .speakerEnrolled(0.41)
                .linguisticAskDetected()
                .build();
        FusionContext ctx = FusionContext.ofFrame(frame);

        FusionResult result = engine.evaluate("em2", ctx);

        assertThat(result.emergencyReason()).isEqualTo(EmergencyTrigger.REASON_VOICEPRINT_ASK);
    }

    @Test
    void emergencySecrecyAuthorityTransaction() {
        FeatureFrame frame = baseFrameBuilder()
                .cumulativeSpeechMs(5000)
                .linguisticSecrecyAuthority(0.90, 0.90)
                .build();
        FusionContext ctx = new FusionContext(frame, 0.85, true, 0.2, true, false, Double.MAX_VALUE);

        FusionResult result = engine.evaluate("em3", ctx);

        assertThat(result.emergencyReason()).isEqualTo(EmergencyTrigger.REASON_SECRECY_AUTHORITY_TXN);
    }

    @Test
    void insufficientEvidenceBelowSpeechThreshold() {
        FeatureFrame frame = baseFrameBuilder()
                .cumulativeSpeechMs(500)
                .linguistic(0.8, 0L)
                .build();

        FusionResult result = engine.evaluate("insuf", FusionContext.ofFrame(frame));

        assertThat(result.state()).isEqualTo(FusionResult.RiskState.INSUFFICIENT_EVIDENCE);
    }

    @Test
    void degradedWhenFewerThanThreeFamiliesAvailable() {
        FeatureFrame frame = baseFrameBuilder()
                .cumulativeSpeechMs(5000)
                .voiceUnavailable()
                .channelUnavailable()
                .prosodyUnavailable()
                .linguistic(0.7, 0L)
                .build();
        // only linguistic + no txn/relationship → 1 available
        FusionResult result = engine.evaluate("deg", FusionContext.ofFrame(frame));

        assertThat(result.state()).isEqualTo(FusionResult.RiskState.DEGRADED);
    }

    @Test
    void goldenVector_widebandTwoFamiliesExactScore() {
        // voice S=0.50 c=1.0 w=0.24; linguistic S=0.90 c=1.0 w=0.25 (ageMs=0)
        // S = (0.24*0.50 + 0.25*0.90) / (0.24+0.25) = 0.345 / 0.49
        double expected = 0.345 / 0.49;

        FeatureFrame frame = baseFrameBuilder()
                .channelProfile(ChannelProfile.WEBRTC_WIDEBAND)
                .cumulativeSpeechMs(5000)
                .voice(0.50, 1.0)
                .channelUnavailable()
                .prosodyUnavailable()
                .linguistic(0.90, 0L)
                .build();

        FusionResult result = engine.evaluate("gold", FusionContext.ofFrame(frame));

        assertThat(result.instantaneous()).isCloseTo(expected, within(1e-12));
    }

    @Test
    void changingWeightInPropertiesChangesOutput() {
        FeatureFrame frame = baseFrameBuilder()
                .cumulativeSpeechMs(5000)
                .voice(0.80, 1.0)
                .channelUnavailable()
                .prosodyUnavailable()
                .linguistic(0.20, 0L)
                .build();

        double withDefault = engine.evaluate("w1", FusionContext.ofFrame(frame)).instantaneous();

        // Boost linguistic weight, shrink voice — score should drop toward linguistic=0.20
        SentinelProperties skewed = testPropertiesWithWeights(
                Map.of(
                        "voice", 0.10,
                        "channel", 0.08,
                        "prosody", 0.13,
                        "linguistic", 0.39,
                        "transaction", 0.18,
                        "relationship", 0.12
                ),
                properties.fusion().weights().narrowband()
        );
        FusionEngineService skewedEngine = new FusionEngineService(
                skewed,
                new EmaSmoother(skewed),
                new CorroborationGate(skewed),
                new EmergencyTrigger(skewed)
        );
        double withSkew = skewedEngine.evaluate("w2", FusionContext.ofFrame(frame)).instantaneous();

        assertThat(withSkew).isLessThan(withDefault);
    }

    private static double sum(Map<String, Double> weights) {
        return weights.values().stream().mapToDouble(Double::doubleValue).sum();
    }

    public static SentinelProperties testProperties() {
        return testPropertiesWithWeights(
                Map.of(
                        "voice", 0.24,
                        "channel", 0.08,
                        "prosody", 0.13,
                        "linguistic", 0.25,
                        "transaction", 0.18,
                        "relationship", 0.12
                ),
                Map.of(
                        "voice", 0.15,
                        "channel", 0.10,
                        "prosody", 0.12,
                        "linguistic", 0.29,
                        "transaction", 0.20,
                        "relationship", 0.14
                )
        );
    }

    static SentinelProperties testPropertiesWithWeights(
            Map<String, Double> wideband,
            Map<String, Double> narrowband
    ) {
        return new SentinelProperties(
                new SentinelProperties.Fusion(
                        new SentinelProperties.Weights(wideband, narrowband),
                        0.55,
                        0.88,
                        Map.of(
                                "voice", 0.60,
                                "channel", 0.55,
                                "prosody", 0.60,
                                "linguistic", 0.65,
                                "transaction", 0.60,
                                "relationship", 0.60
                        ),
                        3000L,
                        3000L,
                        new SentinelProperties.Emergency(0.50, 0.85, 0.85, 0.80)
                ),
                new SentinelProperties.Intervention(
                        new SentinelProperties.Transition(0.35, 0.28, 1000),
                        new SentinelProperties.Transition(0.35, 0.28, 5000),
                        new SentinelProperties.Transition(0.55, 0.46, 1000),
                        new SentinelProperties.Transition(0.55, 0.46, 8000),
                        new SentinelProperties.Transition(0.75, 0.66, 1000),
                        new SentinelProperties.Transition(0.75, 0.66, 15000),
                        new SentinelProperties.Transition(0.90, 0.90, 0),
                        120_000L
                ),
                new SentinelProperties.Ml("http://localhost:8000", "ws://localhost:8000/ingest", 2000, 1500),
                new SentinelProperties.Session(30, 100),
                new SentinelProperties.Audit("SENTINELVOICE-GENESIS-v1"),
                new SentinelProperties.Identity(
                        "^(ext-)?\\d{3,5}$",
                        java.util.List.of("+91-22-6655-0100", "+91-22-6655-0001"),
                        0.70,
                        0.50,
                        0.60
                ),
                new SentinelProperties.ContextScoring(
                        new SentinelProperties.RelationshipScoring(
                                0.60, 0.35, 0.05, 0.05, 6, 9, 18, 4, 2.0
                        ),
                        new SentinelProperties.TransactionScoring(
                                0.98, 0.25, 0.20, 0.15, 0.40,
                                100_000.0, 5_000_000.0, 3,
                                java.util.List.of("vendor account ending 8821", "payroll suspense 1001")
                        )
                ),
                new SentinelProperties.Actuation(
                        "noop",
                        new SentinelProperties.Ari(
                                "http://127.0.0.1:8088/ari",
                                "sentinel",
                                "sentineldemo",
                                2000,
                                3000
                        ),
                        "http://127.0.0.1:8080/mock-cbs/freeze",
                        "PJSIP/agent"
                )
        );
    }

    private static FrameBuilder baseFrameBuilder() {
        return new FrameBuilder();
    }

    /** Fluent builder for FeatureFrame test fixtures. */
    static final class FrameBuilder {
        private ChannelProfile channelProfile = ChannelProfile.WEBRTC_WIDEBAND;
        private boolean speechPresent = true;
        private long cumulativeSpeechMs = 5000;
        private FeatureFrame.VoiceFamily voice =
                new FeatureFrame.VoiceFamily(true, 0.5, "test", 1.0);
        private FeatureFrame.ChannelFamily channel =
                new FeatureFrame.ChannelFamily(true, 40.0, true, 0.5, 0.5, 0.0);
        private FeatureFrame.ProsodyFamily prosody =
                new FeatureFrame.ProsodyFamily(true, 120.0, 5.0, 0.5, 4.0, 20.0, 10.0, 0.0, 4.0, 0.5);
        private FeatureFrame.SpeakerFamily speaker =
                new FeatureFrame.SpeakerFamily(false, null, null, null, null);
        private LinguisticFamily linguistic =
                new LinguisticFamily(
                        false, null, null, null, null, null, null, null, null, null, null, null);

        FrameBuilder channelProfile(ChannelProfile profile) {
            this.channelProfile = profile;
            return this;
        }

        FrameBuilder speechPresent(boolean value) {
            this.speechPresent = value;
            return this;
        }

        FrameBuilder cumulativeSpeechMs(long ms) {
            this.cumulativeSpeechMs = ms;
            return this;
        }

        FrameBuilder voice(double spoof, double confidence) {
            this.voice = new FeatureFrame.VoiceFamily(true, spoof, "test", confidence);
            return this;
        }

        FrameBuilder voiceUnavailable() {
            this.voice = new FeatureFrame.VoiceFamily(false, null, null, null);
            return this;
        }

        FrameBuilder channelUnavailable() {
            this.channel = new FeatureFrame.ChannelFamily(false, null, null, null, null, null);
            return this;
        }

        FrameBuilder prosodyUnavailable() {
            this.prosody = new FeatureFrame.ProsodyFamily(
                    false, null, null, null, null, null, null, null, null, null);
            return this;
        }

        FrameBuilder linguistic(double score, long ageMs) {
            this.linguistic = new LinguisticFamily(
                    true, ageMs, "en", score, score, score, score,
                    false, null, null, null, "");
            return this;
        }

        FrameBuilder linguisticWithAsk(double score, double amount) {
            this.linguistic = new LinguisticFamily(
                    true, 0L, "en", score, score, score, score,
                    true,
                    new Ask("WIRE_TRANSFER", amount, "INR", "x", "immediate"),
                    "Claimed", "CFO", "");
            return this;
        }

        FrameBuilder linguisticAskDetected() {
            this.linguistic = new LinguisticFamily(
                    true, 0L, "en", 0.5, 0.5, 0.5, 0.5,
                    true,
                    new Ask("WIRE_TRANSFER", 1000.0, "INR", "x", "now"),
                    "Claimed", "CFO", "");
            return this;
        }

        FrameBuilder linguisticSecrecyAuthority(double secrecy, double authority) {
            this.linguistic = new LinguisticFamily(
                    true, 0L, "en", 0.5, secrecy, authority, 0.5,
                    false, null, null, null, "");
            return this;
        }

        FrameBuilder speakerEnrolled(double cosine) {
            this.speaker = new FeatureFrame.SpeakerFamily(true, "emb-1", "EMP-1", cosine, 0.1);
            return this;
        }

        FeatureFrame build() {
            return new FeatureFrame(
                    "sentinelvoice.FeatureFrame/1",
                    "call-test",
                    1,
                    0L,
                    500L,
                    channelProfile,
                    speechPresent,
                    cumulativeSpeechMs,
                    voice,
                    channel,
                    prosody,
                    speaker,
                    new FeatureFrame.WatermarkFamily(false, null, null, null, null),
                    linguistic,
                    new FeatureFrame.LatencyMs(50, 200)
            );
        }
    }
}
