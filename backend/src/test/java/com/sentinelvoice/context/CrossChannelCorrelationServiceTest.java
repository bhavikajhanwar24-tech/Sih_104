package com.sentinelvoice.context;

import com.sentinelvoice.config.SentinelProperties;
import com.sentinelvoice.context.model.CorrelationResult;
import com.sentinelvoice.context.model.CrossChannelEvent;
import com.sentinelvoice.fusion.FusionEngineTest;
import com.sentinelvoice.identity.DirectoryService;
import com.sentinelvoice.model.CallSession;
import com.sentinelvoice.model.ChannelProfile;
import com.sentinelvoice.repository.CrossChannelEventRepository;
import com.sentinelvoice.service.CallSessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CrossChannelCorrelationServiceTest {

    @Mock
    CrossChannelEventRepository repository;
    @Mock
    CallSessionManager sessions;
    @Mock
    DirectoryService directory;

    private CrossChannelCorrelationService service;
    private final Instant now = Instant.parse("2026-09-18T12:00:00Z");

    @BeforeEach
    void setUp() {
        SentinelProperties props = FusionEngineTest.testProperties();
        service = new CrossChannelCorrelationService(
                repository, sessions, directory, props, Clock.fixed(now, ZoneOffset.UTC)
        );
    }

    @Test
    void emptyWhenNoEvents() {
        when(repository.findByTargetEmployeeIdAndOccurredAtGreaterThanEqualOrderByOccurredAtAsc(
                eq("EMP-50040"), any()
        )).thenReturn(List.of());

        CorrelationResult result = service.correlateEmployee("EMP-50040", 48);
        assertThat(result.events()).isEmpty();
        assertThat(result.correlationScore()).isZero();
        assertThat(result.matchingCampaign()).isFalse();
    }

    @Test
    void matchingCampaignRaisesScoreAndFlag() {
        CrossChannelEvent email = event(
                CrossChannelEvent.Channel.EMAIL,
                now.minusSeconds(36 * 3600L),
                "Rajesh Kumar <cfo@phish.example>",
                "BEC-CFO-2026-09"
        );
        CrossChannelEvent sms = event(
                CrossChannelEvent.Channel.SMS,
                now.minusSeconds(4 * 3600L),
                "Rajesh Kumar via +91-98XXX",
                "BEC-CFO-2026-09"
        );
        when(repository.findByTargetEmployeeIdAndOccurredAtGreaterThanEqualOrderByOccurredAtAsc(
                eq("EMP-50040"), any()
        )).thenReturn(List.of(email, sms));

        CorrelationResult result = service.correlateEmployee("EMP-50040", 48);
        assertThat(result.eventCount()).isEqualTo(2);
        assertThat(result.matchingCampaign()).isTrue();
        assertThat(result.correlationScore()).isGreaterThan(0.7);

        double graphOnly = 0.60;
        double blended = service.blendRelationshipScore(graphOnly, result);
        assertThat(blended).isGreaterThan(graphOnly);
    }

    @Test
    void cfoWireScenarioResolvesSunita() {
        CallSession session = new CallSession(
                "sid", "+91-unreg", "desk-1", ChannelProfile.WEBRTC_WIDEBAND, "cfo-wire-inr"
        );
        when(sessions.requireSession("sid")).thenReturn(session);
        when(repository.findByTargetEmployeeIdAndOccurredAtGreaterThanEqualOrderByOccurredAtAsc(
                eq(CrossChannelCorrelationService.DEMO_CALLEE_EMPLOYEE_ID), any()
        )).thenReturn(List.of());

        CorrelationResult result = service.correlateSession("sid", 48);
        assertThat(result.targetEmployeeId())
                .isEqualTo(CrossChannelCorrelationService.DEMO_CALLEE_EMPLOYEE_ID);
        assertThat(result.events()).isEmpty();
    }

    private static CrossChannelEvent event(
            CrossChannelEvent.Channel channel,
            Instant at,
            String indicator,
            String campaignId
    ) {
        CrossChannelEvent e = new CrossChannelEvent();
        e.setId(java.util.UUID.randomUUID().toString());
        e.setChannel(channel);
        e.setTargetEmployeeId("EMP-50040");
        e.setOccurredAt(at);
        e.setSeverity(CrossChannelEvent.Severity.HIGH);
        e.setIndicator(indicator);
        e.setCampaignId(campaignId);
        e.setDescription("test");
        return e;
    }
}
