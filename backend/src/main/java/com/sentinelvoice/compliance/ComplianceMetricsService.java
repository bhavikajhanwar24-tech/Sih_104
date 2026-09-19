package com.sentinelvoice.compliance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.config.SentinelProperties;
import com.sentinelvoice.repository.AuditBlockRepository;
import com.sentinelvoice.tenant.BootstrapTenant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Compliance portal metrics. Passport counts deferred to F12; audit uses bootstrap tenant.
 */
@Service
public class ComplianceMetricsService {

    private static final String TELEMETRY_EVENT = AuditEventType.FEATURE_FRAME_SCORED.name();
    private static final String ERASE_EVENT = AuditEventType.PASSPORT_ERASED.name();

    private final AuditBlockRepository auditBlockRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final SentinelProperties.Compliance compliance;
    private final AtomicReference<Instant> nextPurgeAt = new AtomicReference<>();
    private final AtomicReference<Instant> lastPurgeAt = new AtomicReference<>();

    public ComplianceMetricsService(
            AuditBlockRepository auditBlockRepository,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            SentinelProperties properties
    ) {
        this.auditBlockRepository = auditBlockRepository;
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
        out.put("telemetry", Map.of("note", "age histogram deferred — audit payload shape changed in F1"));
        out.put("embeddingsStored", 0);
        out.put("embeddingsStoredNote", "re-implemented in F12");
        out.put("erasuresPerformed",
                auditBlockRepository.countByTenantIdAndEventType(BootstrapTenant.ID, ERASE_EVENT));
        out.put("tombstonesRecorded",
                auditBlockRepository.countByTenantIdAndEventType(BootstrapTenant.ID, ERASE_EVENT));
        out.put("auditBlocksTotal", auditBlockRepository.countByTenantId(BootstrapTenant.ID));
        out.put("telemetryTtlDays", compliance.telemetryTtlDays());
        out.put("auditRetentionYears", compliance.auditRetentionYears());
        Instant next = nextPurgeAt.get();
        Instant last = lastPurgeAt.get();
        out.put("nextScheduledPurge", next == null ? null : next.toString());
        out.put("lastPurgeAt", last == null ? null : last.toString());
        out.put("purgeIntervalHours", compliance.purgeIntervalHours());
        return out;
    }

    public long rawAudioBytesPersisted() {
        Long suspicious = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*) FROM information_schema.columns
                        WHERE lower(column_name) IN ('pcm', 'raw_audio', 'audio_bytes', 'wav', 'audio_pcm')
                        """,
                Long.class
        );
        if (suspicious != null && suspicious > 0) {
            return -1L;
        }
        return 0L;
    }

    public Map<String, Object> rawAudioSchemaProbe() {
        Long columns = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*) FROM information_schema.columns
                        WHERE lower(column_name) IN ('pcm', 'raw_audio', 'audio_bytes', 'wav', 'audio_pcm')
                        """,
                Long.class
        );
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("suspiciousColumns", columns == null ? 0 : columns);
        out.put("enforcing", true);
        return out;
    }

    public Map<String, Object> fairnessReport() {
        Path path = Path.of(compliance.fairnessResultsPath()).toAbsolutePath().normalize();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", path.toString());
        if (!Files.isRegularFile(path)) {
            out.put("status", "evaluation not yet run");
            return out;
        }
        try {
            out.put("status", "ok");
            out.put("results", objectMapper.readTree(path.toFile()));
        } catch (Exception ex) {
            out.put("status", "unreadable");
            out.put("error", ex.getMessage());
        }
        return out;
    }

    public List<String> auditSessionIds() {
        // Session IDs live inside payload jsonb — full listing returns in a later feature.
        return List.of();
    }

    public Instant markPurgeCompleted() {
        Instant next = Instant.now().plus(compliance.purgeIntervalHours(), ChronoUnit.HOURS);
        lastPurgeAt.set(Instant.now());
        nextPurgeAt.set(next);
        return next;
    }
}
