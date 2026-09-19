package com.sentinelvoice.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.sentinelvoice.context.model.CrossChannelEvent;
import com.sentinelvoice.context.model.InteractionEdge;
import com.sentinelvoice.identity.model.DirectoryRecord;
import com.sentinelvoice.model.ChannelProfile;
import com.sentinelvoice.model.SessionStartRequest;
import com.sentinelvoice.repository.CrossChannelEventRepository;
import com.sentinelvoice.repository.DirectoryRecordRepository;
import com.sentinelvoice.repository.InteractionEdgeRepository;
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
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Loads Context §14 scenario fixtures, seeds directory / relationship / cross-channel
 * state, and opens a Decision Plane session for replay or live Asterisk.
 */
@Service
public class ScenarioService {

    private static final Logger log = LoggerFactory.getLogger(ScenarioService.class);

    private final DirectoryRecordRepository directoryRecordRepository;
    private final InteractionEdgeRepository interactionEdgeRepository;
    private final CrossChannelEventRepository crossChannelEventRepository;
    private final CallSessionManager callSessionManager;
    private final ScenarioSessionContext scenarioSessionContext;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final ConcurrentHashMap<String, Scenario> scenarios = new ConcurrentHashMap<>();

    @Value("${sentinelvoice.scenarios.path:}")
    private String scenariosPath;

    @Value("${sentinelvoice.ml.websocket-url:ws://localhost:8000/ingest}")
    private String ingestWsBase;

    public ScenarioService(
            DirectoryRecordRepository directoryRecordRepository,
            InteractionEdgeRepository interactionEdgeRepository,
            CrossChannelEventRepository crossChannelEventRepository,
            CallSessionManager callSessionManager,
            ScenarioSessionContext scenarioSessionContext
    ) {
        this.directoryRecordRepository = directoryRecordRepository;
        this.interactionEdgeRepository = interactionEdgeRepository;
        this.crossChannelEventRepository = crossChannelEventRepository;
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

    /**
     * Apply seed state and open a session. {@code mode} is {@code replay} or {@code live}.
     */
    @Transactional
    public ScenarioSessionDescriptor load(String id, String mode) {
        Scenario scenario = require(id);
        String normalisedMode = normaliseMode(mode);
        if ("live".equals(normalisedMode) && scenario.audio() != null && !scenario.audio().isLiveAllowed()) {
            throw new IllegalArgumentException("scenario " + id + " does not allow live mode");
        }

        applyDirectoryOverrides(scenario.directoryOverrides());
        applyRelationshipEdges(scenario.relationshipEdges());
        applyCrossChannelEvents(scenario.crossChannelEvents());

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

        log.info(
                "Loaded scenario id={} mode={} sessionId={} expectedFinal={}",
                scenario.id(),
                normalisedMode,
                sessionId,
                scenario.expectedFinalLevel()
        );

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
        // Maven surefire often runs with user.dir = backend/
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

    private void applyDirectoryOverrides(List<Scenario.DirectoryOverride> overrides) {
        for (Scenario.DirectoryOverride o : overrides) {
            if (o.employeeId() == null || o.employeeId().isBlank()) {
                continue;
            }
            DirectoryRecord record = directoryRecordRepository.findById(o.employeeId())
                    .orElseGet(DirectoryRecord::new);
            if (record.getEmployeeId() == null) {
                record.setEmployeeId(o.employeeId());
                // Defaults for newly inserted senior-shield / demo rows
                record.setName(Optional.ofNullable(o.name()).orElse(o.employeeId()));
                record.setRole(Optional.ofNullable(o.role()).orElse("Demo"));
                record.setDepartment(Optional.ofNullable(o.department()).orElse("Demo"));
                record.setPrimaryCli(Optional.ofNullable(o.primaryCli()).orElse("unknown"));
                record.setVerbalAuthorityLimitInr(
                        o.verbalAuthorityLimitInr() != null ? o.verbalAuthorityLimitInr() : 0.0
                );
                record.setPermittedChannels(
                        Optional.ofNullable(o.permittedChannels()).orElse("BRANCH")
                );
                record.setHierarchyLevel(o.hierarchyLevel() != null ? o.hierarchyLevel() : 99);
                record.setPassportEnrolled(Boolean.TRUE.equals(o.passportEnrolled()));
            }
            if (o.name() != null) {
                record.setName(o.name());
            }
            if (o.role() != null) {
                record.setRole(o.role());
            }
            if (o.department() != null) {
                record.setDepartment(o.department());
            }
            if (o.primaryCli() != null) {
                record.setPrimaryCli(o.primaryCli());
            }
            if (o.extension() != null) {
                record.setExtension(o.extension());
            }
            if (o.verbalAuthorityLimitInr() != null) {
                record.setVerbalAuthorityLimitInr(o.verbalAuthorityLimitInr());
            }
            if (o.permittedChannels() != null) {
                record.setPermittedChannels(o.permittedChannels());
            }
            if (o.presenceStatus() != null) {
                record.setPresenceStatus(o.presenceStatus());
            }
            if (o.calendarLocation() != null) {
                record.setCalendarLocation(o.calendarLocation());
            }
            if (o.managerEmployeeId() != null) {
                record.setManagerEmployeeId(o.managerEmployeeId());
            }
            if (o.hierarchyLevel() != null) {
                record.setHierarchyLevel(o.hierarchyLevel());
            }
            if (o.passportEnrolled() != null) {
                record.setPassportEnrolled(o.passportEnrolled());
            }
            directoryRecordRepository.save(record);
        }
    }

    private void applyRelationshipEdges(List<Scenario.RelationshipEdgeSeed> edges) {
        Instant now = Instant.now();
        for (Scenario.RelationshipEdgeSeed seed : edges) {
            InteractionEdge edge = interactionEdgeRepository
                    .findByCallerEmployeeIdAndCalleeEmployeeId(
                            seed.callerEmployeeId(),
                            seed.calleeEmployeeId()
                    )
                    .orElseGet(InteractionEdge::new);
            edge.setCallerEmployeeId(seed.callerEmployeeId());
            edge.setCalleeEmployeeId(seed.calleeEmployeeId());
            edge.setInteractionCount(seed.interactionCount());
            edge.setTypicalHourOfDay(seed.typicalHourOfDay());
            edge.setTypicalDurationSec(seed.typicalDurationSec());
            if (edge.getFirstSeenAt() == null) {
                edge.setFirstSeenAt(now.minus(180, ChronoUnit.DAYS));
            }
            edge.setLastSeenAt(now.minus(1, ChronoUnit.DAYS));
            interactionEdgeRepository.save(edge);
        }
    }

    private void applyCrossChannelEvents(List<Scenario.CrossChannelSeed> seeds) {
        // Replace events for campaigns referenced by this scenario so reloads are idempotent.
        seeds.stream()
                .map(Scenario.CrossChannelSeed::campaignId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .forEach(campaignId -> {
                    List<CrossChannelEvent> existing = crossChannelEventRepository.findByCampaignId(campaignId);
                    if (!existing.isEmpty()) {
                        crossChannelEventRepository.deleteAll(existing);
                    }
                });

        Instant now = Instant.now();
        for (Scenario.CrossChannelSeed seed : seeds) {
            CrossChannelEvent event = new CrossChannelEvent();
            event.setId(seed.id());
            event.setChannel(CrossChannelEvent.Channel.valueOf(seed.channel().toUpperCase(Locale.ROOT)));
            event.setTargetEmployeeId(seed.targetEmployeeId());
            event.setOccurredAt(now.minus((long) (seed.occurredHoursAgo() * 3600), ChronoUnit.SECONDS));
            event.setSeverity(CrossChannelEvent.Severity.valueOf(seed.severity().toUpperCase(Locale.ROOT)));
            event.setIndicator(seed.indicator());
            event.setCampaignId(seed.campaignId());
            event.setDescription(seed.description());
            crossChannelEventRepository.save(event);
        }
    }

    private static String normaliseMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return "replay";
        }
        String m = mode.trim().toLowerCase(Locale.ROOT);
        if (!m.equals("replay") && !m.equals("live")) {
            throw new IllegalArgumentException("mode must be 'replay' or 'live', got: " + mode);
        }
        return m;
    }
}
