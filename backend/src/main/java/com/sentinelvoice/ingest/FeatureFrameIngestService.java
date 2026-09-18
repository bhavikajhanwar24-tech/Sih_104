package com.sentinelvoice.ingest;

import com.sentinelvoice.config.SentinelProperties;
import com.sentinelvoice.fusion.EvidenceFamily;
import com.sentinelvoice.fusion.FusionContext;
import com.sentinelvoice.fusion.FusionEngineService;
import com.sentinelvoice.fusion.FusionResult;
import com.sentinelvoice.intervention.InterventionDecision;
import com.sentinelvoice.intervention.InterventionLadderService;
import com.sentinelvoice.intervention.InterventionStateMachine;
import com.sentinelvoice.model.CallSession;
import com.sentinelvoice.model.FeatureFrame;
import com.sentinelvoice.model.InterventionLevel;
import com.sentinelvoice.model.TelemetryEntry;
import com.sentinelvoice.model.TelemetryFrame;
import com.sentinelvoice.service.CallSessionManager;
import com.sentinelvoice.telemetry.TelemetryBroadcaster;
import com.sentinelvoice.telemetry.TelemetryFrameBuilder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Accepts FeatureFrames from the Inference Plane, runs fusion + intervention,
 * and publishes a {@link TelemetryFrame} to the analyst console.
 */
@Service
public class FeatureFrameIngestService {

    private static final Logger log = LoggerFactory.getLogger(FeatureFrameIngestService.class);
    private static final long EPOCH_MS_THRESHOLD = 1_000_000_000_000L;

    private final CallSessionManager callSessionManager;
    private final SentinelProperties properties;
    private final FusionEngineService fusionEngineService;
    private final InterventionLadderService interventionLadderService;
    private final TelemetryFrameBuilder telemetryFrameBuilder;
    private final TelemetryBroadcaster telemetryBroadcaster;
    private final Counter received;
    private final Counter dropped;
    private final Counter stale;

    public FeatureFrameIngestService(
            CallSessionManager callSessionManager,
            SentinelProperties properties,
            FusionEngineService fusionEngineService,
            InterventionLadderService interventionLadderService,
            TelemetryFrameBuilder telemetryFrameBuilder,
            TelemetryBroadcaster telemetryBroadcaster,
            MeterRegistry meterRegistry
    ) {
        this.callSessionManager = callSessionManager;
        this.properties = properties;
        this.fusionEngineService = fusionEngineService;
        this.interventionLadderService = interventionLadderService;
        this.telemetryFrameBuilder = telemetryFrameBuilder;
        this.telemetryBroadcaster = telemetryBroadcaster;
        this.received = Counter.builder("sentinel.frames.received")
                .description("FeatureFrames accepted into a CallSession")
                .register(meterRegistry);
        this.dropped = Counter.builder("sentinel.frames.dropped")
                .description("FeatureFrames dropped (unknown session, out of order, invalid)")
                .register(meterRegistry);
        this.stale = Counter.builder("sentinel.frames.stale")
                .description("FeatureFrames dropped for exceeding frameStalenessMs")
                .register(meterRegistry);
    }

    public void rejectInvalid(Exception cause) {
        dropped.increment();
        log.warn("feature_frame_drop reason=invalid cause={}", cause.getClass().getSimpleName());
    }

    public void ingest(FeatureFrame frame) {
        if (missingSessionId(frame)) {
            dropped.increment();
            throw new FrameValidationException("feature frame missing sessionId");
        }
        Optional<CallSession> existing = callSessionManager.getSession(frame.sessionId());
        if (existing.isEmpty()) {
            dropped.increment();
            log.warn("feature_frame_drop reason=unknown_session sessionId={} seq={}", frame.sessionId(), frame.seq());
            return;
        }
        CallSession session = existing.get();
        if (frame.seq() <= session.getLastFeatureSeq()) {
            dropped.increment();
            log.warn(
                    "feature_frame_drop reason=out_of_order sessionId={} seq={} lastSeq={}",
                    frame.sessionId(),
                    frame.seq(),
                    session.getLastFeatureSeq()
            );
            return;
        }
        long ageMs = frameAgeMs(session, frame);
        int stalenessMs = properties.ml().frameStalenessMs();
        if (ageMs > stalenessMs) {
            stale.increment();
            log.warn(
                    "feature_frame_drop reason=stale sessionId={} seq={} ageMs={} stalenessMs={}",
                    frame.sessionId(),
                    frame.seq(),
                    ageMs,
                    stalenessMs
            );
            return;
        }
        session.storeFeatureFrame(frame);
        received.increment();
        log.info(
                "feature_frame sessionId={} seq={} speechPresent={} cumulativeSpeechMs={} fastPathMs={}",
                frame.sessionId(),
                frame.seq(),
                frame.speechPresent(),
                frame.cumulativeSpeechMs(),
                frame.latencyMs() == null ? -1 : frame.latencyMs().fastPath()
        );

        try {
            publishTelemetry(session, frame);
        } catch (ex) {
            // Frame is already accepted — fusion/broadcast failures must not mark it invalid
            // (which would also confuse the FeatureFrameSocketHandler drop counters).
            log.error(
                    "telemetry_publish_failed sessionId={} seq={} cause={}",
                    frame.sessionId(),
                    frame.seq(),
                    ex.toString(),
                    ex
            );
        }
    }

    private void publishTelemetry(CallSession session, FeatureFrame frame) {
        long nowMs = Instant.now().toEpochMilli();
        InterventionLevel previousLevel = session.getCurrentLevel();

        FusionResult fusion = fusionEngineService.evaluate(
                session.getSessionId(),
                FusionContext.ofFrame(frame)
        );

        List<String> corroborating = fusion.corroboration().familiesAboveThreshold().stream()
                .map(EvidenceFamily::configKey)
                .toList();

        InterventionDecision decision = interventionLadderService.evaluate(
                session.getSessionId(),
                new InterventionStateMachine.EvaluationInput(
                        fusion.smoothed(),
                        fusion.corroboration().satisfied(),
                        corroborating,
                        fusion.emergencyReason() != null,
                        false,
                        nowMs
                )
        );

        TelemetryFrame telemetry = telemetryFrameBuilder.build(
                session,
                frame,
                fusion,
                decision,
                previousLevel,
                nowMs
        );

        Map<String, Double> factorBreakdown = new LinkedHashMap<>();
        fusion.families().forEach((family, score) ->
                factorBreakdown.put(family.configKey(), score.available() ? score.score() : 0.0));

        callSessionManager.recordTelemetry(
                session.getSessionId(),
                new TelemetryEntry(
                        frame.seq(),
                        nowMs,
                        fusion.instantaneous(),
                        fusion.smoothed(),
                        decision.level(),
                        factorBreakdown
                )
        );

        telemetryBroadcaster.publish(telemetry);
        log.info(
                "telemetry_built sessionId={} seq={} smoothed={} level={}",
                session.getSessionId(),
                frame.seq(),
                fusion.smoothed(),
                decision.level()
        );
    }

    private static boolean missingSessionId(FeatureFrame frame) {
        return frame == null || frame.sessionId() == null || frame.sessionId().isBlank();
    }

    long frameAgeMs(CallSession session, FeatureFrame frame) {
        long now = Instant.now().toEpochMilli();
        long windowEnd = frame.windowEndMs();
        long frameEndEpoch = windowEnd > EPOCH_MS_THRESHOLD
                ? windowEnd
                : session.getCreatedAt().toEpochMilli() + windowEnd;
        return now - frameEndEpoch;
    }
}
