package com.sentinelvoice.telemetry;

import com.sentinelvoice.model.CallSession;
import com.sentinelvoice.model.TelemetryFrame;
import com.sentinelvoice.service.CallSessionManager;
import com.sentinelvoice.telephony.LiveCallsBroadcaster;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Publishes {@link TelemetryFrame}s to STOMP
 * {@code /topic/tenant/{tenantId}/telemetry/{sessionId}}.
 */
@Component
public class TelemetryBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(TelemetryBroadcaster.class);
    private static final int QUEUE_CAPACITY = 4;

    private final SimpMessagingTemplate messagingTemplate;
    private final CallSessionManager callSessionManager;
    private final ConcurrentHashMap<String, ArrayDeque<TelemetryFrame>> queues = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TelemetryFrame> latestBySession = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicBoolean> draining = new ConcurrentHashMap<>();
    private final ExecutorService drainExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "telemetry-stomp-drain");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Counter droppedOldest;
    private final LiveCallsBroadcaster liveCallsBroadcaster;
    private final boolean labMode;

    public TelemetryBroadcaster(
            @Lazy SimpMessagingTemplate messagingTemplate,
            @Lazy CallSessionManager callSessionManager,
            @Lazy LiveCallsBroadcaster liveCallsBroadcaster,
            MeterRegistry meterRegistry,
            @Value("${LAB_MODE:false}") boolean labMode
    ) {
        this.messagingTemplate = messagingTemplate;
        this.callSessionManager = callSessionManager;
        this.liveCallsBroadcaster = liveCallsBroadcaster;
        this.labMode = labMode;
        this.droppedOldest = Counter.builder("sentinel.telemetry.dropped_oldest")
                .description("Oldest TelemetryFrames dropped under STOMP backpressure")
                .register(meterRegistry);
    }

    public static String topic(UUID tenantId, String sessionId) {
        return "/topic/tenant/" + tenantId + "/telemetry/" + sessionId;
    }

    public void publish(TelemetryFrame frame) {
        if (closed.get() || frame == null || frame.sessionId() == null || frame.sessionId().isBlank()) {
            return;
        }
        String sessionId = frame.sessionId();
        latestBySession.put(sessionId, frame);

        ArrayDeque<TelemetryFrame> queue = queues.computeIfAbsent(sessionId, id -> new ArrayDeque<>());
        synchronized (queue) {
            while (queue.size() >= QUEUE_CAPACITY) {
                TelemetryFrame dropped = queue.pollFirst();
                droppedOldest.increment();
                log.debug(
                        "telemetry_drop_oldest sessionId={} droppedSeq={} newestSeq={}",
                        sessionId,
                        dropped == null ? -1 : dropped.seq(),
                        frame.seq()
                );
            }
            queue.offerLast(frame);
        }
        scheduleDrain(sessionId);
    }

    public void flushPending(String sessionId) {
        scheduleDrain(sessionId);
    }

    public Optional<TelemetryFrame> latest(String sessionId) {
        return Optional.ofNullable(latestBySession.get(sessionId));
    }

    public void clear(String sessionId) {
        ArrayDeque<TelemetryFrame> queue = queues.remove(sessionId);
        if (queue != null) {
            synchronized (queue) {
                queue.clear();
            }
        }
        latestBySession.remove(sessionId);
        draining.remove(sessionId);
    }

    static TelemetryFrame redactTranscriptForWire(TelemetryFrame frame) {
        if (frame == null || frame.transcriptDelta() == null) {
            return frame;
        }
        TelemetryFrame.TranscriptDelta td = frame.transcriptDelta();
        if (td.text() == null || td.text().isEmpty()) {
            return frame;
        }
        return new TelemetryFrame(
                frame.schema(),
                frame.sessionId(),
                frame.seq(),
                frame.tsEpochMs(),
                frame.callElapsedMs(),
                frame.risk(),
                frame.families(),
                frame.corroboration(),
                frame.intervention(),
                frame.identity(),
                frame.topReasons(),
                new TelemetryFrame.TranscriptDelta(td.tsMs(), "", td.flags()),
                frame.auditHash()
        );
    }

    private void scheduleDrain(String sessionId) {
        AtomicBoolean flag = draining.computeIfAbsent(sessionId, id -> new AtomicBoolean(false));
        if (flag.compareAndSet(false, true)) {
            drainExecutor.execute(() -> drain(sessionId));
        }
    }

    private void drain(String sessionId) {
        try {
            while (true) {
                TelemetryFrame next;
                ArrayDeque<TelemetryFrame> queue = queues.get(sessionId);
                if (queue == null) {
                    return;
                }
                synchronized (queue) {
                    next = queue.pollFirst();
                    if (next == null) {
                        return;
                    }
                }
                Optional<CallSession> session = callSessionManager.getSession(sessionId);
                if (session.isEmpty()) {
                    log.debug("telemetry_skip_no_session sessionId={}", sessionId);
                    continue;
                }
                String destination = topic(session.get().getTenantId(), sessionId);
                // Lab operator workspace needs live captions on the selected-call topic.
                TelemetryFrame wire = labMode ? next : redactTranscriptForWire(next);
                messagingTemplate.convertAndSend(destination, wire);
                try {
                    liveCallsBroadcaster.onTelemetry(next);
                } catch (RuntimeException ex) {
                    log.debug("live_calls_delta_failed sessionId={} cause={}", sessionId, ex.toString());
                }
                log.debug(
                        "telemetry_publish sessionId={} seq={} destination={}",
                        sessionId,
                        next.seq(),
                        destination
                );
            }
        } finally {
            AtomicBoolean flag = draining.get(sessionId);
            if (flag != null) {
                flag.set(false);
                ArrayDeque<TelemetryFrame> queue = queues.get(sessionId);
                if (queue != null) {
                    synchronized (queue) {
                        if (!queue.isEmpty()) {
                            scheduleDrain(sessionId);
                        }
                    }
                }
            }
        }
    }
}
