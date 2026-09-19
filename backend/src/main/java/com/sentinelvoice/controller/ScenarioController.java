package com.sentinelvoice.controller;

import com.sentinelvoice.scenario.ScenarioReplayLauncher;
import com.sentinelvoice.scenario.ScenarioService;
import com.sentinelvoice.scenario.ScenarioTrajectoryRunner;
import com.sentinelvoice.scenario.model.Scenario;
import com.sentinelvoice.scenario.model.ScenarioSessionDescriptor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/v1/scenario")
public class ScenarioController {

    private final ScenarioService scenarioService;
    private final ScenarioReplayLauncher replayLauncher;
    private final ScenarioTrajectoryRunner trajectoryRunner;

    public ScenarioController(
            ScenarioService scenarioService,
            ScenarioReplayLauncher replayLauncher,
            ScenarioTrajectoryRunner trajectoryRunner
    ) {
        this.scenarioService = scenarioService;
        this.replayLauncher = replayLauncher;
        this.trajectoryRunner = trajectoryRunner;
    }

    /**
     * Offline ladder rehearsal (no ML audio) — verifies authored trajectories without PCM.
     */
    @PostMapping("/{id}/rehearse")
    public ResponseEntity<?> rehearse(@PathVariable String id) {
        try {
            return ResponseEntity.ok(trajectoryRunner.run(id));
        } catch (NoSuchElementException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return scenarioService.list().stream().map(this::summary).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
        try {
            return ResponseEntity.ok(detail(scenarioService.require(id)));
        } catch (NoSuchElementException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Seed directory / relationship / cross-channel state and open a session.
     *
     * @param mode {@code replay} (default) or {@code live} Asterisk path
     * @param autoReplay when mode=replay, start {@code replay_audio.py} into ml-engine
     */
    @PostMapping("/{id}/load")
    public ResponseEntity<?> load(
            @PathVariable String id,
            @RequestParam(defaultValue = "replay") String mode,
            @RequestParam(defaultValue = "true") boolean autoReplay
    ) {
        try {
            ScenarioSessionDescriptor descriptor = scenarioService.load(id, mode);
            boolean replayStarted = false;
            if (autoReplay
                    && "replay".equalsIgnoreCase(descriptor.mode())
                    && descriptor.audioSource() != null
                    && !"live".equalsIgnoreCase(descriptor.audioSource())) {
                replayStarted = replayLauncher.startReplay(
                        descriptor.sessionId(),
                        descriptor.audioSource(),
                        descriptor.ingestWsUrl(),
                        descriptor.channelProfile() != null ? descriptor.channelProfile().name() : null
                );
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("scenarioId", descriptor.scenarioId());
            body.put("title", descriptor.title());
            body.put("sessionId", descriptor.sessionId());
            body.put("mode", descriptor.mode());
            body.put("channelProfile", descriptor.channelProfile());
            body.put("callerCli", descriptor.callerCli());
            body.put("calleeCli", descriptor.calleeCli());
            body.put("claimedIdentity", descriptor.claimedIdentity());
            body.put("claimedRole", descriptor.claimedRole());
            body.put("audioSource", descriptor.audioSource());
            body.put("ingestWsUrl", descriptor.ingestWsUrl());
            body.put("replayCommand", descriptor.replayCommand());
            body.put("replayStarted", replayStarted);
            body.put("expectedFinalLevel", descriptor.expectedFinalLevel());
            body.put("mustNotExceed", descriptor.mustNotExceed());
            body.put("seniorShield", descriptor.seniorShield());
            body.put("teachingPoint", descriptor.teachingPoint());
            body.put("expectedTrajectory", descriptor.expectedTrajectory());
            return ResponseEntity.ok(body);
        } catch (NoSuchElementException ex) {
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    private Map<String, Object> summary(Scenario scenario) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", scenario.id());
        row.put("title", scenario.title());
        row.put("description", scenario.description());
        row.put("channelProfile", scenario.channelProfile());
        row.put("expectedFinalLevel", scenario.expectedFinalLevel());
        row.put("mustNotExceed", scenario.mustNotExceed());
        row.put("teachingPoint", scenario.teachingPoint());
        row.put("audioSource", scenario.audio() != null ? scenario.audio().source() : null);
        row.put("seniorShield", Boolean.TRUE.equals(scenario.seniorShield()));
        return row;
    }

    private Map<String, Object> detail(Scenario scenario) {
        Map<String, Object> row = summary(scenario);
        row.put("expectedTrajectory", scenario.expectedTrajectory());
        row.put("caller", scenario.caller());
        row.put("callee", scenario.callee());
        row.put("directoryOverrides", scenario.directoryOverrides());
        row.put("relationshipEdges", scenario.relationshipEdges());
        row.put("crossChannelEvents", scenario.crossChannelEvents());
        row.put("audio", scenario.audio());
        row.put("challenge", scenario.challenge());
        return row;
    }
}
