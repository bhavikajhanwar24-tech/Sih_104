package com.sentinelvoice.intervention;

import com.sentinelvoice.model.InterventionLevel;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Facade over {@link InterventionStateMachine}. Prefer {@link #evaluate} / {@link #override};
 * {@link #resolve(double)} remains as a stateless hint for legacy callers.
 */
@Service
public class InterventionLadderService {

    private final InterventionStateMachine stateMachine;

    public InterventionLadderService(InterventionStateMachine stateMachine) {
        this.stateMachine = stateMachine;
    }

    public InterventionDecision evaluate(String sessionId, InterventionStateMachine.EvaluationInput input) {
        return stateMachine.evaluate(sessionId, input);
    }

    public InterventionDecision override(
            String sessionId,
            InterventionLevel targetLevel,
            String analystId,
            String reason,
            long nowMs
    ) {
        return stateMachine.override(sessionId, targetLevel, analystId, reason, nowMs);
    }

    public InterventionLevel currentLevel(String sessionId) {
        return stateMachine.currentLevel(sessionId);
    }

    /**
     * Stateless threshold snapshot (no dwell/hysteresis). Prefer {@link #evaluate} in the live path.
     * Assumes corroboration is satisfied so the hint reflects the score ceiling; L5 still needs confirm.
     */
    public InterventionLevel resolve(double riskScore) {
        return stateMachine.computeDesiredLevel(riskScore, true, false);
    }

    public Map<String, Object> explain(InterventionLevel level) {
        Map<String, Object> explanation = new LinkedHashMap<>();
        explanation.put("level", level.name());
        explanation.put("actions", InterventionStateMachine.actionsFor(level));
        explanation.put("action", switch (level) {
            case LEVEL_1_SILENT -> "Log and continue monitoring.";
            case LEVEL_2_SOFT_NUDGE -> "Display soft warning and request verification callback.";
            case LEVEL_3_STEP_UP_MFA -> "Require step-up authentication before transferring funds.";
            case LEVEL_4_AUTO_HOLD -> "Pause transaction completion and alert supervisor.";
            case LEVEL_5_TERMINATE -> "Terminate call and escalate to fraud response team.";
        });
        return Collections.unmodifiableMap(explanation);
    }
}
