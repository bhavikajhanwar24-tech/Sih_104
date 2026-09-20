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
        return FusionConfigDocument.parse(java.util.Map.of(
                "weights", java.util.Map.of(
                        "wideband", java.util.Map.of(
                                "voice", 0.24, "channel", 0.08, "prosody", 0.13,
                                "linguistic", 0.25, "transaction", 0.18, "relationship", 0.12
                        ),
                        "narrowband", java.util.Map.of(
                                "voice", 0.15, "channel", 0.10, "prosody", 0.12,
                                "linguistic", 0.29, "transaction", 0.20, "relationship", 0.14
                        )
                ),
                "smoothing", java.util.Map.of(
                        "lambdaUp", 0.55, "lambdaDown", 0.88, "linguisticStalenessTauMs", 3000
                ),
                "familyThresholds", java.util.Map.of(
                        "voice", 0.60, "channel", 0.55, "prosody", 0.60,
                        "linguistic", 0.65, "transaction", 0.60, "relationship", 0.60
                ),
                "corroboration", java.util.Map.of(
                        "minIndependentFamiliesForL3", 2, "minForL4", 3
                ),
                "levels", java.util.Map.of(
                        "L1", java.util.Map.of("enter", 0.30, "exit", 0.25, "minDwellMs", 1000),
                        "L2", java.util.Map.of("enter", 0.35, "exit", 0.28, "minDwellMs", 1000),
                        "L3", java.util.Map.of("enter", 0.55, "exit", 0.46, "minDwellMs", 1000),
                        "L4", java.util.Map.of("enter", 0.73, "exit", 0.66, "minDwellMs", 1000)
                ),
                "insufficientEvidence", java.util.Map.of("minSpeechMs", 3000),
                "missingEvidence", java.util.Map.of(
                        "llmUnavailable", "CONTINUE_RULES_ONLY",
                        "directoryEmpty", "CONTINUE_RULES_ONLY"
                ),
                "hardFloors", java.util.Map.of("acousticAloneMaxLevel", 2),
                "emergency", java.util.Map.of("enabled", true, "rules", java.util.List.of()),
                "overridePinDurationMs", 120000
        ));
    }
}
