package com.sentinelvoice.intervention;

import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.audit.AuditLedgerService;
import com.sentinelvoice.config.SentinelProperties;
import com.sentinelvoice.fusion.FusionEngineTest;
import com.sentinelvoice.model.AuditBlock;
import com.sentinelvoice.model.InterventionLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InterventionStateMachineTest {

    @Mock
    private AuditLedgerService auditLedgerService;

    private InterventionStateMachine fsm;
    private final AtomicInteger auditSeq = new AtomicInteger();

    @BeforeEach
    void setUp() {
        SentinelProperties properties = FusionEngineTest.testProperties();
        lenient().when(auditLedgerService.append(anyString(), any(AuditEventType.class), anyMap()))
                .thenAnswer(invocation -> {
                    AuditBlock block = new AuditBlock();
                    block.setSessionId(invocation.getArgument(0));
                    block.setEventType(invocation.getArgument(1, AuditEventType.class).name());
                    block.setBlockIndex(auditSeq.getAndIncrement());
                    return block;
                });
        fsm = new InterventionStateMachine(properties, auditLedgerService);
    }

    @Test
    void flickerRegression_oscillatingAroundPointFiveFiveProducesAtMostOneTransitionInTenSeconds() {
        String session = "flicker";
        long t0 = 1_000_000L;
        // Establish L2 first: dwell 1s at L1, then escalate with score above L2 threshold.
        fsm.evaluate(session, input(t0, 0.20, false, false));
        InterventionDecision first = fsm.evaluate(session, input(t0 + 1_000L, 0.56, true, false));
        assertThat(first.changed()).isTrue();
        InterventionLevel afterFirst = first.level();

        int furtherChanges = 0;
        for (int i = 1; i <= 20; i++) {
            // Oscillate 0.54 / 0.56 around the L2↔L3 boundary; hysteresis (down at 0.46) blocks return.
            double score = (i % 2 == 0) ? 0.56 : 0.54;
            InterventionDecision d = fsm.evaluate(session, input(t0 + 1_000L + i * 500L, score, true, false));
            if (d.changed()) {
                furtherChanges++;
            }
        }

        assertThat(furtherChanges)
                .as("flicker regression: at most one transition while oscillating around 0.55 for ~10s")
                .isLessThanOrEqualTo(1);
        // Still not bouncing every tick
        assertThat(fsm.currentLevel(session).ordinal()).isGreaterThanOrEqualTo(afterFirst.ordinal());
    }

    @Test
    void dwellEnforced_immediateSecondEscalationIsSuppressed() {
        String session = "dwell";
        long t0 = 2_000_000L;
        fsm.evaluate(session, input(t0, 0.10, false, false));

        InterventionDecision up = fsm.evaluate(session, input(t0 + 1_000L, 0.40, false, false));
        assertThat(up.changed()).isTrue();
        assertThat(up.level()).isEqualTo(InterventionLevel.LEVEL_2_SOFT_NUDGE);

        // Immediate further escalation toward L3 — dwell on L2 is 1s for L2→L3
        InterventionDecision blocked = fsm.evaluate(session, input(t0 + 1_000L + 100L, 0.60, true, false));
        assertThat(blocked.changed()).isFalse();
        assertThat(blocked.dwellRemainingMs()).isGreaterThan(0L);
        assertThat(blocked.level()).isEqualTo(InterventionLevel.LEVEL_2_SOFT_NUDGE);
    }

    @Test
    void deEscalationIsOneLevelAtATime() {
        String session = "down";
        long t = 3_000_000L;
        // Climb to L4 via emergency (ignores dwell)
        InterventionDecision em = fsm.evaluate(session, input(t, 0.20, false, true));
        assertThat(em.level()).isEqualTo(InterventionLevel.LEVEL_4_AUTO_HOLD);

        // Score collapses — must step L4→L3 only (after L4 dwell 15s)
        InterventionDecision down1 = fsm.evaluate(session, input(t + 15_000L, 0.10, false, false));
        assertThat(down1.changed()).isTrue();
        assertThat(down1.level()).isEqualTo(InterventionLevel.LEVEL_3_STEP_UP_MFA);

        InterventionDecision down2 = fsm.evaluate(session, input(t + 15_000L + 8_000L, 0.10, false, false));
        assertThat(down2.level()).isEqualTo(InterventionLevel.LEVEL_2_SOFT_NUDGE);
    }

    @Test
    void l5NeverAutoDeEscalates() {
        String session = "l5";
        long t = 4_000_000L;
        fsm.override(session, InterventionLevel.LEVEL_5_TERMINATE, "analyst-1", "Confirmed terminate after review", t);

        InterventionDecision after = fsm.evaluate(session, input(t + 60_000L, 0.01, false, false));
        assertThat(after.level()).isEqualTo(InterventionLevel.LEVEL_5_TERMINATE);
        assertThat(after.changed()).isFalse();
        assertThat(after.rationale()).containsIgnoringCase("terminal");
    }

    @Test
    void overrideSuppressesAutomaticTransitionsButLogsSuppressedIntent() {
        String session = "ovr";
        long t = 5_000_000L;
        fsm.override(session, InterventionLevel.LEVEL_2_SOFT_NUDGE, "analyst-2", "Holding at nudge for coaching", t);

        InterventionDecision held = fsm.evaluate(session, input(t + 1_000L, 0.95, true, true));
        assertThat(held.changed()).isFalse();
        assertThat(held.level()).isEqualTo(InterventionLevel.LEVEL_2_SOFT_NUDGE);
        assertThat(held.suppressedIntent()).isNotNull();
        assertThat(held.suppressedIntent()).contains("would have");
        verify(auditLedgerService).append(eq(session), eq(AuditEventType.ANALYST_OVERRIDE), anyMap());
    }

    @Test
    void fullL1ToL5EscalationProducesExpectedAuditBlockSequence() {
        String session = "climb";
        long t = 6_000_000L;
        List<Map<String, Object>> riskPayloads = new ArrayList<>();

        when(auditLedgerService.append(eq(session), eq(AuditEventType.RISK_LEVEL_CHANGED), anyMap()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> payload = invocation.getArgument(2);
                    riskPayloads.add(Map.copyOf(payload));
                    AuditBlock block = new AuditBlock();
                    block.setEventType(AuditEventType.RISK_LEVEL_CHANGED.name());
                    block.setBlockIndex(auditSeq.getAndIncrement());
                    return block;
                });
        when(auditLedgerService.append(eq(session), eq(AuditEventType.ANALYST_OVERRIDE), anyMap()))
                .thenAnswer(invocation -> {
                    AuditBlock block = new AuditBlock();
                    block.setEventType(AuditEventType.ANALYST_OVERRIDE.name());
                    block.setBlockIndex(auditSeq.getAndIncrement());
                    return block;
                });

        // L1 → L2 (dwell 1s)
        fsm.evaluate(session, input(t, 0.10, false, false));
        fsm.evaluate(session, input(t + 1_000L, 0.40, false, false));
        // L2 → L3 (dwell 1s + corroboration)
        fsm.evaluate(session, input(t + 2_000L, 0.60, true, false));
        // L3 → L4 (dwell 1s + corroboration)
        fsm.evaluate(session, input(t + 3_000L, 0.80, true, false));
        // L4 → L5 via analyst override (automatic path cannot enter L5 alone)
        fsm.override(session, InterventionLevel.LEVEL_5_TERMINATE, "analyst-3", "Supervisor confirmed terminate", t + 4_000L);

        assertThat(riskPayloads).hasSize(4);
        assertThat(riskPayloads.get(0)).containsEntry("from", "LEVEL_1_SILENT").containsEntry("to", "LEVEL_2_SOFT_NUDGE")
                .containsEntry("trigger", "AUTOMATIC");
        assertThat(riskPayloads.get(1)).containsEntry("from", "LEVEL_2_SOFT_NUDGE").containsEntry("to", "LEVEL_3_STEP_UP_MFA")
                .containsEntry("trigger", "AUTOMATIC");
        assertThat(riskPayloads.get(2)).containsEntry("from", "LEVEL_3_STEP_UP_MFA").containsEntry("to", "LEVEL_4_AUTO_HOLD")
                .containsEntry("trigger", "AUTOMATIC");
        assertThat(riskPayloads.get(3)).containsEntry("from", "LEVEL_4_AUTO_HOLD").containsEntry("to", "LEVEL_5_TERMINATE")
                .containsEntry("trigger", "MANUAL");

        verify(auditLedgerService, atLeast(1)).append(eq(session), eq(AuditEventType.ANALYST_OVERRIDE), anyMap());
    }

    @Test
    void emergencyJumpsToL4IgnoringDwellButNotL5() {
        String session = "em";
        long t = 7_000_000L;
        InterventionDecision d = fsm.evaluate(session, input(t, 0.99, true, true));
        assertThat(d.level()).isEqualTo(InterventionLevel.LEVEL_4_AUTO_HOLD);
        assertThat(d.changed()).isTrue();
        verify(auditLedgerService).append(eq(session), eq(AuditEventType.RISK_LEVEL_CHANGED), anyMap());
    }

    private static InterventionStateMachine.EvaluationInput input(
            long nowMs,
            double smoothed,
            boolean corroboration,
            boolean emergency
    ) {
        return new InterventionStateMachine.EvaluationInput(
                smoothed,
                corroboration,
                corroboration ? List.of("voice", "linguistic") : List.of(),
                emergency,
                false,
                nowMs
        );
    }
}
