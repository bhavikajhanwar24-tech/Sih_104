package com.sentinelvoice.telemetry;

import com.sentinelvoice.model.TelemetryFrame;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Publishes {@link TelemetryFrame}s to STOMP {@code /topic/telemetry/{sessionId}}.
 *
 * <p>Per-session outbound queue with bounded depth: when full, drop the <em>oldest</em>
 * frame and keep the newest (stale risk is worse than a skipped update).
 */
@Component
public class TelemetryBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(TelemetryBroadcaster.class);
    private static final int QUEUE_CAPACITY = 4;
    private static final String TOPIC_PREFIX = "/topic/telemetry/";

    private final SimpMessagingTemplate messagingTemplate;
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

    public TelemetryBroadcaster(@Lazy SimpMessagingTemplate messagingTemplate, MeterRegistry meterRegistry) {
        this.messagingTemplate = messagingTemplate;
        this.droppedOldest = Counter.builder("sentinel.telemetry.dropped_oldest")
                .description("Oldest TelemetryFrames dropped under STOMP backpressure")
                .register(meterRegistry);
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
                String destination = TOPIC_PREFIX + sessionId;
                messagingTemplate.convertAndSend(destination, next);
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
            }
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
