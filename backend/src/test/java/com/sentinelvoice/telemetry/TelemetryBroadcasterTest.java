package com.sentinelvoice.telemetry;

import com.sentinelvoice.model.TelemetryFrame;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class TelemetryBroadcasterTest {

    private final List<TelemetryFrame> sent = new CopyOnWriteArrayList<>();
    private TelemetryBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        doAnswer(invocation -> {
            sent.add(invocation.getArgument(1));
            // Simulate slow consumer so the queue backs up.
            Thread.sleep(30);
            return null;
        }).when(template).convertAndSend(anyString(), any(Object.class));
        broadcaster = new TelemetryBroadcaster(template, new SimpleMeterRegistry());
        sent.clear();
    }

    @Test
    void dropsOldestWhenOutboundQueueFull() throws Exception {
        String sessionId = "bp-1";
        for (int seq = 1; seq <= 10; seq++) {
            broadcaster.publish(frame(sessionId, seq));
        }

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            broadcaster.flushPending(sessionId);
            if (!sent.isEmpty() && sent.getLast().seq() == 10) {
                break;
            }
            Thread.sleep(20L);
        }

        List<Integer> seqs = sent.stream().map(TelemetryFrame::seq).toList();
        assertThat(seqs).isNotEmpty();
        assertThat(seqs.getLast()).isEqualTo(10);
        assertThat(seqs).isSorted();
        // At least one oldest frame was dropped from the bounded queue (capacity 4).
        assertThat(seqs).doesNotContain(1, 2);
        assertThat(seqs.size()).isLessThan(10);
    }

    private static TelemetryFrame frame(String sessionId, int seq) {
        TelemetryFrame.FamilyScore unavailable = new TelemetryFrame.FamilyScore(0, 0, 0, false);
        return new TelemetryFrame(
                TelemetryFrame.SCHEMA,
                sessionId,
                seq,
                1_000L + seq,
                seq * 10L,
                new TelemetryFrame.Risk(0.1, 0.1, "STABLE", "ACTIVE"),
                new TelemetryFrame.Families(
                        unavailable, unavailable, unavailable, unavailable, unavailable, unavailable
                ),
                new TelemetryFrame.Corroboration(List.of(), 2, false),
                new TelemetryFrame.Intervention("LEVEL_1_SILENT", "LEVEL_1_SILENT", 0L, 0L, null, List.of()),
                new TelemetryFrame.Identity(
                        "cli", "UNKNOWN", null, null, null, null, false,
                        new TelemetryFrame.VoicePassport(false, 0.0, "INCONCLUSIVE"), null
                ),
                List.of(),
                new TelemetryFrame.TranscriptDelta(0L, "", List.of()),
                "hash-" + seq
        );
    }
}
