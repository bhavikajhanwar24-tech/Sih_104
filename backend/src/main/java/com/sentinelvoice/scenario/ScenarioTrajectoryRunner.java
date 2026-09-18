package com.sentinelvoice.scenario;

import com.sentinelvoice.intervention.InterventionDecision;
import com.sentinelvoice.intervention.InterventionLadderService;
import com.sentinelvoice.intervention.InterventionStateMachine;
import com.sentinelvoice.model.InterventionLevel;
import com.sentinelvoice.scenario.model.Scenario;
import com.sentinelvoice.scenario.model.ScenarioSessionDescriptor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Drives the intervention ladder along a scenario's expected trajectory for
 * integration tests and offline rehearsal (no ML audio required).
 */
@Service
public class ScenarioTrajectoryRunner {

    private final ScenarioService scenarioService;
    private final InterventionLadderService interventionLadderService;
    private final InterventionStateMachine stateMachine;

    public ScenarioTrajectoryRunner(
            ScenarioService scenarioService,
            InterventionLadderService interventionLadderService,
            InterventionStateMachine stateMachine
    ) {
        this.scenarioService = scenarioService;
        this.interventionLadderService = interventionLadderService;
        this.stateMachine = stateMachine;
    }

    public record TrajectoryResult(
            String scenarioId,
            String sessionId,
            List<InterventionLevel> observedLevels,
            InterventionLevel finalLevel,
            InterventionLevel expectedFinalLevel
    ) {
    }

    /**
     * Load scenario seeds, then evaluate each trajectory point with enough dwell
     * time for hysteresis so the ladder can escalate to the authored levels.
     */
    public TrajectoryResult run(String scenarioId) {
        Scenario scenario = scenarioService.require(scenarioId);
        ScenarioSessionDescriptor descriptor = scenarioService.load(scenarioId, "replay");
        String sessionId = descriptor.sessionId();
        stateMachine.clearSession(sessionId);

        List<InterventionLevel> observed = new ArrayList<>();
        long nowMs = 1_000_000L;
        InterventionLevel last = InterventionLevel.LEVEL_1_SILENT;

        for (Scenario.TrajectoryPoint point : scenario.expectedTrajectory()) {
            // Satisfy the longest up-dwell (L1→L2 = 1000 ms; pad generously between points).
            nowMs += 20_000L;
            InterventionDecision decision = interventionLadderService.evaluate(
                    sessionId,
                    new InterventionStateMachine.EvaluationInput(
                            point.risk(),
                            point.corroboration(),
                            point.corroboration()
                                    ? List.of("linguistic", "transaction", "relationship")
                                    : List.of(),
                            false,
                            point.isConfirmL5(),
                            nowMs
                    )
            );
            last = decision.level();
            observed.add(last);

            if (scenario.mustNotExceed() != null
                    && last.ordinal() > scenario.mustNotExceed().ordinal()) {
                throw new AssertionError(
                        "Scenario " + scenarioId + " exceeded mustNotExceed="
                                + scenario.mustNotExceed() + " at tSec=" + point.tSec()
                                + " (observed=" + last + ")"
                );
            }
        }

        InterventionLevel finalLevel = interventionLadderService.currentLevel(sessionId);
        if (finalLevel != scenario.expectedFinalLevel()) {
            throw new AssertionError(
                    "Scenario " + scenarioId + " expected final "
                            + scenario.expectedFinalLevel() + " but observed " + finalLevel
                            + " (path=" + observed + ")"
            );
        }

        return new TrajectoryResult(
                scenarioId,
                sessionId,
                List.copyOf(observed),
                finalLevel,
                scenario.expectedFinalLevel()
        );
    }
}
