package com.sentinelvoice.telemetry;

import com.sentinelvoice.model.TelemetryFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Publishes {@link TelemetryFrame}s to STOMP {@code /topic/telemetry/{sessionId}}.
 *
 * <p>Coalesces to at most one publish per 400 ms per session so a FeatureFrame burst
 * cannot flood the browser. The latest coalesced frame is flushed when the window
 * elapses. The latest frame for each session is retained for REST fallback.
 */
@Component
public class TelemetryBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(TelemetryBroadcaster.class);
    private static final long MIN_PUBLISH_INTERVAL_MS = 400L;
    private static final String TOPIC_PREFIX = "/topic/telemetry/";

    private final SimpMessagingTemplate messagingTemplate;
    private final ConcurrentHashMap<String, Long> lastPublishAtMs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TelemetryFrame> latestBySession = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TelemetryFrame> pendingBySession = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> flushTasks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "telemetry-coalesce");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public TelemetryBroadcaster(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Store and (subject to 400 ms coalesce) publish a telemetry frame.
     */
    public void publish(TelemetryFrame frame) {
        if (closed.get() || frame == null || frame.sessionId() == null || frame.sessionId().isBlank()) {
            return;
        }
        String sessionId = frame.sessionId();
        latestBySession.put(sessionId, frame);

        long now = System.currentTimeMillis();
        Long last = lastPublishAtMs.get(sessionId);
        if (last != null && (now - last) < MIN_PUBLISH_INTERVAL_MS) {
            pendingBySession.put(sessionId, frame);
            scheduleFlush(sessionId, MIN_PUBLISH_INTERVAL_MS - (now - last));
            log.debug(
                    "telemetry_coalesced sessionId={} seq={} waitMs={}",
                    sessionId,
                    frame.seq(),
                    MIN_PUBLISH_INTERVAL_MS - (now - last)
            );
            return;
        }
        flush(sessionId, frame, now);
    }

    /**
     * Force-publish any coalesced pending frame (e.g. on session close).
     */
    public void flushPending(String sessionId) {
        cancelFlushTask(sessionId);
        TelemetryFrame pending = pendingBySession.remove(sessionId);
        if (pending != null) {
            flush(sessionId, pending, System.currentTimeMillis());
        }
    }

    public Optional<TelemetryFrame> latest(String sessionId) {
        return Optional.ofNullable(latestBySession.get(sessionId));
    }

    public void clear(String sessionId) {
        cancelFlushTask(sessionId);
        lastPublishAtMs.remove(sessionId);
        latestBySession.remove(sessionId);
        pendingBySession.remove(sessionId);
    }

    private void scheduleFlush(String sessionId, long delayMs) {
        flushTasks.compute(sessionId, (id, existing) -> {
            if (existing != null && !existing.isDone()) {
                return existing;
            }
            return scheduler.schedule(
                    () -> {
                        flushTasks.remove(id);
                        TelemetryFrame pending = pendingBySession.remove(id);
                        if (pending != null) {
                            flush(id, pending, System.currentTimeMillis());
                        }
                    },
                    Math.max(1L, delayMs),
                    TimeUnit.MILLISECONDS
            );
        });
    }

    private void cancelFlushTask(String sessionId) {
        ScheduledFuture<?> task = flushTasks.remove(sessionId);
        if (task != null) {
            task.cancel(false);
        }
    }

    private void flush(String sessionId, TelemetryFrame frame, long nowMs) {
        cancelFlushTask(sessionId);
        pendingBySession.remove(sessionId);
        lastPublishAtMs.put(sessionId, nowMs);
        String destination = TOPIC_PREFIX + sessionId;
        messagingTemplate.convertAndSend(destination, frame);
        log.debug("telemetry_publish sessionId={} seq={} destination={}", sessionId, frame.seq(), destination);
    }
}
