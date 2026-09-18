package com.sentinelvoice.actuation;

import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.audit.AuditWriteDispatcher;
import com.sentinelvoice.config.SentinelProperties;
import com.sentinelvoice.fusion.FusionEngineTest;
import com.sentinelvoice.model.InterventionLevel;
import com.sentinelvoice.service.ForensicDossierService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ActuationServiceTest {

    private AuditWriteDispatcher audit;
    private ActuationService service;
    private CallControlPort holdCounterPort;

    @BeforeEach
    void setUp() {
        SentinelProperties properties = FusionEngineTest.testProperties();
        audit = mock(AuditWriteDispatcher.class);
        Clock clock = Clock.fixed(Instant.parse("2026-09-18T12:00:00Z"), ZoneOffset.UTC);
        OobMfaService mfa = new OobMfaService(properties, clock);
        CoreBankingWebhookService cbs = mock(CoreBankingWebhookService.class);
        ForensicDossierService dossier = new ForensicDossierService();
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);

        AtomicInteger holdCalls = new AtomicInteger();
        holdCounterPort = new NoopAdapter() {
            @Override
            public ActuationResult hold(String sessionId) {
                holdCalls.incrementAndGet();
                return super.hold(sessionId);
            }
        };
        // stash for L4 assertion via casting in test — use field
        this.holdCalls = holdCalls;

        service = new ActuationService(
                holdCounterPort,
                mfa,
                cbs,
                dossier,
                audit,
                properties,
                messagingProvider(messaging)
        );
    }

    private AtomicInteger holdCalls;

    @Test
    void l2FiresUiBannerOnce_reentryDoesNotRefire() {
        String sessionId = "sess-l2";
        assertThatCode(() -> service.apply(sessionId, InterventionLevel.LEVEL_2_SOFT_NUDGE, false))
                .doesNotThrowAnyException();
        assertThatCode(() -> service.apply(sessionId, InterventionLevel.LEVEL_2_SOFT_NUDGE, false))
                .doesNotThrowAnyException();

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(audit, times(1)).submit(
                eq(sessionId),
                eq(AuditEventType.INTERVENTION_ACTION_FIRED),
                payloadCaptor.capture()
        );
        assertThat(payloadCaptor.getValue().get("action")).isEqualTo("UI_BANNER");
        assertThat(payloadCaptor.getValue().get("result")).isEqualTo("SUCCESS");
        assertThat(payloadCaptor.getValue().get("adapter")).isEqualTo("noop");
        assertThat(payloadCaptor.getValue().get("latencyMs")).isInstanceOf(Number.class);
    }

    @Test
    void l4FiresHoldActions() {
        String sessionId = "sess-l4";
        assertThatCode(() -> service.apply(sessionId, InterventionLevel.LEVEL_4_AUTO_HOLD, false))
                .doesNotThrowAnyException();

        assertThat(holdCalls.get()).isEqualTo(1);

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(audit, atLeastOnce()).submit(
                eq(sessionId),
                eq(AuditEventType.INTERVENTION_ACTION_FIRED),
                payloadCaptor.capture()
        );
        List<String> actions = payloadCaptor.getAllValues().stream()
                .map(p -> String.valueOf(p.get("action")))
                .toList();
        assertThat(actions).contains("CALL_HELD", "ANNOUNCE_HOLD", "WHISPER_WARNING", "SUPERVISOR_BRIDGED");
    }

    @Test
    void unsupportedPathAuditsUnsupported() {
        SentinelProperties properties = FusionEngineTest.testProperties();
        AuditWriteDispatcher localAudit = mock(AuditWriteDispatcher.class);
        CallControlPort emptyCaps = new CallControlPort() {
            @Override
            public ActuationResult hold(String sessionId) {
                return ActuationResult.unsupported("no hold");
            }

            @Override
            public ActuationResult unhold(String sessionId) {
                return ActuationResult.unsupported("no unhold");
            }

            @Override
            public ActuationResult whisperToAgent(String sessionId, String soundId) {
                return ActuationResult.unsupported("no whisper");
            }

            @Override
            public ActuationResult bridgeSupervisor(String sessionId, String supervisorEndpoint) {
                return ActuationResult.unsupported("no bridge");
            }

            @Override
            public ActuationResult announce(String sessionId, String soundId) {
                return ActuationResult.unsupported("no announce");
            }

            @Override
            public ActuationResult terminate(String sessionId, String reason) {
                return ActuationResult.unsupported("no terminate");
            }

            @Override
            public Set<ActuationAction> capabilities() {
                return EnumSet.noneOf(ActuationAction.class);
            }

            @Override
            public String adapterName() {
                return "empty";
            }
        };

        ActuationService limited = new ActuationService(
                emptyCaps,
                new OobMfaService(properties, Clock.systemUTC()),
                mock(CoreBankingWebhookService.class),
                new ForensicDossierService(),
                localAudit,
                properties,
                messagingProvider(mock(SimpMessagingTemplate.class))
        );

        assertThatCode(() -> limited.apply("sess-unsup", InterventionLevel.LEVEL_4_AUTO_HOLD, false))
                .doesNotThrowAnyException();

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(localAudit, atLeastOnce()).submit(
                eq("sess-unsup"),
                eq(AuditEventType.INTERVENTION_ACTION_FIRED),
                payloadCaptor.capture()
        );
        boolean sawUnsupported = payloadCaptor.getAllValues().stream()
                .anyMatch(p -> "UNSUPPORTED".equals(String.valueOf(p.get("result")))
                        && "CALL_HELD".equals(String.valueOf(p.get("action"))));
        assertThat(sawUnsupported).isTrue();
    }

    @Test
    void neverThrowsOnNullOrBrokenAdapter() {
        SentinelProperties properties = FusionEngineTest.testProperties();
        CallControlPort throwing = new CallControlPort() {
            @Override
            public ActuationResult hold(String sessionId) {
                throw new RuntimeException("boom");
            }

            @Override
            public ActuationResult unhold(String sessionId) {
                throw new RuntimeException("boom");
            }

            @Override
            public ActuationResult whisperToAgent(String sessionId, String soundId) {
                throw new RuntimeException("boom");
            }

            @Override
            public ActuationResult bridgeSupervisor(String sessionId, String supervisorEndpoint) {
                throw new RuntimeException("boom");
            }

            @Override
            public ActuationResult announce(String sessionId, String soundId) {
                throw new RuntimeException("boom");
            }

            @Override
            public ActuationResult terminate(String sessionId, String reason) {
                throw new RuntimeException("boom");
            }

            @Override
            public Set<ActuationAction> capabilities() {
                return EnumSet.allOf(ActuationAction.class);
            }

            @Override
            public String adapterName() {
                return "throwing";
            }
        };
        AuditWriteDispatcher brittleAudit = mock(AuditWriteDispatcher.class);
        ActuationService brittle = new ActuationService(
                throwing,
                new OobMfaService(properties, Clock.systemUTC()),
                mock(CoreBankingWebhookService.class),
                new ForensicDossierService(),
                brittleAudit,
                properties,
                messagingProvider(mock(SimpMessagingTemplate.class))
        );
        assertThatCode(() -> brittle.apply("sess-x", InterventionLevel.LEVEL_4_AUTO_HOLD, false))
                .doesNotThrowAnyException();
        assertThatCode(() -> brittle.apply(null, InterventionLevel.LEVEL_2_SOFT_NUDGE, false))
                .doesNotThrowAnyException();
        verify(brittleAudit, atLeastOnce()).submit(anyString(), eq(AuditEventType.INTERVENTION_ACTION_FIRED), anyMap());
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<SimpMessagingTemplate> messagingProvider(SimpMessagingTemplate template) {
        ObjectProvider<SimpMessagingTemplate> provider = mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(template);
        return provider;
    }
}
