package com.sentinelvoice.telemetry;

import com.sentinelvoice.model.CallSession;
import com.sentinelvoice.model.TelemetryFrame;
import com.sentinelvoice.service.CallSessionManager;
import com.sentinelvoice.telephony.LiveCallsBroadcaster;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TelemetryBroadcasterTest {

    private final List<TelemetryFrame> sent = new CopyOnWriteArrayList<>();
    private TelemetryBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        doAnswer(invocation -> {
            sent.add(invocation.getArgument(1));
            Thread.sleep(30);
            return null;
        }).when(template).convertAndSend(anyString(), any(Object.class));

        CallSessionManager sessions = mock(CallSessionManager.class);
        when(sessions.getSession(anyString())).thenAnswer(inv -> {
            String id = inv.getArgument(0);
            CallSession s = mock(CallSession.class);
            when(s.getSessionId()).thenReturn(id);
            when(s.getTenantId()).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000001"));
            return Optional.of(s);
        });

        broadcaster = new TelemetryBroadcaster(
                template,
                sessions,
                mock(LiveCallsBroadcaster.class),
                new SimpleMeterRegistry(),
                false
        );
        sent.clear();
    }

    @Test
    void dropsOldestWhenOutboundQueueFull() throws Exception {
        String sessionId = "bp-1";
        for (int seq = 1; seq <= 10; seq++) {
            broadcaster.publish(frame(sessionId, seq, ""));
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
        assertThat(seqs).doesNotContain(1, 2);
        assertThat(seqs.size()).isLessThan(10);
    }

    @Test
    void liveStompStripsTranscriptTextWhenNotLab() throws Exception {
        broadcaster.publish(frame("tx-1", 1, "secret account 123456789"));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline && sent.isEmpty()) {
            broadcaster.flushPending("tx-1");
            Thread.sleep(20L);
        }
        assertThat(sent).isNotEmpty();
        assertThat(sent.getFirst().transcriptDelta().text()).isEmpty();
        assertThat(broadcaster.latest("tx-1")).isPresent();
        assertThat(broadcaster.latest("tx-1").orElseThrow().transcriptDelta().text())
                .isEqualTo("secret account 123456789");
    }

    private static TelemetryFrame frame(String sessionId, int seq, String transcript) {
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
                new TelemetryFrame.TranscriptDelta(0L, transcript, List.of()),
                "hash-" + seq
        );
    }
}
