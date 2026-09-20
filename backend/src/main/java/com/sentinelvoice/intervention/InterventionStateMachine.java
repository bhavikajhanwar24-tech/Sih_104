package com.sentinelvoice.intervention;

import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.audit.AuditWriteDispatcher;
import com.sentinelvoice.config.SentinelProperties;
import com.sentinelvoice.model.InterventionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Intervention ladder FSM with hysteresis, dwell, emergency bypass, and manual override
 * (Context §9.5).
 */
@Component
public class InterventionStateMachine {

    private static final Logger log = LoggerFactory.getLogger(InterventionStateMachine.class);

    public enum Trigger {
        AUTOMATIC,
        EMERGENCY,
        MANUAL
    }

    public record EvaluationInput(
            double smoothedScore,
            boolean corroborationSatisfied,
            List<String> corroboratingFamilies,
            boolean emergency,
            boolean analystConfirmedForL5,
            long nowMs,
            int policyMinLevel
    ) {
        public EvaluationInput {
            corroboratingFamilies = corroboratingFamilies == null
                    ? List.of()
                    : List.copyOf(corroboratingFamilies);
            if (policyMinLevel < 0) {
                policyMinLevel = 0;
            }
            if (policyMinLevel > 4) {
                policyMinLevel = 4;
            }
        }

        /** Back-compat for callers that do not yet pass a policy floor. */
        public EvaluationInput(
                double smoothedScore,
                boolean corroborationSatisfied,
                List<String> corroboratingFamilies,
                boolean emergency,
                boolean analystConfirmedForL5,
                long nowMs
        ) {
            this(smoothedScore, corroborationSatisfied, corroboratingFamilies,
                    emergency, analystConfirmedForL5, nowMs, 0);
        }
    }

    private final SentinelProperties.Intervention interventionProps;
    private final long overridePinDurationMs;
    private final AuditWriteDispatcher auditWriteDispatcher;
    private final Map<InterventionLevel, TransitionRule> upRules;
    private final Map<InterventionLevel, TransitionRule> downRules;
    private final ConcurrentMap<String, SessionLadderState> sessions = new ConcurrentHashMap<>();

    public InterventionStateMachine(SentinelProperties properties, AuditWriteDispatcher auditWriteDispatcher) {
        this.interventionProps = properties.intervention();
        this.overridePinDurationMs = interventionProps.overridePinDurationMs();
        this.auditWriteDispatcher = auditWriteDispatcher;
        this.upRules = buildUpRules(interventionProps);
        this.downRules = buildDownRules(interventionProps);
    }

    public InterventionDecision evaluate(String sessionId, EvaluationInput input) {
        SessionLadderState state = sessions.computeIfAbsent(sessionId, id -> new SessionLadderState(input.nowMs()));
        synchronized (state) {
            state.lastEvaluatedAtMs = input.nowMs();
            InterventionLevel current = state.currentLevel;

            // L5 is terminal for automatic / emergency paths.
            // Once a call is disconnected and an account frozen, a fluctuating score must never
            // silently undo it — only an explicit audited analyst override may leave L5.
            if (current == InterventionLevel.LEVEL_5_TERMINATE) {
                return InterventionDecision.unchanged(
                        current,
                        0L,
                        "L5_TERMINATE is terminal; automatic de-escalation is forbidden"
                );
            }

            InterventionLevel desired = computeDesiredLevel(
                    input.smoothedScore(),
                    input.corroborationSatisfied(),
                    input.analystConfirmedForL5()
            );
            desired = max(desired, fromPolicyMinLevel(input.policyMinLevel()));

            if (input.emergency()) {
                desired = max(desired, InterventionLevel.LEVEL_4_AUTO_HOLD);
                // Emergency never auto-promotes to L5 — analyst confirmation still required.
                if (desired == InterventionLevel.LEVEL_5_TERMINATE && !input.analystConfirmedForL5()) {
                    desired = InterventionLevel.LEVEL_4_AUTO_HOLD;
                }
            }

            OverrideRecord override = state.override;
            boolean overrideActive = override != null && override.isActive(input.nowMs());
            state.overrideActive = overrideActive;
            if (override != null && !overrideActive) {
                state.override = null;
            }

            if (overrideActive) {
                InterventionLevel wouldBe = desired;
                if (input.emergency()) {
                    wouldBe = max(wouldBe, InterventionLevel.LEVEL_4_AUTO_HOLD);
                }
                if (wouldBe == InterventionLevel.LEVEL_5_TERMINATE && !input.analystConfirmedForL5()) {
                    wouldBe = InterventionLevel.LEVEL_4_AUTO_HOLD;
                }
                if (wouldBe.ordinal() < current.ordinal()) {
                    InterventionLevel oneDown = previous(current);
                    wouldBe = oneDown != null ? oneDown : current;
                }
                String suppressed = null;
                if (wouldBe != current) {
                    suppressed = "would have "
                            + (wouldBe.ordinal() > current.ordinal() ? "escalated" : "de-escalated")
                            + " to " + wouldBe.name();
                    log.info("sessionId={} overrideActive suppressedIntent={}", sessionId, suppressed);
                }
                return new InterventionDecision(
                        current,
                        false,
                        0L,
                        List.of(),
                        "Manual override active until " + override.expiresAtMs() + "; automatic transitions suppressed",
                        suppressed
                );
            }

            if (desired == current) {
                long dwellRemaining = dwellRemainingForPossibleMove(current, desired, input.nowMs(), state);
                return InterventionDecision.unchanged(
                        current,
                        dwellRemaining,
                        "Score holds level " + current.name()
                );
            }

            if (desired.ordinal() > current.ordinal()) {
                return escalate(sessionId, state, current, desired, input);
            }
            return deEscalate(sessionId, state, current, desired, input);
        }
    }

    /**
     * Analyst force to a level. Pins automatic transitions for {@code overridePinDurationMs}.
     */
    public InterventionDecision override(
            String sessionId,
            InterventionLevel targetLevel,
            String analystId,
            String reason,
            long nowMs
    ) {
        if (analystId == null || analystId.isBlank()) {
            throw new IllegalArgumentException("analystId is required for an intervention override");
        }
        if (reason == null || reason.trim().length() < 10) {
            throw new IllegalArgumentException("override reason must be at least 10 characters");
        }
        if (targetLevel == null) {
            throw new IllegalArgumentException("targetLevel is required");
        }

        SessionLadderState state = sessions.computeIfAbsent(sessionId, id -> new SessionLadderState(nowMs));
        synchronized (state) {
            InterventionLevel from = state.currentLevel;
            state.override = new OverrideRecord(
                    targetLevel,
                    analystId.trim(),
                    reason.trim(),
                    nowMs,
                    nowMs + overridePinDurationMs
            );
            state.overrideActive = true;

            Map<String, Object> overridePayload = new LinkedHashMap<>();
            overridePayload.put("from", from.name());
            overridePayload.put("to", targetLevel.name());
            overridePayload.put("analystId", analystId.trim());
            overridePayload.put("reason", reason.trim());
            overridePayload.put("pinnedUntilMs", state.override.expiresAtMs());
            auditWriteDispatcher.submit(sessionId, AuditEventType.ANALYST_OVERRIDE, overridePayload);

            if (from != targetLevel) {
                applyTransition(sessionId, state, from, targetLevel, 0.0, List.of(), Trigger.MANUAL, nowMs);
                return new InterventionDecision(
                        targetLevel,
                        true,
                        0L,
                        actionsFor(targetLevel),
                        "Analyst " + analystId.trim() + " overrode " + from.name() + " → " + targetLevel.name()
                                + ": " + reason.trim(),
                        null
                );
            }
            return InterventionDecision.unchanged(
                    targetLevel,
                    0L,
                    "Analyst " + analystId.trim() + " re-pinned " + targetLevel.name()
            );
        }
    }

    public InterventionLevel currentLevel(String sessionId) {
        SessionLadderState state = sessions.get(sessionId);
        return state == null ? InterventionLevel.LEVEL_1_SILENT : state.currentLevel;
    }

    public void clearSession(String sessionId) {
        sessions.remove(sessionId);
    }

    // -------------------------------------------------------------------------

    private InterventionDecision escalate(
            String sessionId,
            SessionLadderState state,
            InterventionLevel current,
            InterventionLevel desired,
            EvaluationInput input
    ) {
        Trigger trigger = input.emergency() ? Trigger.EMERGENCY : Trigger.AUTOMATIC;
        boolean ignoreDwell = input.emergency();

        TransitionRule leaveRule = upRules.get(current);
        long dwellMs = leaveRule != null ? leaveRule.dwellMs() : 0L;
        long elapsed = input.nowMs() - state.levelEnteredAtMs;
        long remaining = Math.max(0L, dwellMs - elapsed);

        if (!ignoreDwell && remaining > 0L) {
            return InterventionDecision.unchanged(
                    current,
                    remaining,
                    "Dwell " + remaining + "ms remaining before escalation from " + current.name()
            );
        }

        InterventionLevel target = desired;
        // Cap automatic/emergency at L4 unless analyst confirmed L5.
        if (target == InterventionLevel.LEVEL_5_TERMINATE && !input.analystConfirmedForL5()) {
            target = InterventionLevel.LEVEL_4_AUTO_HOLD;
        }
        if (target.ordinal() <= current.ordinal()) {
            return InterventionDecision.unchanged(current, 0L, "No higher reachable level");
        }

        applyTransition(
                sessionId,
                state,
                current,
                target,
                input.smoothedScore(),
                input.corroboratingFamilies(),
                trigger,
                input.nowMs()
        );
        String rationale = trigger == Trigger.EMERGENCY
                ? "Emergency bypass → " + target.name() + " (dwell ignored; L5 still needs analyst confirm)"
                : "Escalated " + current.name() + " → " + target.name()
                + " (smoothed=" + input.smoothedScore() + ")";
        return new InterventionDecision(target, true, 0L, actionsFor(target), rationale, null);
    }

    private InterventionDecision deEscalate(
            String sessionId,
            SessionLadderState state,
            InterventionLevel current,
            InterventionLevel desired,
            EvaluationInput input
    ) {
        // De-escalation is ONE LEVEL AT A TIME.
        InterventionLevel oneDown = previous(current);
        if (oneDown == null || oneDown.ordinal() < desired.ordinal()) {
            // desired is somehow inconsistent; no-op
            return InterventionDecision.unchanged(current, 0L, "No lower level available");
        }

        TransitionRule downRule = downRules.get(current);
        if (downRule == null) {
            return InterventionDecision.unchanged(current, 0L, "No down-transition from " + current.name());
        }

        long elapsed = input.nowMs() - state.levelEnteredAtMs;
        long remaining = Math.max(0L, downRule.dwellMs() - elapsed);
        if (remaining > 0L) {
            return InterventionDecision.unchanged(
                    current,
                    remaining,
                    "Dwell " + remaining + "ms remaining before de-escalation from " + current.name()
            );
        }

        // Hysteresis: smoothed must be <= down threshold for leaving current downward.
        if (input.smoothedScore() > downRule.threshold()) {
            return InterventionDecision.unchanged(
                    current,
                    0L,
                    "Hysteresis: smoothed " + input.smoothedScore() + " still above down-threshold "
                            + downRule.threshold()
            );
        }

        applyTransition(
                sessionId,
                state,
                current,
                oneDown,
                input.smoothedScore(),
                input.corroboratingFamilies(),
                Trigger.AUTOMATIC,
                input.nowMs()
            );
        return new InterventionDecision(
                oneDown,
                true,
                0L,
                actionsFor(oneDown),
                "De-escalated one level " + current.name() + " → " + oneDown.name(),
                null
        );
    }

    private void applyTransition(
            String sessionId,
            SessionLadderState state,
            InterventionLevel from,
            InterventionLevel to,
            double smoothedScore,
            List<String> corroboratingFamilies,
            Trigger trigger,
            long nowMs
    ) {
        state.currentLevel = to;
        state.levelEnteredAtMs = nowMs;
        state.lastEvaluatedAtMs = nowMs;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from", from.name());
        payload.put("to", to.name());
        payload.put("smoothedScore", smoothedScore);
        payload.put("corroboratingFamilies", corroboratingFamilies);
        payload.put("trigger", trigger.name());
        auditWriteDispatcher.submit(sessionId, AuditEventType.RISK_LEVEL_CHANGED, payload);
    }

    /**
     * Highest level whose entry conditions are met given score + corroboration + L5 confirm.
     */
    InterventionLevel computeDesiredLevel(
            double smoothed,
            boolean corroborationSatisfied,
            boolean analystConfirmedForL5
    ) {
        // Walk from highest down so skip-escalation picks the max reachable.
        if (smoothed >= upRules.get(InterventionLevel.LEVEL_4_AUTO_HOLD).threshold()
                && corroborationSatisfied
                && analystConfirmedForL5) {
            return InterventionLevel.LEVEL_5_TERMINATE;
        }
        if (smoothed >= upRules.get(InterventionLevel.LEVEL_3_STEP_UP_MFA).threshold()
                && corroborationSatisfied) {
            // L3→L4 threshold
            return InterventionLevel.LEVEL_4_AUTO_HOLD;
        }
        if (smoothed >= upRules.get(InterventionLevel.LEVEL_2_SOFT_NUDGE).threshold()
                && corroborationSatisfied) {
            return InterventionLevel.LEVEL_3_STEP_UP_MFA;
        }
        if (smoothed >= upRules.get(InterventionLevel.LEVEL_1_SILENT).threshold()) {
            return InterventionLevel.LEVEL_2_SOFT_NUDGE;
        }
        return InterventionLevel.LEVEL_1_SILENT;
    }

    private long dwellRemainingForPossibleMove(
            InterventionLevel current,
            InterventionLevel desired,
            long nowMs,
            SessionLadderState state
    ) {
        TransitionRule rule = desired.ordinal() > current.ordinal()
                ? upRules.get(current)
                : downRules.get(current);
        if (rule == null) {
            return 0L;
        }
        long elapsed = nowMs - state.levelEnteredAtMs;
        return Math.max(0L, rule.dwellMs() - elapsed);
    }

    static List<String> actionsFor(InterventionLevel level) {
        return switch (level) {
            case LEVEL_1_SILENT -> List.of("LOG_ONLY");
            case LEVEL_2_SOFT_NUDGE -> List.of("SOFT_WARNING", "REQUEST_CALLBACK");
            case LEVEL_3_STEP_UP_MFA -> List.of("STEP_UP_MFA", "TXN_APPROVE_LOCKED");
            case LEVEL_4_AUTO_HOLD -> List.of("CALL_HELD", "OOB_MFA_SENT", "SUPERVISOR_BRIDGED", "TXN_APPROVE_LOCKED");
            case LEVEL_5_TERMINATE -> List.of("CALL_TERMINATED", "FRAUD_TEAM_ESCALATED", "ACCOUNT_FREEZE");
        };
    }

    private static InterventionLevel previous(InterventionLevel level) {
        int idx = level.ordinal() - 1;
        return idx >= 0 ? InterventionLevel.values()[idx] : null;
    }

    private static InterventionLevel fromPolicyMinLevel(int policyMinLevel) {
        return switch (policyMinLevel) {
            case 2 -> InterventionLevel.LEVEL_2_SOFT_NUDGE;
            case 3 -> InterventionLevel.LEVEL_3_STEP_UP_MFA;
            case 4 -> InterventionLevel.LEVEL_4_AUTO_HOLD;
            default -> InterventionLevel.LEVEL_1_SILENT;
        };
    }

    private static InterventionLevel max(InterventionLevel a, InterventionLevel b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    private static Map<InterventionLevel, TransitionRule> buildUpRules(SentinelProperties.Intervention p) {
        Map<InterventionLevel, TransitionRule> map = new EnumMap<>(InterventionLevel.class);
        map.put(InterventionLevel.LEVEL_1_SILENT, new TransitionRule(
                InterventionLevel.LEVEL_1_SILENT, InterventionLevel.LEVEL_2_SOFT_NUDGE,
                TransitionRule.Direction.UP, p.l1ToL2().upThreshold(), p.l1ToL2().dwellMs(),
                false, false));
        map.put(InterventionLevel.LEVEL_2_SOFT_NUDGE, new TransitionRule(
                InterventionLevel.LEVEL_2_SOFT_NUDGE, InterventionLevel.LEVEL_3_STEP_UP_MFA,
                TransitionRule.Direction.UP, p.l2ToL3().upThreshold(), p.l2ToL3().dwellMs(),
                true, false));
        map.put(InterventionLevel.LEVEL_3_STEP_UP_MFA, new TransitionRule(
                InterventionLevel.LEVEL_3_STEP_UP_MFA, InterventionLevel.LEVEL_4_AUTO_HOLD,
                TransitionRule.Direction.UP, p.l3ToL4().upThreshold(), p.l3ToL4().dwellMs(),
                true, false));
        map.put(InterventionLevel.LEVEL_4_AUTO_HOLD, new TransitionRule(
                InterventionLevel.LEVEL_4_AUTO_HOLD, InterventionLevel.LEVEL_5_TERMINATE,
                TransitionRule.Direction.UP, p.l4ToL5().upThreshold(), p.l4ToL5().dwellMs(),
                true, true));
        return Map.copyOf(map);
    }

    private static Map<InterventionLevel, TransitionRule> buildDownRules(SentinelProperties.Intervention p) {
        Map<InterventionLevel, TransitionRule> map = new EnumMap<>(InterventionLevel.class);
        map.put(InterventionLevel.LEVEL_2_SOFT_NUDGE, new TransitionRule(
                InterventionLevel.LEVEL_2_SOFT_NUDGE, InterventionLevel.LEVEL_1_SILENT,
                TransitionRule.Direction.DOWN, p.l2ToL1().downThreshold(), p.l2ToL1().dwellMs(),
                false, false));
        map.put(InterventionLevel.LEVEL_3_STEP_UP_MFA, new TransitionRule(
                InterventionLevel.LEVEL_3_STEP_UP_MFA, InterventionLevel.LEVEL_2_SOFT_NUDGE,
                TransitionRule.Direction.DOWN, p.l3ToL2().downThreshold(), p.l3ToL2().dwellMs(),
                false, false));
        map.put(InterventionLevel.LEVEL_4_AUTO_HOLD, new TransitionRule(
                InterventionLevel.LEVEL_4_AUTO_HOLD, InterventionLevel.LEVEL_3_STEP_UP_MFA,
                TransitionRule.Direction.DOWN, p.l4ToL3().downThreshold(), p.l4ToL3().dwellMs(),
                false, false));
        // No automatic down-rule from L5 — terminal.
        return Map.copyOf(map);
    }

    static final class SessionLadderState {
        InterventionLevel currentLevel = InterventionLevel.LEVEL_1_SILENT;
        long levelEnteredAtMs;
        long lastEvaluatedAtMs;
        boolean overrideActive;
        OverrideRecord override;

        SessionLadderState(long nowMs) {
            this.levelEnteredAtMs = nowMs;
            this.lastEvaluatedAtMs = nowMs;
        }
    }
}
