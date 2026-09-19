package com.sentinelvoice.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
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
 * Loads scenario fixtures and opens sessions.
 * Directory / relationship / cross-channel DB seeding deferred to F4 / F15 / F18.
 */
@Service
public class ScenarioService {

    private static final Logger log = LoggerFactory.getLogger(ScenarioService.class);

    private final CallSessionManager callSessionManager;
    private final ScenarioSessionContext scenarioSessionContext;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final ConcurrentHashMap<String, Scenario> scenarios = new ConcurrentHashMap<>();

    @Value("${sentinelvoice.scenarios.path:}")
    private String scenariosPath;

    @Value("${sentinelvoice.ml.websocket-url:ws://localhost:8000/ingest}")
    private String ingestWsBase;

    public ScenarioService(
            CallSessionManager callSessionManager,
            ScenarioSessionContext scenarioSessionContext
    ) {
        this.callSessionManager = callSessionManager;
        this.scenarioSessionContext = scenarioSessionContext;
    }

    @PostConstruct
    void loadFixtures() {
        scenarios.clear();
        List<Path> files = discoverYamlFiles();
        if (files.isEmpty()) {
            loadFromClasspath();
        } else {
            for (Path file : files) {
                try (InputStream in = Files.newInputStream(file)) {
                    Scenario scenario = yamlMapper.readValue(in, Scenario.class);
                    register(scenario, file.toString());
                } catch (IOException ex) {
                    throw new IllegalStateException("Failed to parse scenario " + file + ": " + ex.getMessage(), ex);
                }
            }
        }
        if (scenarios.isEmpty()) {
            throw new IllegalStateException(
                    "No scenario fixtures found. Expected YAML under scenarios/ or classpath:scenarios/"
            );
        }
        log.info("Loaded {} scenario fixtures: {}", scenarios.size(), scenarios.keySet());
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

        // F1: DB seed of directory/edges/cross-channel removed — returns in F4/F15/F18.
        log.warn(
                "scenario_load_without_db_seed id={} reason=F1_postgres_baseline",
                scenario.id()
        );

        ChannelProfile profile = ChannelProfile.valueOf(scenario.channelProfile());
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
            if (point.acoustic() != null) {
                row.put("acoustic", point.acoustic());
            }
            if (point.contextual() != null) {
                row.put("contextual", point.contextual());
            }
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
                scenario.caller() != null ? scenario.caller().claimedIdentity() : null,
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

    private List<Path> discoverYamlFiles() {
        List<Path> roots = new ArrayList<>();
        if (scenariosPath != null && !scenariosPath.isBlank()) {
            roots.add(Path.of(scenariosPath).toAbsolutePath().normalize());
        }
        Path cwd = Path.of("").toAbsolutePath().normalize();
        roots.add(cwd.resolve("scenarios"));
        roots.add(cwd.resolve("..").resolve("scenarios").normalize());
        roots.add(cwd.getParent() != null ? cwd.getParent().resolve("scenarios") : null);

        List<Path> found = new ArrayList<>();
        for (Path root : roots) {
            if (root == null || !Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> stream = Files.list(root)) {
                stream.filter(p -> {
                            String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                            return name.endsWith(".yaml") || name.endsWith(".yml");
                        })
                        .sorted()
                        .forEach(found::add);
            } catch (IOException ex) {
                log.warn("Cannot list scenario dir {}: {}", root, ex.getMessage());
            }
            if (!found.isEmpty()) {
                return found;
            }
        }
        return found;
    }

    private void loadFromClasspath() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath*:scenarios/*.{yml,yaml}");
            for (Resource resource : resources) {
                if (!resource.exists() || !resource.isReadable()) {
                    continue;
                }
                try (InputStream in = resource.getInputStream()) {
                    Scenario scenario = yamlMapper.readValue(in, Scenario.class);
                    register(scenario, resource.getDescription());
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load classpath scenarios: " + ex.getMessage(), ex);
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
