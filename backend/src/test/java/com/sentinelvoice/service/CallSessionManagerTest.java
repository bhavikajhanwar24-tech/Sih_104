package com.sentinelvoice.service;

import com.sentinelvoice.audit.AuditLedgerService;
import com.sentinelvoice.config.SentinelProperties;
import com.sentinelvoice.model.AuditBlock;
import com.sentinelvoice.model.ChannelProfile;
import com.sentinelvoice.model.InterventionLevel;
import com.sentinelvoice.model.SessionStartRequest;
import com.sentinelvoice.model.TelemetryEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CallSessionManagerTest {

    private CallSessionManager manager;

    @BeforeEach
    void setUp() {
        SentinelProperties properties = mock(SentinelProperties.class);
        when(properties.session()).thenReturn(new SentinelProperties.Session(30, 2));
        AuditLedgerService auditLedgerService = mock(AuditLedgerService.class);
        when(auditLedgerService.append(any(), any(), any())).thenReturn(new AuditBlock());
        manager = new CallSessionManager(properties, auditLedgerService);
    }

    @Test
    void createSessionGeneratesIdWhenAbsent() {
        var session = manager.createSession(request(null, "cli-1"));
        assertTrue(session.getSessionId() != null && !session.getSessionId().isBlank());
        assertEquals("cli-1", session.getCallerId());
        assertEquals("desk-1", session.getCalleeId());
        assertEquals(ChannelProfile.WEBRTC_WIDEBAND, session.getChannelProfile());
        assertEquals(1, manager.activeSessionCount());
    }

    @Test
    void rejectsBeyondMaxConcurrent() {
        manager.createSession(request("s1", "cli-1"));
        manager.createSession(request("s2", "cli-2"));
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> manager.createSession(request("s3", "cli-3"))
        );
        assertTrue(ex.getMessage().contains("maxConcurrent=2"));
    }

    @Test
    void requireSessionThrowsWhenMissing() {
        assertThrows(NoSuchElementException.class, () -> manager.requireSession("missing"));
    }

    @Test
    void recordTelemetryUpdatesSessionAndEvictionUsesLastFrame() {
        var session = manager.createSession(request("live", "cli-1"));
        manager.recordTelemetry("live", new TelemetryEntry(
                0L,
                Instant.now().toEpochMilli(),
                0.4,
                0.35,
                InterventionLevel.LEVEL_2_SOFT_NUDGE,
                Map.of("voice", 0.4)
        ));
        assertEquals(0.35, session.getSmoothedRisk());
        assertEquals(InterventionLevel.LEVEL_2_SOFT_NUDGE, session.getCurrentLevel());

        session.setLastFrameAt(Instant.now().minus(31, ChronoUnit.MINUTES));
        var evicted = manager.evictIdleSessions();
        assertEquals(1, evicted.size());
        assertEquals("live", evicted.getFirst());
        assertTrue(manager.getSession("live").isEmpty());
        assertEquals(0, manager.activeSessionCount());
    }

    private static SessionStartRequest request(String sessionId, String callerId) {
        return new SessionStartRequest(
                "sentinelvoice.SessionStartRequest/1",
                sessionId,
                callerId,
                "desk-1",
                ChannelProfile.WEBRTC_WIDEBAND,
                null
        );
    }
}
