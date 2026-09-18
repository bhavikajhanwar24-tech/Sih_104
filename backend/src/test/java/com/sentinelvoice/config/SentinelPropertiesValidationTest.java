package com.sentinelvoice.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class SentinelPropertiesValidationTest {

    private static final String[] VALID = {
            "sentinelvoice.fusion.weights.wideband.voice=0.24",
            "sentinelvoice.fusion.weights.wideband.channel=0.08",
            "sentinelvoice.fusion.weights.wideband.prosody=0.13",
            "sentinelvoice.fusion.weights.wideband.linguistic=0.25",
            "sentinelvoice.fusion.weights.wideband.transaction=0.18",
            "sentinelvoice.fusion.weights.wideband.relationship=0.12",
            "sentinelvoice.fusion.weights.narrowband.voice=0.15",
            "sentinelvoice.fusion.weights.narrowband.channel=0.10",
            "sentinelvoice.fusion.weights.narrowband.prosody=0.12",
            "sentinelvoice.fusion.weights.narrowband.linguistic=0.29",
            "sentinelvoice.fusion.weights.narrowband.transaction=0.20",
            "sentinelvoice.fusion.weights.narrowband.relationship=0.14",
            "sentinelvoice.fusion.lambda-up=0.55",
            "sentinelvoice.fusion.lambda-down=0.88",
            "sentinelvoice.fusion.family-thresholds.voice=0.60",
            "sentinelvoice.fusion.family-thresholds.channel=0.55",
            "sentinelvoice.fusion.family-thresholds.prosody=0.60",
            "sentinelvoice.fusion.family-thresholds.linguistic=0.65",
            "sentinelvoice.fusion.family-thresholds.transaction=0.60",
            "sentinelvoice.fusion.family-thresholds.relationship=0.60",
            "sentinelvoice.fusion.linguistic-staleness-tau-ms=3000",
            "sentinelvoice.fusion.min-speech-ms-for-scoring=3000",
            "sentinelvoice.fusion.emergency.cosine-mismatch-threshold=0.50",
            "sentinelvoice.fusion.emergency.secrecy-threshold=0.85",
            "sentinelvoice.fusion.emergency.authority-threshold=0.85",
            "sentinelvoice.fusion.emergency.transaction-score-threshold=0.80",
            "sentinelvoice.intervention.l1-to-l2.up-threshold=0.35",
            "sentinelvoice.intervention.l1-to-l2.down-threshold=0.28",
            "sentinelvoice.intervention.l1-to-l2.dwell-ms=1000",
            "sentinelvoice.intervention.l2-to-l1.up-threshold=0.35",
            "sentinelvoice.intervention.l2-to-l1.down-threshold=0.28",
            "sentinelvoice.intervention.l2-to-l1.dwell-ms=5000",
            "sentinelvoice.intervention.l2-to-l3.up-threshold=0.55",
            "sentinelvoice.intervention.l2-to-l3.down-threshold=0.46",
            "sentinelvoice.intervention.l2-to-l3.dwell-ms=1000",
            "sentinelvoice.intervention.l3-to-l2.up-threshold=0.55",
            "sentinelvoice.intervention.l3-to-l2.down-threshold=0.46",
            "sentinelvoice.intervention.l3-to-l2.dwell-ms=8000",
            "sentinelvoice.intervention.l3-to-l4.up-threshold=0.75",
            "sentinelvoice.intervention.l3-to-l4.down-threshold=0.66",
            "sentinelvoice.intervention.l3-to-l4.dwell-ms=1000",
            "sentinelvoice.intervention.l4-to-l3.up-threshold=0.75",
            "sentinelvoice.intervention.l4-to-l3.down-threshold=0.66",
            "sentinelvoice.intervention.l4-to-l3.dwell-ms=15000",
            "sentinelvoice.intervention.l4-to-l5.up-threshold=0.90",
            "sentinelvoice.intervention.l4-to-l5.down-threshold=0.90",
            "sentinelvoice.intervention.l4-to-l5.dwell-ms=0",
            "sentinelvoice.intervention.override-pin-duration-ms=120000",
            "sentinelvoice.ml.base-url=http://localhost:8000",
            "sentinelvoice.ml.websocket-url=ws://localhost:8000/ingest",
            "sentinelvoice.ml.connect-timeout-ms=2000",
            "sentinelvoice.ml.frame-staleness-ms=1500",
            "sentinelvoice.session.ttl-minutes=30",
            "sentinelvoice.session.max-concurrent=100",
            "sentinelvoice.audit.genesis-prefix=SENTINELVOICE-GENESIS-v1",
            "sentinelvoice.identity.internal-extension-pattern=^(ext-)?\\d{3,5}$",
            "sentinelvoice.identity.registered-external-clis[0]=+91-22-6655-0100",
            "sentinelvoice.identity.cosine-match-min=0.70",
            "sentinelvoice.identity.cosine-mismatch-max=0.50",
            "sentinelvoice.identity.spoof-high-threshold=0.60",
            "sentinelvoice.context.relationship.weight-first-contact=0.60",
            "sentinelvoice.context.relationship.weight-hierarchy=0.35",
            "sentinelvoice.context.relationship.weight-off-hours=0.05",
            "sentinelvoice.context.relationship.weight-duration-anomaly=0.05",
            "sentinelvoice.context.relationship.weight-cross-channel=0.40",
            "sentinelvoice.context.relationship.hierarchy-normalize-levels=6",
            "sentinelvoice.context.relationship.business-hour-start=9",
            "sentinelvoice.context.relationship.business-hour-end=18",
            "sentinelvoice.context.relationship.typical-hour-deviation-hours=4",
            "sentinelvoice.context.relationship.duration-anomaly-ratio=2.0",
            "sentinelvoice.context.cross-channel.window-hours=48",
            "sentinelvoice.context.cross-channel.matching-campaign-boost=0.35",
            "sentinelvoice.context.cross-channel.indicator-match-boost=0.15",
            "sentinelvoice.context.cross-channel.multi-channel-boost=0.10",
            "sentinelvoice.context.transaction.policy-violation-score=0.98",
            "sentinelvoice.context.transaction.weight-channel-denied=0.25",
            "sentinelvoice.context.transaction.weight-beneficiary-novel=0.20",
            "sentinelvoice.context.transaction.weight-velocity=0.15",
            "sentinelvoice.context.transaction.weight-urgency-amount-product=0.40",
            "sentinelvoice.context.transaction.high-value-threshold-inr=100000",
            "sentinelvoice.context.transaction.large-amount-threshold-inr=5000000",
            "sentinelvoice.context.transaction.velocity-high-value-limit=3",
            "sentinelvoice.context.transaction.known-beneficiary-hints[0]=payroll suspense 1001",
            "sentinelvoice.actuation.adapter=noop",
            "sentinelvoice.actuation.ari.base-url=http://127.0.0.1:8088/ari",
            "sentinelvoice.actuation.ari.username=sentinel",
            "sentinelvoice.actuation.ari.password=sentineldemo",
            "sentinelvoice.actuation.ari.connect-timeout-ms=2000",
            "sentinelvoice.actuation.ari.read-timeout-ms=3000",
            "sentinelvoice.actuation.cbs-freeze-url=http://127.0.0.1:8080/mock-cbs/freeze",
            "sentinelvoice.actuation.supervisor-endpoint=PJSIP/agent",
            "sentinelvoice.compliance.telemetry-ttl-days=90",
            "sentinelvoice.compliance.audit-retention-years=7",
            "sentinelvoice.compliance.purge-interval-hours=24",
            "sentinelvoice.compliance.fairness-results-path=../ml-engine/benchmarks/results.json",
            "sentinelvoice.compliance.raw-audio-enforcing-path=ml-engine/app/ring_buffer.py"
    };

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    @Test
    void validPropertiesBind() {
        runner.withPropertyValues(VALID).run(context -> {
            assertThat(context).hasNotFailed();
            SentinelProperties properties = context.getBean(SentinelProperties.class);
            assertThat(properties.fusion().lambdaUp()).isEqualTo(0.55);
            assertThat(properties.fusion().lambdaDown()).isEqualTo(0.88);
            assertThat(properties.fusion().minSpeechMsForScoring()).isEqualTo(3000);
            assertThat(properties.audit().genesisPrefix()).isEqualTo("SENTINELVOICE-GENESIS-v1");
        });
    }

    @Test
    void lambdaUpOutOfRangeFailsStartup() {
        runner.withPropertyValues(VALID)
                .withPropertyValues("sentinelvoice.fusion.lambda-up=5.0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("fusion.lambdaUp must be between 0 and 1");
                });
    }

    @Test
    void weightsThatDoNotSumToOneFailStartup() {
        runner.withPropertyValues(VALID)
                .withPropertyValues(
                        "sentinelvoice.fusion.weights.wideband.voice=1",
                        "sentinelvoice.fusion.weights.wideband.channel=1",
                        "sentinelvoice.fusion.weights.wideband.prosody=1",
                        "sentinelvoice.fusion.weights.wideband.linguistic=1",
                        "sentinelvoice.fusion.weights.wideband.transaction=1",
                        "sentinelvoice.fusion.weights.wideband.relationship=1"
                )
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration
    @EnableConfigurationProperties(SentinelProperties.class)
    static class TestConfig {
    }
}
