package com.sentinelvoice.compliance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.config.SentinelProperties;
import com.sentinelvoice.repository.AuditBlockRepository;
import com.sentinelvoice.repository.VoicePassportRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Real-query counters for the DPDP / RBI compliance portal (Context §13.3 / §13.5).
 * No hardcoded dashboard figures — every number comes from a repository or JDBC query.
 */
@Service
public class ComplianceMetricsService {

    private static final String TELEMETRY_EVENT = AuditEventType.FEATURE_FRAME_SCORED.name();
    private static final String ERASE_EVENT = AuditEventType.PASSPORT_ERASED.name();

    private final AuditBlockRepository auditBlockRepository;
    private final VoicePassportRepository voicePassportRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final SentinelProperties.Compliance compliance;
    private final AtomicReference<Instant> nextPurgeAt = new AtomicReference<>();
    private final AtomicReference<Instant> lastPurgeAt = new AtomicReference<>();

    public ComplianceMetricsService(
            AuditBlockRepository auditBlockRepository,
            VoicePassportRepository voicePassportRepository,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            SentinelProperties properties
    ) {
        this.auditBlockRepository = auditBlockRepository;
        this.voicePassportRepository = voicePassportRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.compliance = properties.compliance();
        Instant now = Instant.now();
        this.nextPurgeAt.set(now.plus(compliance.purgeIntervalHours(), ChronoUnit.HOURS));
        this.lastPurgeAt.set(null);
    }

    public Map<String, Object> retentionDashboard() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rawAudioBytesPersisted", rawAudioBytesPersisted());
        out.put("rawAudioEnforcingPath", compliance.rawAudioEnforcingPath());
        out.put("rawAudioSchemaProbe", rawAudioSchemaProbe());
        out.put("telemetry", telemetryAgeDistribution());
        out.put("embeddingsStored", voicePassportRepository.countByActiveTrue());
        out.put("erasuresPerformed", auditBlockRepository.countByEventType(ERASE_EVENT));
        out.put("tombstonesRecorded", auditBlockRepository.countByEventType(ERASE_EVENT));
        out.put("auditBlocksTotal", auditBlockRepository.count());
        out.put("telemetryTtlDays", compliance.telemetryTtlDays());
        out.put("auditRetentionYears", compliance.auditRetentionYears());
        Instant next = nextPurgeAt.get();
        Instant last = lastPurgeAt.get();
        out.put("nextScheduledPurge", next == null ? null : next.toString());
        out.put("lastPurgeAt", last == null ? null : last.toString());
        out.put("purgeIntervalHours", compliance.purgeIntervalHours());
        return out;
    }

    /**
     * Architectural invariant: Decision Plane never stores PCM. Confirmed by schema probe
     * (no pcm/raw_audio columns) plus {@code SessionRetentionTest}.
     */
    public long rawAudioBytesPersisted() {
        Long suspicious = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                        WHERE LOWER(COLUMN_NAME) IN ('pcm', 'raw_audio', 'audio_bytes', 'wav', 'audio_pcm')
                        """,
                Long.class
        );
        if (suspicious != null && suspicious > 0) {
            // Should never happen — fail loud in metrics rather than invent a byte count.
            return -1L;
        }
        return 0L;
    }

    public Map<String, Object> rawAudioSchemaProbe() {
        Long columns = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                        WHERE LOWER(COLUMN_NAME) IN ('pcm', 'raw_audio', 'audio_bytes', 'wav', 'audio_pcm')
                        """,
                Long.class
        );
        Map<String, Object> probe = new LinkedHashMap<>();
        probe.put("pcmLikeColumns", columns == null ? 0L : columns);
        probe.put("enforcingCodePath", compliance.rawAudioEnforcingPath());
        probe.put("decisionPlaneGuardTest", "backend/.../model/SessionRetentionTest.java");
        return probe;
    }

    public Map<String, Object> telemetryAgeDistribution() {
        int ttlDays = compliance.telemetryTtlDays();
        long nowMs = Instant.now().toEpochMilli();
        long dayMs = 86_400_000L;

        long total = auditBlockRepository.countByEventType(TELEMETRY_EVENT);
        long age0to7 = auditBlockRepository.countByEventTypeAndTsRange(
                TELEMETRY_EVENT, nowMs - 7 * dayMs, nowMs + 1
        );
        long age8to30 = auditBlockRepository.countByEventTypeAndTsRange(
                TELEMETRY_EVENT, nowMs - 30 * dayMs, nowMs - 7 * dayMs
        );
        long age31to90 = auditBlockRepository.countByEventTypeAndTsRange(
                TELEMETRY_EVENT, nowMs - (long) ttlDays * dayMs, nowMs - 30 * dayMs
        );
        long pastTtl = auditBlockRepository.countByEventTypeOlderThan(
                TELEMETRY_EVENT, nowMs - (long) ttlDays * dayMs
        );

        Map<String, Object> dist = new LinkedHashMap<>();
        dist.put("eventType", TELEMETRY_EVENT);
        dist.put("totalRows", total);
        dist.put("ttlDays", ttlDays);
        Map<String, Long> buckets = new LinkedHashMap<>();
        buckets.put("0_7_days", age0to7);
        buckets.put("8_30_days", age8to30);
        buckets.put("31_ttl_days", age31to90);
        buckets.put("past_ttl", pastTtl);
        dist.put("ageBuckets", buckets);
        return dist;
    }

    public Map<String, Object> fairnessReport() {
        Path path = resolveFairnessPath();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", path.toString());
        if (!Files.isRegularFile(path)) {
            out.put("status", "evaluation_not_yet_run");
            out.put("message", "P12 evaluation output missing — run the benchmark harness to produce results.json");
            out.put("results", null);
            return out;
        }
        try {
            JsonNode root = objectMapper.readTree(Files.readString(path));
            out.put("status", "ok");
            out.put("message", null);
            out.put("results", objectMapper.convertValue(root, Map.class));
            return out;
        } catch (IOException e) {
            out.put("status", "unreadable");
            out.put("message", e.getMessage());
            out.put("results", null);
            return out;
        }
    }

    public Instant markPurgeCompleted() {
        Instant now = Instant.now();
        lastPurgeAt.set(now);
        Instant next = now.plus(compliance.purgeIntervalHours(), ChronoUnit.HOURS);
        nextPurgeAt.set(next);
        return next;
    }

    public Instant nextPurgeAt() {
        return nextPurgeAt.get();
    }

    public List<String> auditSessionIds() {
        return auditBlockRepository.findDistinctSessionIds();
    }

    private Path resolveFairnessPath() {
        String configured = compliance.fairnessResultsPath();
        Path cwd = Path.of(System.getProperty("user.dir"));
        List<Path> candidates = new java.util.ArrayList<>(List.of(
                Path.of(configured),
                cwd.resolve(configured),
                cwd.resolve("benchmarks").resolve("results.json"),
                cwd.resolve("..").resolve("benchmarks").resolve("results.json"),
                cwd.resolve("..").resolve(configured)
        ));
        // Classpath resource exported beside the running jar / test working dirs.
        try {
            var url = getClass().getClassLoader().getResource("benchmarks/results.json");
            if (url != null && "file".equals(url.getProtocol())) {
                candidates.add(0, Path.of(url.toURI()));
            }
        } catch (Exception ignored) {
            // fall through to filesystem candidates
        }
        for (Path candidate : candidates) {
            Path normalized = candidate.normalize();
            if (Files.isRegularFile(normalized)) {
                return normalized;
            }
        }
        return cwd.resolve(configured).normalize();
    }
}
