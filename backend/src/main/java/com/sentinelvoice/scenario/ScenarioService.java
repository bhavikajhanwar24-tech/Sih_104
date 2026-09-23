package com.sentinelvoice.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelvoice.model.ChannelProfile;
import com.sentinelvoice.model.SessionStartRequest;
import com.sentinelvoice.scenario.model.Scenario;
import com.sentinelvoice.scenario.model.ScenarioSessionDescriptor;
import com.sentinelvoice.service.CallSessionManager;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * F18 — loads JSON scenario fixtures (classpath:/scenarios/*.json).
 * Real audio via WAV replay / ARI; no fake FeatureFrame injection into live sessions.
 */
@Service
public class ScenarioService {

    private static final Logger log = LoggerFactory.getLogger(ScenarioService.class);

    private final CallSessionManager callSessionManager;
    private final ScenarioSessionContext scenarioSessionContext;
    private final ObjectMapper jsonMapper;
    private final ConcurrentHashMap<String, Scenario> scenarios = new ConcurrentHashMap<>();

    @Value("${sentinelvoice.scenarios.path:}")
    private String scenariosPath;

    @Value("${sentinelvoice.ml.websocket-url:ws://localhost:8000/ingest}")
    private String ingestWsBase;

    public ScenarioService(
            CallSessionManager callSessionManager,
            ScenarioSessionContext scenarioSessionContext,
            ObjectMapper objectMapper
    ) {
        this.callSessionManager = callSessionManager;
        this.scenarioSessionContext = scenarioSessionContext;
        this.jsonMapper = objectMapper;
    }

    @PostConstruct
    void loadFixtures() {
        scenarios.clear();
        loadFromClasspathJson();
        if (scenarios.isEmpty()) {
            loadFromFilesystemJson();
        }
        if (scenarios.isEmpty()) {
            log.warn("No F18 scenario fixtures found under classpath:/scenarios/*.json");
            return;
        }
        log.info("Loaded {} F18 scenario fixtures: {}", scenarios.size(), scenarios.keySet());
    }

    public List<Scenario> list() {
        return scenarios.values().stream()
                .sorted(Comparator.comparing(Scenario::id))
                .toList();
    }

    public Scenario require(String id) {
        Scenario scenario = scenarios.get(id);
        if (scenario == null) {
            throw new NoSuchElementException("unknown scenario: " + id);
        }
        return scenario;
    }

    public ScenarioSessionDescriptor load(String id, String mode) {
        Scenario scenario = require(id);
        String normalisedMode = normaliseMode(mode);
        if ("live".equals(normalisedMode) && scenario.audio() != null && !scenario.audio().isLiveAllowed()) {
            throw new IllegalArgumentException("scenario " + id + " does not allow live mode");
        }

        ChannelProfile profile = ChannelProfile.valueOf(
                scenario.channelProfile() == null ? "WEBRTC_WIDEBAND" : scenario.channelProfile()
        );
        String sessionId = "scen-" + scenario.id() + "-" + Long.toHexString(System.currentTimeMillis());
        String callerCli = scenario.caller() != null ? scenario.caller().cli() : "unknown";
        String calleeCli = scenario.callee() != null ? scenario.callee().cli() : "unknown";

        callSessionManager.createSession(new SessionStartRequest(
                "sentinelvoice.SessionStartRequest/1",
                sessionId,
                callerCli,
                calleeCli,
                profile,
                scenario.id()
        ));
        scenarioSessionContext.bind(sessionId, scenario.transactionSeed(), scenario);

        String audioSource = scenario.audio() != null ? scenario.audio().source() : "live";
        if ("live".equals(normalisedMode)) {
            audioSource = "live";
        }
        String ingestWs = ingestWsBase.endsWith("/")
                ? ingestWsBase + sessionId
                : ingestWsBase + "/" + sessionId;

        String replayCommand = null;
        if ("replay".equals(normalisedMode) && audioSource != null && !"live".equalsIgnoreCase(audioSource)) {
            String profileHint = scenario.audio() != null && scenario.audio().codecProfileHint() != null
                    ? " --profile " + scenario.audio().codecProfileHint()
                    : "";
            replayCommand = "python gateway/replay_audio.py --wav " + audioSource
                    + " --session " + sessionId
                    + " --ws " + ingestWs
                    + profileHint;
        }

        List<Map<String, Object>> trajectory = new ArrayList<>();
        for (Scenario.TrajectoryPoint point : scenario.expectedTrajectory()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tSec", point.tSec());
            row.put("risk", point.risk());
            row.put("level", point.level() != null ? point.level().name() : null);
            row.put("corroboration", point.corroboration());
            trajectory.add(row);
        }

        return new ScenarioSessionDescriptor(
                scenario.id(),
                scenario.title(),
                sessionId,
                normalisedMode,
                profile,
                callerCli,
                calleeCli,
                scenario.caller() != null ? scenario.caller().claimedIdentity() : scenario.claimedIdentity(),
                scenario.caller() != null ? scenario.caller().claimedRole() : null,
                audioSource,
                ingestWs,
                replayCommand,
                scenario.expectedFinalLevel(),
                scenario.mustNotExceed(),
                Boolean.TRUE.equals(scenario.seniorShield()),
                scenario.teachingPoint(),
                List.copyOf(trajectory)
        );
    }

    private void register(Scenario scenario, String source) {
        if (scenario.id() == null || scenario.id().isBlank()) {
            throw new IllegalStateException("Scenario missing id in " + source);
        }
        if (scenario.expectedFinalLevel() == null) {
            throw new IllegalStateException("Scenario " + scenario.id() + " missing expectedFinalLevel");
        }
        scenarios.put(scenario.id(), scenario);
    }

    private void loadFromClasspathJson() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath*:scenarios/*.json");
            for (Resource resource : resources) {
                if (!resource.exists() || !resource.isReadable()) {
                    continue;
                }
                try (InputStream in = resource.getInputStream()) {
                    Scenario scenario = jsonMapper.readValue(in, Scenario.class);
                    register(scenario, resource.getDescription());
                }
            }
        } catch (IOException ex) {
            log.warn("classpath scenario load failed: {}", ex.getMessage());
        }
    }

    private void loadFromFilesystemJson() {
        List<Path> roots = new ArrayList<>();
        if (scenariosPath != null && !scenariosPath.isBlank()) {
            roots.add(Path.of(scenariosPath).toAbsolutePath().normalize());
        }
        Path cwd = Path.of("").toAbsolutePath().normalize();
        roots.add(cwd.resolve("scenarios"));
        roots.add(cwd.resolve("backend/src/main/resources/scenarios"));
        for (Path root : roots) {
            if (root == null || !Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> stream = Files.list(root)) {
                stream.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                        .sorted()
                        .forEach(p -> {
                            try (InputStream in = Files.newInputStream(p)) {
                                register(jsonMapper.readValue(in, Scenario.class), p.toString());
                            } catch (IOException ex) {
                                throw new IllegalStateException("Failed to parse " + p + ": " + ex.getMessage(), ex);
                            }
                        });
            } catch (IOException ex) {
                log.warn("Cannot list {}: {}", root, ex.getMessage());
            }
            if (!scenarios.isEmpty()) {
                return;
            }
        }
    }

    private static String normaliseMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return "replay";
        }
        String m = mode.trim().toLowerCase(Locale.ROOT);
        if ("live".equals(m) || "replay".equals(m)) {
            return m;
        }
        throw new IllegalArgumentException("mode must be replay or live");
    }
}
