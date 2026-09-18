package com.sentinelvoice.ingest;

import com.sentinelvoice.config.SentinelProperties;
import com.sentinelvoice.model.CallSession;
import com.sentinelvoice.model.FeatureFrame;
import com.sentinelvoice.service.CallSessionManager;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

@Service
public class FeatureFrameIngestService {

    private static final Logger log = LoggerFactory.getLogger(FeatureFrameIngestService.class);
    private static final long EPOCH_MS_THRESHOLD = 1_000_000_000_000L;

    private final CallSessionManager callSessionManager;
    private final SentinelProperties properties;
    private final Counter received;
    private final Counter dropped;
    private final Counter stale;

    public FeatureFrameIngestService(
            CallSessionManager callSessionManager,
            SentinelProperties properties,
            MeterRegistry meterRegistry
    ) {
        this.callSessionManager = callSessionManager;
        this.properties = properties;
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
