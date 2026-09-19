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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

    /**
     * Loads P12 {@code results.json} and shapes it for the portal chart contract:
     * {@code byLanguageGroup}, {@code byGender}, {@code byChannelProfile}, plus honest gap notes.
     */
    public Map<String, Object> fairnessReport() {
        Path path = resolveFairnessPath();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", path.toString());
        if (!Files.isRegularFile(path)) {
            out.put("status", "EVALUATION_NOT_RUN");
            out.put("message", "Evaluation not yet run — produce ml-engine/benchmarks/results.json via the harness");
            out.put("synthetic", null);
            out.put("results", null);
            return out;
        }
        try {
            JsonNode root = objectMapper.readTree(Files.readString(path));
            boolean synthetic = root.path("meta").path("synthetic").asBoolean(false);
            out.put("status", "ok");
            out.put("message", null);
            out.put("synthetic", synthetic);
            Map<String, Object> shaped = shapeFairnessForPortal(root);
            shaped.put("synthetic", synthetic);
            out.put("results", shaped);
            return out;
        } catch (IOException e) {
            out.put("status", "unreadable");
            out.put("message", e.getMessage());
            out.put("synthetic", null);
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

    @SuppressWarnings("unchecked")
    Map<String, Object> shapeFairnessForPortal(JsonNode root) {
        Map<String, Object> shaped = new LinkedHashMap<>();
        JsonNode fairness = root.path("fairness");
        String metric = textOr(fairness, "metric", "false_positive_rate");
        shaped.put("metric", metric);
        shaped.put("thresholdRule", textOr(fairness, "threshold_rule", "pooled_eer_threshold"));
        shaped.put(
                "generatedAt",
                textOr(root.path("meta"), "generated_at_utc", null)
        );
        shaped.put("sourceNote", textOr(fairness, "note", null));

        List<Map<String, Object>> byLanguage = new ArrayList<>();
        List<Map<String, Object>> byGender = new ArrayList<>();
        List<Map<String, Object>> byAge = new ArrayList<>();

        JsonNode groups = fairness.path("groups");
        if (groups.isArray()) {
            for (JsonNode g : groups) {
                String name = textOr(g, "group", "unknown");
                Map<String, Object> row = groupRow(g, name);
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.contains("gender")) {
                    byGender.add(row);
                } else if (lower.contains("age")) {
                    byAge.add(row);
                } else {
                    // indo_aryan / dravidian / language family labels
                    byLanguage.add(row);
                }
            }
        }

        List<Map<String, Object>> byChannel = channelFprFromCells(root.path("cells"));

        shaped.put("byLanguageGroup", byLanguage);
        shaped.put("byGender", byGender);
        shaped.put("byAgeBand", byAge);
        shaped.put("byChannelProfile", byChannel);

        String disparity = buildDisparityNotes(byLanguage, byGender, byChannel);
        shaped.put("disparityNotes", disparity);
        shaped.put("mitigations", List.of(
                "Report FPR parity by language family, gender, and channel — never hide the gap.",
                "Replace proxy group assignment with Mozilla Common Voice language tags when corpora are present.",
                "Enrol Voice Passport per channel profile (wideband + narrowband) to cut channel-driven FPR lift.",
                "Corroboration gate (Scenario 5) keeps acoustic-only spikes from escalating past L2."
        ));
        return shaped;
    }

    private static Map<String, Object> groupRow(JsonNode g, String name) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("group", name);
        row.put("n", g.path("n").asInt(0));
        row.put("nBonafide", g.path("n_bonafide").asInt(0));
        row.put("fpr", g.path("fpr").isMissingNode() || g.path("fpr").isNull()
                ? null
                : g.path("fpr").asDouble());
        row.put("note", textOr(g, "note", null));
        return row;
    }

    private List<Map<String, Object>> channelFprFromCells(JsonNode cells) {
        // Mean FPR@TPR=0.90 per channel_condition across models/datasets (real cell metrics).
        Map<String, List<Double>> byChannel = new LinkedHashMap<>();
        if (cells != null && cells.isArray()) {
            for (JsonNode cell : cells) {
                String ch = textOr(cell, "channel_condition", null);
                if (ch == null || ch.isBlank()) {
                    continue;
                }
                JsonNode fprNode = cell.path("metrics").path("fpr_at_tpr").path("0.90").path("fpr");
                if (fprNode.isMissingNode() || fprNode.isNull()) {
                    continue;
                }
                byChannel.computeIfAbsent(ch, k -> new ArrayList<>()).add(fprNode.asDouble());
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, List<Double>> e : byChannel.entrySet()) {
            List<Double> vals = e.getValue();
            double mean = vals.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("group", e.getKey());
            row.put("n", vals.size());
            row.put("nBonafide", null);
            row.put("fpr", round6(mean));
            row.put("note", "mean FPR@TPR=0.90 across P12 cells for this channel");
            rows.add(row);
        }
        rows.sort(Comparator.comparing(r -> String.valueOf(r.get("group"))));
        return rows;
    }

    private static String buildDisparityNotes(
            List<Map<String, Object>> language,
            List<Map<String, Object>> gender,
            List<Map<String, Object>> channel
    ) {
        String langGap = gapSentence("language family", language);
        String genderGap = gapSentence("gender", gender);
        String channelGap = gapSentence("channel profile", channel);
        return String.join(" ", List.of(
                "Honest FPR gaps (Context §13.5) — a flat chart would be less credible than a visible disparity.",
                langGap,
                genderGap,
                channelGap,
                "We surface the gap, document mitigations, and refuse to ship a model that interrogates one language community more than another without scrutiny."
        ));
    }

    private static String gapSentence(String facet, List<Map<String, Object>> rows) {
        Double min = null;
        Double max = null;
        String minG = null;
        String maxG = null;
        for (Map<String, Object> row : rows) {
            Object fprObj = row.get("fpr");
            if (!(fprObj instanceof Number n)) {
                continue;
            }
            double fpr = n.doubleValue();
            String g = String.valueOf(row.get("group"));
            if (min == null || fpr < min) {
                min = fpr;
                minG = g;
            }
            if (max == null || fpr > max) {
                max = fpr;
                maxG = g;
            }
        }
        if (min == null || max == null) {
            return "No " + facet + " FPR rows yet.";
        }
        double gap = max - min;
        return String.format(
                Locale.ROOT,
                "%s: highest FPR %.1f%% (%s) vs lowest %.1f%% (%s) — gap %.1f pp.",
                capitalize(facet),
                max * 100.0,
                maxG,
                min * 100.0,
                minG,
                gap * 100.0
        );
    }

    private Path resolveFairnessPath() {
        String configured = compliance.fairnessResultsPath();
        Path cwd = Path.of(System.getProperty("user.dir"));
        List<Path> candidates = List.of(
                Path.of(configured),
                cwd.resolve(configured),
                cwd.resolve("ml-engine").resolve("benchmarks").resolve("results.json"),
                cwd.resolve("..").resolve("ml-engine").resolve("benchmarks").resolve("results.json"),
                cwd.resolve("..").resolve(configured)
        );
        // Never fall back to a classpath-bundled results.json — that invited fabricated numbers.
        for (Path candidate : candidates) {
            Path normalized = candidate.normalize();
            if (Files.isRegularFile(normalized)) {
                return normalized;
            }
        }
        return cwd.resolve("ml-engine").resolve("benchmarks").resolve("results.json").normalize();
    }

    private static String textOr(JsonNode node, String field, String fallback) {
        if (node == null || node.isMissingNode()) {
            return fallback;
        }
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return fallback;
        }
        String s = v.asText();
        return s == null || s.isBlank() ? fallback : s;
    }

    private static double round6(double v) {
        return Math.round(v * 1_000_000.0) / 1_000_000.0;
    }

    private static String capitalize(String s) {
        if (s == null || s.isBlank()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
