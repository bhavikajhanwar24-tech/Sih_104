package com.sentinelvoice.scenario;

import com.sentinelvoice.model.InterventionLevel;
import com.sentinelvoice.scenario.model.Scenario;
import com.sentinelvoice.scenario.model.ScenarioSessionDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Asserts Context §14 scenarios load and their authored trajectories reach the
 * expected final intervention level. A silent ladder regression fails CI here —
 * not on stage.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ScenarioTrajectoryIT {

    private static final Set<String> REQUIRED_IDS = Set.of(
            "legit-cfo",
            "deepfake-ceo-wire",
            "liveness-challenge",
            "hinglish-grandparent",
            "false-positive-stress",
            "adversarial-evasion"
    );

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ScenarioService scenarioService;

    @Autowired
    private ScenarioTrajectoryRunner trajectoryRunner;

    @Test
    void listsAllSixScenarios() throws Exception {
        mockMvc.perform(get("/api/v1/scenario"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6));

        Set<String> ids = scenarioService.list().stream().map(Scenario::id).collect(Collectors.toSet());
        assertThat(ids).containsExactlyInAnyOrderElementsOf(REQUIRED_IDS);
    }

    @Test
    void loadReturnsSessionDescriptorForReplayAndLive() throws Exception {
        mockMvc.perform(post("/api/v1/scenario/deepfake-ceo-wire/load").param("mode", "replay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scenarioId").value("deepfake-ceo-wire"))
                .andExpect(jsonPath("$.mode").value("replay"))
                .andExpect(jsonPath("$.sessionId").isNotEmpty())
                .andExpect(jsonPath("$.expectedFinalLevel").value("LEVEL_4_AUTO_HOLD"))
                .andExpect(jsonPath("$.replayCommand").isNotEmpty());

        mockMvc.perform(post("/api/v1/scenario/deepfake-ceo-wire/load").param("mode", "live"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("live"))
                .andExpect(jsonPath("$.audioSource").value("live"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "legit-cfo",
            "deepfake-ceo-wire",
            "liveness-challenge",
            "hinglish-grandparent",
            "false-positive-stress",
            "adversarial-evasion"
    })
    void trajectoryReachesExpectedFinalLevel(String scenarioId) {
        ScenarioTrajectoryRunner.TrajectoryResult result = trajectoryRunner.run(scenarioId);
        Scenario scenario = scenarioService.require(scenarioId);

        assertThat(result.finalLevel())
                .as("final level for %s", scenarioId)
                .isEqualTo(scenario.expectedFinalLevel());

        assertThat(result.observedLevels()).isNotEmpty();

        if (scenario.mustNotExceed() != null) {
            assertThat(result.observedLevels())
                    .as("mustNotExceed for %s", scenarioId)
                    .allSatisfy(level ->
                            assertThat(level.ordinal()).isLessThanOrEqualTo(scenario.mustNotExceed().ordinal())
                    );
        }

        // Authored checkpoint levels must be reachable (observed ends at expected).
        List<InterventionLevel> authored = scenario.expectedTrajectory().stream()
                .map(Scenario.TrajectoryPoint::level)
                .toList();
        assertThat(authored).isNotEmpty();
        assertThat(authored.get(authored.size() - 1)).isEqualTo(scenario.expectedFinalLevel());
    }

    @Test
    void falsePositiveStressNeverEscalatesPastL2() {
        ScenarioTrajectoryRunner.TrajectoryResult result = trajectoryRunner.run("false-positive-stress");
        assertThat(result.finalLevel()).isEqualTo(InterventionLevel.LEVEL_2_SOFT_NUDGE);
        assertThat(result.observedLevels())
                .allMatch(l -> l.ordinal() <= InterventionLevel.LEVEL_2_SOFT_NUDGE.ordinal());
    }

    @Test
    void loadSeedsDirectoryAndReturnsTeachingPoint() {
        ScenarioSessionDescriptor descriptor = scenarioService.load("hinglish-grandparent", "replay");
        assertThat(descriptor.seniorShield()).isTrue();
        assertThat(descriptor.teachingPoint()).containsIgnoringCase("Senior Shield");
        assertThat(descriptor.expectedTrajectory()).isNotEmpty();
    }
}
