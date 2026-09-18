package com.sentinelvoice.ingest;

import com.sentinelvoice.audit.AuditLedgerService;
import com.sentinelvoice.audit.ChainVerificationResult;
import com.sentinelvoice.model.Ask;
import com.sentinelvoice.model.ChannelProfile;
import com.sentinelvoice.model.FeatureFrame;
import com.sentinelvoice.model.InterventionLevel;
import com.sentinelvoice.model.LinguisticFamily;
import com.sentinelvoice.model.SessionStartRequest;
import com.sentinelvoice.model.TelemetryFrame;
import com.sentinelvoice.service.CallSessionManager;
import com.sentinelvoice.telemetry.TelemetryBroadcaster;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P5.4 end-to-end: FeatureFrame ingest → fusion → FSM → reasons → async audit → STOMP.
 */
@SpringBootTest
class PipelineIntegrationTest {

    @Autowired
    private CallSessionManager callSessionManager;

    @Autowired
    private FeatureFrameIngestService ingestService;

    @Autowired
    private AuditLedgerService auditLedgerService;

    @Autowired
    private TelemetryBroadcaster telemetryBroadcaster;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void risingLinguisticAndTransactionWalksL1ToL4AndAuditChainValidates() throws Exception {
        String sessionId = "pipeline-e2e-" + Instant.now().toEpochMilli();
        callSessionManager.createSession(new SessionStartRequest(
                "sentinelvoice.SessionStartRequest/1",
                sessionId,
                "+91-22-6655-0100", // registered CFO CLI — no CLI/claim emergency
                "EMP-30020",
                ChannelProfile.WEBRTC_WIDEBAND,
                "p5.4-escalation"
        ));

        Set<InterventionLevel> seen = EnumSet.noneOf(InterventionLevel.class);
        seen.add(InterventionLevel.LEVEL_1_SILENT);

        for (int seq = 1; seq <= 20; seq++) {
            double urgency = Math.min(0.95, 0.12 + (seq - 1) * 0.045);
            // Keep secrecy/authority below emergency thresholds (0.85) so FSM walks, not jumps.
            double secrecy = Math.min(0.80, 0.20 + (seq - 1) * 0.030);
            double authority = Math.min(0.80, 0.20 + (seq - 1) * 0.030);
            // Acoustic corroborator (voice) rises so L3+ can clear the cross-group gate.
            double spoof = Math.min(0.92, 0.20 + (seq - 1) * 0.040);
            boolean withAsk = seq >= 4;
            double amount = withAsk ? (seq < 12 ? 250_000.0 : 5_000_000.0) : 0.0;

            FeatureFrame frame = syntheticFrame(
                    sessionId, seq, urgency, secrecy, authority, spoof, withAsk, amount
            );
            ingestService.ingest(frame);

            telemetryBroadcaster.latest(sessionId).ifPresent(tf -> {
                InterventionLevel level = InterventionLevel.valueOf(tf.intervention().level());
                seen.add(level);
            });

            // Satisfy FSM dwell (1s) — denser sleeps once corroboration can fire (seq≥12).
            if (seq >= 10 || seq % 2 == 0) {
                Thread.sleep(1_100L);
            } else {
                Thread.sleep(50L);
            }
        }

        assertThat(seen)
                .as("session must walk the ladder under rising linguistic + transaction evidence")
                .contains(
                        InterventionLevel.LEVEL_1_SILENT,
                        InterventionLevel.LEVEL_2_SOFT_NUDGE,
                        InterventionLevel.LEVEL_3_STEP_UP_MFA,
                        InterventionLevel.LEVEL_4_AUTO_HOLD
                );

        TelemetryFrame latest = telemetryBroadcaster.latest(sessionId).orElseThrow();
        assertThat(latest.risk().state()).isNotEqualTo("DEGRADED");
        assertThat(latest.families().linguistic().available()).isTrue();
        assertThat(latest.families().transaction().available()).isTrue();
        assertThat(latest.topReasons()).isNotEmpty();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        ChainVerificationResult chain = null;
        while (System.nanoTime() < deadline) {
            chain = auditLedgerService.verify(sessionId);
            if (chain.valid() && chain.blockCount() >= 20) {
                break;
            }
            Thread.sleep(50L);
        }
        assertThat(chain).isNotNull();
        assertThat(chain.valid()).as("audit chain: %s", chain).isTrue();
        assertThat(chain.blockCount()).isGreaterThanOrEqualTo(20);

        Timer pipeline = meterRegistry.find("sentinel.pipeline.latency").timer();
        assertThat(pipeline).isNotNull();
        assertThat(pipeline.count()).isGreaterThanOrEqualTo(20);
        // Wall-clock p95 is enforced in demo via /actuator/metrics; SpringBootTest + H2
        // cold paths inflate means and are not a reliable latency oracle here.
        assertThat(pipeline.totalTime(TimeUnit.MILLISECONDS)).isPositive();
    }

    private static FeatureFrame syntheticFrame(
            String sessionId,
            int seq,
            double urgency,
            double secrecy,
            double authority,
            double spoofProbability,
            boolean withAsk,
            double amount
    ) {
        long now = Instant.now().toEpochMilli();
        Ask ask = withAsk
                ? new Ask("WIRE_TRANSFER", amount, "INR", "vendor account ending 4471", "immediate")
                : null;
        LinguisticFamily linguistic = new LinguisticFamily(
                true,
                100L,
                "en",
                urgency,
                secrecy,
                authority,
                Math.min(0.70, urgency * 0.8),
                withAsk,
                ask,
                "Rajesh Kumar",
                "CFO",
                withAsk ? "Transfer [AMOUNT] immediately" : ""
        );
        return new FeatureFrame(
                "sentinelvoice.FeatureFrame/1",
                sessionId,
                seq,
                now - 500,
                now,
                ChannelProfile.WEBRTC_WIDEBAND,
                true,
                Math.max(3_500L, seq * 800L),
                // Voice provides the acoustic half of the corroboration gate (Context §9.3).
                new FeatureFrame.VoiceFamily(true, spoofProbability, "test-spoof", 1.0),
                new FeatureFrame.ChannelFamily(false, null, null, null, null, null),
                new FeatureFrame.ProsodyFamily(false, null, null, null, null, null, null, null, null, null),
                new FeatureFrame.SpeakerFamily(false, null, null, null, null),
                new FeatureFrame.WatermarkFamily(false, null, null, null, null),
                linguistic,
                new FeatureFrame.LatencyMs(40.0, 200.0)
        );
    }
}
