package com.sentinelvoice.actuation;

import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.audit.AuditWriteDispatcher;
import com.sentinelvoice.config.SentinelProperties;
import com.sentinelvoice.fusion.FusionEngineTest;
import com.sentinelvoice.model.InterventionLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActuationServiceTest {

    private CallControlPort port;
    private OobMfaService oobMfa;
    private CoreBankingWebhookService cbs;
    private AuditWriteDispatcher audit;
    private ActuationService service;
    private final AtomicInteger holdCalls = new AtomicInteger();

    @BeforeEach
    void setUp() {
        port = mock(CallControlPort.class);
        when(port.adapterName()).thenReturn("noop");
        when(port.capabilities()).thenReturn(EnumSet.of(
                ActuationAction.HOLD,
                ActuationAction.UNHOLD,
                ActuationAction.WHISPER,
                ActuationAction.ANNOUNCE,
                ActuationAction.BRIDGE_SUPERVISOR,
                ActuationAction.TERMINATE
        ));
        org.mockito.Mockito.doAnswer(inv -> {
            holdCalls.incrementAndGet();
            return null;
        }).when(port).hold(anyString());

        oobMfa = mock(OobMfaService.class);
        cbs = mock(CoreBankingWebhookService.class);
        when(cbs.freezeBeneficiary(anyString(), anyString(), anyString())).thenReturn(true);
        audit = mock(AuditWriteDispatcher.class);
        SentinelProperties props = FusionEngineTest.testProperties();
        Clock clock = Clock.fixed(Instant.parse("2026-09-18T12:00:00Z"), ZoneOffset.UTC);
        // Run sync on calling thread so assertions see results immediately.
        Executor sync = Runnable::run;
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<com.sentinelvoice.forensics.ForensicDossierService> dossiers =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(dossiers.getIfAvailable()).thenReturn(null);
        service = new ActuationService(port, oobMfa, cbs, audit, sync, clock, props, dossiers);
    }

    @Test
    void level2FiresUiBannerOnly() {
        service.applySync("s1", InterventionLevel.LEVEL_2_SOFT_NUDGE);
        assertThat(service.firedActions("s1")).containsExactly(ActuationAction.UI_BANNER);
        verify(port, never()).hold(anyString());
        verify(audit).submit(eq("s1"), eq(AuditEventType.INTERVENTION_ACTION_FIRED), anyMap());
    }

    @Test
    void level3SendsOobMfaAndLocksTxn() {
        service.applySync("s1", InterventionLevel.LEVEL_3_STEP_UP_MFA);
        assertThat(service.firedActions("s1")).containsExactlyInAnyOrder(
                ActuationAction.TXN_APPROVE_LOCKED,
                ActuationAction.OOB_MFA_SENT
        );
        verify(oobMfa).sendChallenge("s1");
    }

    @Test
    void level4HoldsAndBridgesSupervisor() {
        service.applySync("s1", InterventionLevel.LEVEL_4_AUTO_HOLD);
        assertThat(service.firedActions("s1")).contains(
                ActuationAction.CALL_HELD,
                ActuationAction.SUPERVISOR_BRIDGED,
                ActuationAction.TXN_APPROVE_LOCKED
        );
        verify(port).hold("s1");
        verify(port).bridgeSupervisor(eq("s1"), anyString());
        assertThat(holdCalls.get()).isEqualTo(1);
    }

    @Test
    void holdIsIdempotentAcrossReentry() {
        service.applySync("s1", InterventionLevel.LEVEL_4_AUTO_HOLD);
        service.applySync("s1", InterventionLevel.LEVEL_3_STEP_UP_MFA); // de-escalation path still tracks fired
        service.applySync("s1", InterventionLevel.LEVEL_4_AUTO_HOLD);
        assertThat(holdCalls.get()).isEqualTo(1);
        verify(port, times(1)).hold("s1");
    }

    @Test
    void level5TerminatesAndFreezes() {
        service.applySync("s1", InterventionLevel.LEVEL_5_TERMINATE);
        assertThat(service.firedActions("s1")).contains(
                ActuationAction.CALL_TERMINATED,
                ActuationAction.BENEFICIARY_FROZEN,
                ActuationAction.DOSSIER_GENERATED
        );
        verify(port).terminate(eq("s1"), eq("LEVEL_5_TERMINATE"));
        verify(cbs).freezeBeneficiary(eq("s1"), anyString(), anyString());
    }

    @Test
    void unsupportedCapabilityIsAuditedAndDoesNotThrow() {
        when(port.capabilities()).thenReturn(EnumSet.noneOf(ActuationAction.class));
        assertThatCode(() -> service.applySync("s1", InterventionLevel.LEVEL_4_AUTO_HOLD))
                .doesNotThrowAnyException();
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(audit, org.mockito.Mockito.atLeastOnce())
                .submit(eq("s1"), eq(AuditEventType.INTERVENTION_ACTION_FIRED), captor.capture());
        assertThat(captor.getAllValues()).anySatisfy(payload ->
                assertThat(payload.get("result")).isEqualTo("UNSUPPORTED")
        );
        verify(port, never()).hold(anyString());
    }

    @Test
    void noopAdapterNeverCrashesFullLadder() {
        NoopAdapter noop = new NoopAdapter();
        SentinelProperties props = FusionEngineTest.testProperties();
        ActuationService noopService = new ActuationService(
                noop,
                oobMfa,
                cbs,
                audit,
                Runnable::run,
                Clock.systemUTC(),
                props,
                dossiersProvider()
        );
        assertThatCode(() -> {
            noopService.applySync("demo", InterventionLevel.LEVEL_2_SOFT_NUDGE);
            noopService.applySync("demo", InterventionLevel.LEVEL_3_STEP_UP_MFA);
            noopService.applySync("demo", InterventionLevel.LEVEL_4_AUTO_HOLD);
            noopService.applySync("demo", InterventionLevel.LEVEL_5_TERMINATE);
        }).doesNotThrowAnyException();
        assertThat(noopService.firedActions("demo")).contains(
                ActuationAction.UI_BANNER,
                ActuationAction.CALL_HELD,
                ActuationAction.CALL_TERMINATED
        );
    }

    @Test
    void actionsForLevelMatchesSpec() {
        assertThat(ActuationService.actionsForLevel(InterventionLevel.LEVEL_2_SOFT_NUDGE))
                .isEqualTo(Set.of(ActuationAction.UI_BANNER));
        assertThat(ActuationService.actionsForLevel(InterventionLevel.LEVEL_3_STEP_UP_MFA))
                .containsExactlyInAnyOrder(ActuationAction.TXN_APPROVE_LOCKED, ActuationAction.OOB_MFA_SENT);
        assertThat(ActuationService.actionsForLevel(InterventionLevel.LEVEL_4_AUTO_HOLD))
                .containsExactlyInAnyOrder(
                        ActuationAction.CALL_HELD,
                        ActuationAction.SUPERVISOR_BRIDGED,
                        ActuationAction.TXN_APPROVE_LOCKED
                );
        assertThat(ActuationService.actionsForLevel(InterventionLevel.LEVEL_5_TERMINATE))
                .containsExactlyInAnyOrder(
                        ActuationAction.CALL_TERMINATED,
                        ActuationAction.BENEFICIARY_FROZEN,
                        ActuationAction.DOSSIER_GENERATED
                );
    }

    @SuppressWarnings("unchecked")
    private static org.springframework.beans.factory.ObjectProvider<com.sentinelvoice.forensics.ForensicDossierService> dossiersProvider() {
        org.springframework.beans.factory.ObjectProvider<com.sentinelvoice.forensics.ForensicDossierService> provider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }
}
