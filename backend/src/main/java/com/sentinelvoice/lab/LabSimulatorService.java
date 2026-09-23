package com.sentinelvoice.lab;

import com.sentinelvoice.actuation.AsteriskAriAdapter;
import com.sentinelvoice.actuation.CallControlPort;
import com.sentinelvoice.scenario.ScenarioReplayLauncher;
import com.sentinelvoice.scenario.ScenarioService;
import com.sentinelvoice.scenario.model.Scenario;
import com.sentinelvoice.scenario.model.ScenarioSessionDescriptor;
import com.sentinelvoice.security.TenantContext;
import com.sentinelvoice.telephony.CallSessionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * F18 lab simulator — LAB_MODE only. ARI originate when available + WAV replay into ml-engine.
 */
@Service
public class LabSimulatorService {

    private final ScenarioService scenarioService;
    private final ScenarioReplayLauncher replayLauncher;
    private final CallSessionRepository callSessionRepository;
    private final CallControlPort callControlPort;
    private final JdbcTemplate jdbc;
    private final boolean labMode;
    private final ConcurrentHashMap<String, RunState> runs = new ConcurrentHashMap<>();

    public LabSimulatorService(
            ScenarioService scenarioService,
            ScenarioReplayLauncher replayLauncher,
            CallSessionRepository callSessionRepository,
            CallControlPort callControlPort,
            JdbcTemplate jdbc,
            @Value("${LAB_MODE:false}") boolean labMode
    ) {
        this.scenarioService = scenarioService;
        this.replayLauncher = replayLauncher;
        this.callSessionRepository = callSessionRepository;
        this.callControlPort = callControlPort;
        this.jdbc = jdbc;
        this.labMode = labMode;
    }

    public void requireLabMode() {
        if (!labMode) {
            throw new LabException("LAB_MODE_OFF", "Lab simulator requires LAB_MODE=true");
        }
    }

    public List<Map<String, Object>> listScenarios() {
        requireLabMode();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Scenario s : scenarioService.list()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", s.id());
            row.put("title", s.title());
            row.put("description", s.description());
            row.put("attackerPersona", s.attackerPersona());
            row.put("spoofedCli", s.spoofedCli());
            row.put("claimedIdentity", s.claimedIdentity());
            row.put("spokenScript", s.spokenScript());
            row.put("expectedFinalLevel", s.expectedFinalLevel() == null ? null : s.expectedFinalLevel().name());
            row.put("mustNotExceed", s.mustNotExceed() == null ? null : s.mustNotExceed().name());
            row.put("expectedPolicyRules", s.expectedPolicyRules());
            row.put("expectedTrajectory", s.expectedTrajectory());
            row.put("audioSource", s.audio() == null ? null : s.audio().source());
            row.put("teachingPoint", s.teachingPoint());
            row.put("simulated", true);
            out.add(row);
        }
        return out;
    }

    public Map<String, Object> run(String scenarioId) {
        requireLabMode();
        Scenario scenario = scenarioService.require(scenarioId);
        ScenarioSessionDescriptor descriptor = scenarioService.load(scenarioId, "replay");

        String ariChannel = null;
        if (callControlPort instanceof AsteriskAriAdapter ari) {
            String extension = resolveTargetExtension(scenario);
            String callerId = scenario.spoofedCli() != null
                    ? scenario.spoofedCli()
                    : (scenario.caller() != null ? scenario.caller().cli() : "Lab");
            Optional<String> ch = ari.originateLabCall(descriptor.sessionId(), extension, callerId, null);
            ariChannel = ch.orElse(null);
        }

        boolean replayStarted = false;
        if (descriptor.audioSource() != null && !"live".equalsIgnoreCase(descriptor.audioSource())) {
            replayStarted = replayLauncher.startReplay(
                    descriptor.sessionId(),
                    descriptor.audioSource(),
                    descriptor.ingestWsUrl(),
                    descriptor.channelProfile() == null ? null : descriptor.channelProfile().name()
            );
        }
        RunState state = new RunState(scenarioId, descriptor.sessionId(), scenario, System.currentTimeMillis());
        runs.put(descriptor.sessionId(), state);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", "2");
        body.put("simulated", true);
        body.put("scenarioId", scenarioId);
        body.put("sessionId", descriptor.sessionId());
        body.put("ariChannelId", ariChannel);
        body.put("ariOriginated", ariChannel != null);
        body.put("replayStarted", replayStarted);
        body.put("ingestWsUrl", descriptor.ingestWsUrl());
        body.put("expectedFinalLevel", descriptor.expectedFinalLevel() == null ? null : descriptor.expectedFinalLevel().name());
        body.put("expectedTrajectory", descriptor.expectedTrajectory());
        body.put("expectedPolicyRules", scenario.expectedPolicyRules());
        body.put("teachingPoint", scenario.teachingPoint());
        body.put("note", note(ariChannel != null, replayStarted));
        return body;
    }

    public Map<String, Object> result(String sessionId) {
        requireLabMode();
        RunState state = runs.get(sessionId);
        Scenario scenario = state == null ? null : state.scenario();
        if (scenario == null) {
            scenario = recoverScenario(sessionId);
        }
        UUID tenantId = TenantContext.require().tenantId();
        String peakLevel = null;
        Double peakScore = null;
        try {
            var detail = callSessionRepository.findDetailByIdOrSvSession(tenantId, sessionId);
            if (detail.isPresent()) {
                peakLevel = detail.get().peakLevel();
                peakScore = detail.get().peakScore();
            }
        } catch (Exception ignored) {
            /* optional */
        }
        List<Map<String, Object>> ticks = List.of();
        try {
            ticks = jdbc.query(
                    """
                    SELECT t_ms, score, level FROM session_ticks
                    WHERE tenant_id = ? AND (session_id::text = ? OR id::text = ?)
                    ORDER BY t_ms ASC LIMIT 200
                    """,
                    (rs, i) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("tMs", rs.getLong("t_ms"));
                        m.put("score", rs.getDouble("score"));
                        m.put("level", rs.getString("level"));
                        return m;
                    },
                    tenantId, sessionId, sessionId
            );
        } catch (Exception ignored) {
            /* schema / cast variance */
        }

        String expected = scenario == null || scenario.expectedFinalLevel() == null
                ? null
                : scenario.expectedFinalLevel().name();
        boolean match = expected != null && peakLevel != null && peakLevel.contains(levelToken(expected));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", "2");
        body.put("simulated", true);
        body.put("sessionId", sessionId);
        body.put("scenarioId", scenario == null ? (state == null ? null : state.scenarioId()) : scenario.id());
        body.put("expectedFinalLevel", expected);
        body.put("actualPeakLevel", peakLevel);
        body.put("actualPeakScore", peakScore);
        body.put("match", match);
        body.put("expectedTrajectory", scenario == null ? List.of() : scenario.expectedTrajectory());
        body.put("actualTicks", ticks);
        body.put("expectedPolicyRules", scenario == null ? List.of() : scenario.expectedPolicyRules());
        body.put("teachingPoint", scenario == null ? null : scenario.teachingPoint());
        return body;
    }

    /** Recover scenario from lab session id pattern {@code scen-{id}-{hex}}. */
    private Scenario recoverScenario(String sessionId) {
        if (sessionId == null || !sessionId.startsWith("scen-")) {
            return null;
        }
        String rest = sessionId.substring("scen-".length());
        int dash = rest.lastIndexOf('-');
        if (dash <= 0) {
            return null;
        }
        String scenarioId = rest.substring(0, dash);
        try {
            return scenarioService.require(scenarioId);
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveTargetExtension(Scenario scenario) {
        if (scenario.callee() != null && scenario.callee().cli() != null) {
            String cli = scenario.callee().cli();
            // Prefer last 4 digits as lab extension (seeded 1001+)
            String digits = cli.replaceAll("\\D", "");
            if (digits.length() >= 4) {
                return digits.substring(digits.length() - 4);
            }
        }
        return "1002";
    }

    private static String note(boolean ari, boolean replay) {
        if (ari && replay) {
            return "ARI originate + WAV replay started; watch Live Calls for actual trajectory.";
        }
        if (ari) {
            return "ARI originate started; WAV replay did not start — place softphone online or add scenarios/audio WAV.";
        }
        if (replay) {
            return "WAV replay into ml-engine started (ARI unavailable — softphone/loopback fallback).";
        }
        return "Neither ARI nor WAV replay started — check Asterisk, LAB_MODE, and scenarios/audio.";
    }

    private static String levelToken(String expected) {
        if (expected.contains("LEVEL_4")) return "LEVEL_4";
        if (expected.contains("LEVEL_3")) return "LEVEL_3";
        if (expected.contains("LEVEL_2")) return "LEVEL_2";
        if (expected.contains("LEVEL_1")) return "LEVEL_1";
        if (expected.contains("LEVEL_0") || expected.contains("LEVEL_5")) return expected;
        return expected;
    }

    private record RunState(String scenarioId, String sessionId, Scenario scenario, long startedAtMs) {}
}
