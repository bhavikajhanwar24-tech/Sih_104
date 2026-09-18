package com.sentinelvoice.actuation;

import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.audit.AuditLedgerService;
import com.sentinelvoice.audit.AuditWriteDispatcher;
import com.sentinelvoice.config.SentinelProperties;
import com.sentinelvoice.fusion.FusionEngineTest;
import com.sentinelvoice.intervention.InterventionLadderService;
import com.sentinelvoice.intervention.InterventionStateMachine;
import com.sentinelvoice.model.AuditBlock;
import com.sentinelvoice.model.ChannelProfile;
import com.sentinelvoice.model.InterventionLevel;
import com.sentinelvoice.model.SessionStartRequest;
import com.sentinelvoice.model.TelemetryEntry;
import com.sentinelvoice.service.CallSessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionLockServiceTest {

    @Mock
    private AuditLedgerService auditLedgerService;
    @Mock
    private AuditWriteDispatcher auditWriteDispatcher;

    private CallSessionManager sessions;
    private InterventionLadderService ladder;
    private TransactionLockService lockService;

    @BeforeEach
    void setUp() {
        SentinelProperties props = FusionEngineTest.testProperties();
        when(auditLedgerService.append(anyString(), any(AuditEventType.class), anyMap()))
                .thenReturn(new AuditBlock());
        sessions = new CallSessionManager(props, auditLedgerService);
        ladder = new InterventionLadderService(new InterventionStateMachine(props, auditWriteDispatcher));
        lockService = new TransactionLockService(sessions, ladder, auditLedgerService);
    }

    @Test
    void approveAtL1SucceedsAndAudits() {
        String sid = "txn-l1";
        sessions.createSession(start(sid));
        Map<String, Object> result = lockService.approve(sid, "teller-1");
        assertThat(result.get("status")).isEqualTo("APPROVED");
        verify(auditLedgerService, atLeastOnce()).append(
                eq(sid), eq(AuditEventType.INTERVENTION_ACTION_FIRED), anyMap()
        );
    }

    @Test
    void approveAtL3Returns423AndAuditsBlocked() {
        String sid = "txn-l3";
        sessions.createSession(start(sid));
        long t = System.currentTimeMillis();
        ladder.override(sid, InterventionLevel.LEVEL_3_STEP_UP_MFA, "analyst-1",
                "Escalating for step-up MFA demo", t);
        sessions.requireSession(sid).recordTelemetry(new TelemetryEntry(
                1, t, 0.6, 0.6, InterventionLevel.LEVEL_3_STEP_UP_MFA, Map.of()
        ));

        assertThatThrownBy(() -> lockService.approve(sid, "teller-1"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.LOCKED));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditLedgerService, atLeastOnce()).append(
                eq(sid), eq(AuditEventType.INTERVENTION_ACTION_FIRED), payload.capture()
        );
        assertThat(payload.getValue().get("locked")).isEqualTo(true);
        assertThat(payload.getValue().get("outcome")).isEqualTo("BLOCKED");
    }

    private static SessionStartRequest start(String id) {
        return new SessionStartRequest(
                "sentinelvoice.SessionStartRequest/1",
                id,
                "cli",
                "desk",
                ChannelProfile.WEBRTC_WIDEBAND,
                null
        );
    }
}
