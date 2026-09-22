package com.sentinelvoice.scenario;

import com.sentinelvoice.fusion.config.ActiveFusionConfigCache;
import com.sentinelvoice.fusion.config.FusionConfigDocument;
import com.sentinelvoice.fusion.engine.FusionEngine;
import com.sentinelvoice.fusion.engine.FusionTickInputs;
import com.sentinelvoice.fusion.engine.FusionTickState;
import com.sentinelvoice.fusion.engine.FusionRuntimeService;
import com.sentinelvoice.model.InterventionLevel;
import com.sentinelvoice.scenario.model.Scenario;
import com.sentinelvoice.scenario.model.ScenarioSessionDescriptor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Drives the fusion ladder along a scenario's expected trajectory for
 * integration tests and offline rehearsal (no ML audio required).
 */
@Service
public class ScenarioTrajectoryRunner {

    private final ScenarioService scenarioService;
    private final ActiveFusionConfigCache fusionConfigCache;
    private final FusionRuntimeService fusionRuntimeService;

    public ScenarioTrajectoryRunner(
            ScenarioService scenarioService,
            ActiveFusionConfigCache fusionConfigCache,
            FusionRuntimeService fusionRuntimeService
    ) {
        this.scenarioService = scenarioService;
        this.fusionConfigCache = fusionConfigCache;
        this.fusionRuntimeService = fusionRuntimeService;
    }

    public record TrajectoryResult(
            String scenarioId,
            String sessionId,
            List<InterventionLevel> observedLevels,
            InterventionLevel finalLevel,
            InterventionLevel expectedFinalLevel
    ) {
    }

    public TrajectoryResult run(String scenarioId) {
        Scenario scenario = scenarioService.require(scenarioId);
        ScenarioSessionDescriptor descriptor = scenarioService.load(scenarioId, "replay");
        String sessionId = descriptor.sessionId();
        fusionRuntimeService.clearSession(sessionId);

        FusionConfigDocument config = fusionConfigCache.get(
                        java.util.UUID.fromString("00000000-0000-0000-0000-000000000001")
                )
                .map(ActiveFusionConfigCache.CachedFusionConfig::document)
                .orElseGet(ScenarioTrajectoryRunner::platformDefaultDocument);

        List<InterventionLevel> observed = new ArrayList<>();
        long nowMs = 1_000_000L;
        FusionTickState state = FusionTickState.initial(nowMs);
        InterventionLevel last = InterventionLevel.LEVEL_1_SILENT;

        for (Scenario.TrajectoryPoint point : scenario.expectedTrajectory()) {
            nowMs += 20_000L;
            double risk = point.risk();
            boolean corr = point.corroboration();
            FusionTickInputs inputs = new FusionTickInputs(
                    false,
                    5_000L,
                    true,
                    nowMs,
                    FusionTickInputs.FamilyRaw.available(risk),
                    FusionTickInputs.FamilyRaw.available(corr ? risk : 0.1),
                    FusionTickInputs.FamilyRaw.available(corr ? risk : 0.1),
                    FusionTickInputs.FamilyRaw.available(corr ? risk : 0.1),
                    FusionTickInputs.FamilyRaw.available(corr ? risk : 0.1),
                    FusionTickInputs.FamilyRaw.available(corr ? risk : 0.1),
                    0L,
                    0.0,
                    0.0,
                    0.0,
                    0,
                    List.of(),
                    false
            );
            if (point.isConfirmL5()) {
                // Analyst pin to L5 outside the pure auto path
                state = new FusionTickState(risk, 5, nowMs, nowMs + 120_000L, 5);
            } else {
                FusionEngine.TickResult tick = FusionEngine.tick(config, state, inputs, 1, null);
                state = tick.state();
            }
            last = FusionRuntimeService.toInterventionLevel(state.level());
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

        InterventionLevel finalLevel = last;
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

    private static FusionConfigDocument platformDefaultDocument() {
        return FusionConfigDocument.platformDefault();
    }
}
