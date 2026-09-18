package com.sentinelvoice.ingest;

import com.sentinelvoice.actuation.ActuationService;
import com.sentinelvoice.audit.AuditWriteDispatcher;
import com.sentinelvoice.challenge.ChallengeProperties;
import com.sentinelvoice.challenge.ChallengeService;
import com.sentinelvoice.config.SentinelProperties;
import com.sentinelvoice.context.CrossChannelCorrelationService;
import com.sentinelvoice.context.RelationshipGraphService;
import com.sentinelvoice.context.TransactionPolicyService;
import com.sentinelvoice.context.model.CorrelationResult;
import com.sentinelvoice.context.model.RelationshipAssessment;
import com.sentinelvoice.context.model.TransactionAssessment;
import com.sentinelvoice.fusion.CorroborationGate;
import com.sentinelvoice.fusion.EmaSmoother;
import com.sentinelvoice.fusion.EmergencyTrigger;
import com.sentinelvoice.fusion.FusionEngineService;
import com.sentinelvoice.fusion.FusionEngineTest;
import com.sentinelvoice.fusion.ReasonGenerator;
import com.sentinelvoice.identity.DirectoryService;
import com.sentinelvoice.identity.IdentityResolutionService;
import com.sentinelvoice.identity.IdentityVerdict;
import com.sentinelvoice.identity.model.IdentityAssessment;
import com.sentinelvoice.intervention.InterventionLadderService;
import com.sentinelvoice.intervention.InterventionStateMachine;
import com.sentinelvoice.model.Ask;
import com.sentinelvoice.model.ChannelProfile;
import com.sentinelvoice.model.FeatureFrame;
import com.sentinelvoice.model.LinguisticFamily;
import com.sentinelvoice.model.SessionStartRequest;
import com.sentinelvoice.service.CallSessionManager;
import com.sentinelvoice.telemetry.TelemetryBroadcaster;
import com.sentinelvoice.telemetry.TelemetryFrameBuilder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Isolated latency check: real fusion + FSM + reasons, no JPA/STOMP I/O on the hot path.
 */
class PipelineLatencyTest {

    private FeatureFrameIngestService ingest;
    private CallSessionManager sessions;
    private SimpleMeterRegistry meters;
    private AtomicLong clockMs;

    @BeforeEach
    void setUp() {
        SentinelProperties properties = FusionEngineTest.testProperties();
        clockMs = new AtomicLong(1_700_000_000_000L);
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return Instant.ofEpochMilli(clockMs.get());
            }
        };

        meters = new SimpleMeterRegistry();
        var auditLedger = mock(com.sentinelvoice.audit.AuditLedgerService.class);
        when(auditLedger.append(any(), any(), any())).thenReturn(new com.sentinelvoice.model.AuditBlock());
        sessions = new CallSessionManager(properties, auditLedger);

        FusionEngineService fusion = new FusionEngineService(
                properties,
                new EmaSmoother(properties),
                new CorroborationGate(properties),
                new EmergencyTrigger(properties)
        );
        AuditWriteDispatcher auditDispatcher = mock(AuditWriteDispatcher.class);
        InterventionLadderService ladder = new InterventionLadderService(
                new InterventionStateMachine(properties, auditDispatcher)
        );

        IdentityResolutionService identity = mock(IdentityResolutionService.class);
        when(identity.resolve(any(), any())).thenReturn(new IdentityAssessment(
                "+91-22-6655-0100",
                "REGISTERED_EXTERNAL",
                null,
                "Rajesh Kumar",
                "CFO",
                java.util.Map.of("employeeId", "EMP-10492", "role", "CFO"),
                false,
                new IdentityAssessment.VoicePassport(false, null, IdentityVerdict.INCONCLUSIVE),
                null,
                0.1,
                0.0,
                List.of()
        ));

        RelationshipGraphService relationship = mock(RelationshipGraphService.class);
        when(relationship.assess(any())).thenReturn(
                new RelationshipAssessment(10, false, 1, false, false, 0.2, List.of())
        );

        TransactionPolicyService transaction = mock(TransactionPolicyService.class);
        when(transaction.assess(any(), any())).thenAnswer(inv -> {
            FeatureFrame frame = inv.getArgument(0);
            boolean ask = frame.linguistic() != null && frame.linguistic().askDetected();
            return new TransactionAssessment(
                    ask ? 0.98 : 0.05,
                    ask ? List.of("POLICY_VIOLATION") : List.of("NO_ASK"),
                    ask,
                    true,
                    ask,
                    0,
                    ask ? 5_000_000.0 : null,
                    0.0
            );
        });

        ChallengeService challengeService = mock(ChallengeService.class);
        lenient().when(challengeService.lastFailure(any())).thenReturn(Optional.empty());
        lenient().when(challengeService.properties()).thenReturn(ChallengeProperties.defaults());

        ingest = new FeatureFrameIngestService(
                sessions,
                properties,
                fusion,
                ladder,
                new ReasonGenerator(properties),
                identity,
                relationship,
                transaction,
                mockCrossChannel(),
                mock(DirectoryService.class),
                auditDispatcher,
                new TelemetryFrameBuilder(),
                mock(TelemetryBroadcaster.class),
                mock(ActuationService.class),
                challengeService,
                meters,
                clock
        );
    }

    private static CrossChannelCorrelationService mockCrossChannel() {
        CrossChannelCorrelationService cross = mock(CrossChannelCorrelationService.class);
        when(cross.correlateSession(any(), any())).thenReturn(CorrelationResult.empty(48));
        when(cross.blendRelationshipScore(anyDouble(), any())).thenAnswer(inv -> inv.getArgument(0));
        return cross;
    }

    @Test
    void hotPathP95UnderFifteenMilliseconds() {
        String sessionId = "latency-bench";
        sessions.createSession(new SessionStartRequest(
                "sentinelvoice.SessionStartRequest/1",
                sessionId,
                "+91-22-6655-0100",
                "EMP-30020",
                ChannelProfile.WEBRTC_WIDEBAND,
                null
        ));

        // Warm JIT / class loading.
        for (int i = 1; i <= 5; i++) {
            ingest.ingest(frame(sessionId, i, 0.3 + i * 0.05));
            clockMs.addAndGet(1_100);
        }

        long warmCount = meters.find("sentinel.pipeline.latency").timer().count();
        double warmTotalNs = meters.find("sentinel.pipeline.latency").timer().totalTime(TimeUnit.NANOSECONDS);

        for (int i = 6; i <= 55; i++) {
            double urgency = Math.min(0.95, 0.2 + (i - 6) * 0.015);
            ingest.ingest(frame(sessionId, i, urgency));
            clockMs.addAndGet(1_100);
        }

        var timer = meters.find("sentinel.pipeline.latency").timer();
        assertThat(timer).isNotNull();
        long measured = timer.count() - warmCount;
        assertThat(measured).isEqualTo(50);

        double measuredNs = timer.totalTime(TimeUnit.NANOSECONDS) - warmTotalNs;
        double meanMs = (measuredNs / measured) / 1_000_000.0;
        assertThat(meanMs)
                .as("hot-path mean %.3fms should sit well under the 15ms p95 budget", meanMs)
                .isLessThan(5.0);
    }

    private static FeatureFrame frame(String sessionId, int seq, double urgency) {
        boolean ask = seq >= 3;
        return new FeatureFrame(
                "sentinelvoice.FeatureFrame/1",
                sessionId,
                seq,
                0,
                500,
                ChannelProfile.WEBRTC_WIDEBAND,
                true,
                5_000L,
                new FeatureFrame.VoiceFamily(true, Math.min(0.9, 0.3 + seq * 0.02), "bench", 1.0),
                new FeatureFrame.ChannelFamily(false, null, null, null, null, null),
                new FeatureFrame.ProsodyFamily(false, null, null, null, null, null, null, null, null, null),
                new FeatureFrame.SpeakerFamily(false, null, null, null, null),
                new FeatureFrame.WatermarkFamily(false, null, null, null, null),
                new LinguisticFamily(
                        true, 50L, "en", urgency, 0.5, 0.5, 0.4,
                        ask,
                        ask ? new Ask("WIRE_TRANSFER", 5_000_000.0, "INR", "vendor 4471", "immediate") : null,
                        "Rajesh Kumar", "CFO", ""
                ),
                new FeatureFrame.LatencyMs(10.0, 50.0)
        );
    }
}
