package com.sentinelvoice.analytics;

import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.audit.AuditWriteDispatcher;
import com.sentinelvoice.fusion.config.FusionConfigDocument;
import com.sentinelvoice.fusion.config.FusionConfigService;
import com.sentinelvoice.security.TenantContext;
import com.sentinelvoice.telephony.CallSessionRepository;
import com.sentinelvoice.telephony.TelephonyModels;
import com.sentinelvoice.tenant.TenantSettingsEntity;
import com.sentinelvoice.tenant.TenantSettingsRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * F16 — session labels, tenant analytics metrics, L3 threshold suggestions, fairness, rule quality.
 */
@Service
public class AnalyticsService {

    public static final Set<String> LABELS = Set.of(
            "CONFIRMED_FRAUD", "FALSE_POSITIVE", "BENIGN_HIGH_RISK", "UNKNOWN"
    );

    private static final List<String> FAIRNESS_KEYS = List.of("language", "region", "gender", "ageBand");
    private static final List<String> FAMILIES = List.of(
            "voice", "channel", "prosody", "linguistic", "transaction", "relationship"
    );

    private final JdbcTemplate jdbc;
    private final CallSessionRepository callSessionRepository;
    private final TenantSettingsRepository settingsRepository;
    private final AuditWriteDispatcher auditWriteDispatcher;
    private final FusionConfigService fusionConfigService;

    public AnalyticsService(
            JdbcTemplate jdbc,
            CallSessionRepository callSessionRepository,
            TenantSettingsRepository settingsRepository,
            AuditWriteDispatcher auditWriteDispatcher,
            FusionConfigService fusionConfigService
    ) {
        this.jdbc = jdbc;
        this.callSessionRepository = callSessionRepository;
        this.settingsRepository = settingsRepository;
        this.auditWriteDispatcher = auditWriteDispatcher;
        this.fusionConfigService = fusionConfigService;
    }

    public Map<String, Object> getLabel(UUID tenantId, String sessionKey) {
        TelephonyModels.CallSessionDetail cs = requireSession(tenantId, sessionKey);
        List<Map<String, Object>> rows = jdbc.query(
                """
                SELECT label, labelled_by, note, labelled_at
                FROM session_labels
                WHERE tenant_id = ? AND session_id = ?
                """,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("label", rs.getString("label"));
                    UUID by = rs.getObject("labelled_by", UUID.class);
                    m.put("labelledBy", by == null ? null : by.toString());
                    m.put("note", rs.getString("note"));
                    Timestamp at = rs.getTimestamp("labelled_at");
                    m.put("labelledAt", at == null ? null : at.toInstant().toString());
                    return m;
                },
                tenantId, cs.id()
        );
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", "2");
        out.put("tenantId", tenantId.toString());
        out.put("sessionId", cs.id().toString());
        out.put("svSessionUuid", cs.svSessionUuid() == null ? null : cs.svSessionUuid().toString());
        if (rows.isEmpty()) {
            out.put("label", null);
            out.put("labelledBy", null);
            out.put("note", null);
            out.put("labelledAt", null);
        } else {
            out.putAll(rows.get(0));
        }
        return out;
    }

    @Transactional
    public Map<String, Object> upsertLabel(
            UUID tenantId,
            UUID userId,
            String sessionKey,
            String rawLabel,
            String note,
            boolean syncReviewStatus
    ) {
        TelephonyModels.CallSessionDetail cs = requireSession(tenantId, sessionKey);
        String label = rawLabel == null ? "" : rawLabel.trim().toUpperCase(Locale.ROOT);
        if (!LABELS.contains(label)) {
            throw new AnalyticsException(
                    "INVALID_LABEL",
                    "label must be CONFIRMED_FRAUD, FALSE_POSITIVE, BENIGN_HIGH_RISK, or UNKNOWN"
            );
        }
        String noteClean = note == null || note.isBlank() ? null : note.trim();
        if (noteClean != null && noteClean.length() > 2000) {
            noteClean = noteClean.substring(0, 2000);
        }

        String previous = jdbc.query(
                "SELECT label FROM session_labels WHERE tenant_id = ? AND session_id = ?",
                rs -> rs.next() ? rs.getString(1) : null,
                tenantId, cs.id()
        );

        jdbc.update(
                """
                INSERT INTO session_labels (tenant_id, session_id, label, labelled_by, note, labelled_at, updated_at)
                VALUES (?, ?, ?, ?, ?, now(), now())
                ON CONFLICT (tenant_id, session_id) DO UPDATE SET
                    label = EXCLUDED.label,
                    labelled_by = EXCLUDED.labelled_by,
                    note = EXCLUDED.note,
                    labelled_at = now(),
                    updated_at = now()
                """,
                tenantId, cs.id(), label, userId, noteClean
        );

        if (syncReviewStatus && ("CONFIRMED_FRAUD".equals(label) || "FALSE_POSITIVE".equals(label))) {
            callSessionRepository.updateReviewStatus(tenantId, cs.id(), label, userId);
        }

        String sessionAuditKey = cs.svSessionUuid() == null ? cs.id().toString() : cs.svSessionUuid().toString();
        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("callSessionId", cs.id().toString());
        auditPayload.put("label", label);
        auditPayload.put("previousLabel", previous == null ? "" : previous);
        auditPayload.put("labelledBy", userId == null ? "" : userId.toString());
        if (noteClean != null) {
            auditPayload.put("notePresent", true);
        }
        auditWriteDispatcher.submit(sessionAuditKey, AuditEventType.SESSION_LABELLED, auditPayload);

        return getLabel(tenantId, sessionKey);
    }

    /** Called from F12 review so session_labels stay in sync. */
    @Transactional
    public void syncFromReview(UUID tenantId, UUID userId, UUID callSessionId, String reviewStatus) {
        if (!"CONFIRMED_FRAUD".equals(reviewStatus) && !"FALSE_POSITIVE".equals(reviewStatus)) {
            return;
        }
        jdbc.update(
                """
                INSERT INTO session_labels (tenant_id, session_id, label, labelled_by, labelled_at, updated_at)
                VALUES (?, ?, ?, ?, now(), now())
                ON CONFLICT (tenant_id, session_id) DO UPDATE SET
                    label = EXCLUDED.label,
                    labelled_by = EXCLUDED.labelled_by,
                    labelled_at = now(),
                    updated_at = now()
                """,
                tenantId, callSessionId, reviewStatus, userId
        );
    }

    public Map<String, Object> overview(int days) {
        UUID tenantId = TenantContext.require().tenantId();
        int window = Math.max(1, Math.min(days, 365));
        Instant from = Instant.now().minus(window, ChronoUnit.DAYS);
        TenantSettingsEntity settings = settingsOrDefaults(tenantId);
        int warnBelow = settings.getAnalyticsLabelWarnBelow();

        int labelCount = countLabels(tenantId, from);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", "2");
        body.put("tenantId", tenantId.toString());
        body.put("days", window);
        body.put("labelledSessions", labelCount);
        body.put("lowDataWarning", labelCount < warnBelow);
        body.put("labelWarnBelow", warnBelow);
        try {
            body.put("precisionProxy", precisionProxy(tenantId, from));
        } catch (Exception ex) {
            body.put("precisionProxy", Map.of());
        }
        try {
            body.put("fpRateByLevel", fpRateByLevel(tenantId, from));
        } catch (Exception ex) {
            body.put("fpRateByLevel", List.of());
        }
        try {
            body.put("alertVolumeDaily", alertVolumeDaily(tenantId, window));
        } catch (Exception ex) {
            body.put("alertVolumeDaily", List.of());
        }
        try {
            body.put("timeToLabel", timeToLabelStats(tenantId, from));
        } catch (Exception ex) {
            body.put("timeToLabel", Map.of("labelled", 0, "avgSec", null, "p50Sec", null));
        }
        try {
            body.put("familyContribution", familyContribution(tenantId, from));
        } catch (Exception ex) {
            body.put("familyContribution", List.of());
        }
        try {
            body.put("rulesNeedingReview", rulesNeedingReview(tenantId).stream().limit(8).toList());
        } catch (Exception ex) {
            body.put("rulesNeedingReview", List.of());
        }
        return body;
    }

    public Map<String, Object> ruleQuality() {
        UUID tenantId = TenantContext.require().tenantId();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", "2");
        body.put("tenantId", tenantId.toString());
        body.put("items", ruleQualityRows(tenantId));
        return body;
    }

    public List<Map<String, Object>> rulesNeedingReview(UUID tenantId) {
        return ruleQualityRows(tenantId).stream()
                .filter(r -> Boolean.TRUE.equals(r.get("needsReview")))
                .toList();
    }

    public Map<String, Object> fairnessReport() {
        UUID tenantId = TenantContext.require().tenantId();
        // Show FP rates for any n≥1 (no lab-blocking floor of 30).
        int minGroup = 1;
        Instant from = Instant.now().minus(90, ChronoUnit.DAYS);

        List<Map<String, Object>> dimensions = new ArrayList<>();
        for (String key : FAIRNESS_KEYS) {
            try {
                dimensions.add(fairnessDimension(tenantId, key, minGroup, from));
            } catch (Exception ex) {
                Map<String, Object> dim = new LinkedHashMap<>();
                dim.put("key", key);
                dim.put("groups", List.of());
                dim.put("usableGroups", 0);
                dim.put("error", ex.getMessage());
                dimensions.add(dim);
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", "2");
        body.put("tenantId", tenantId.toString());
        body.put("status", "OK");
        body.put("source", "directory_fairness_tags");
        body.put("message", "FP rates use tenant-supplied directory fairness_tags only; never inferred from voice.");
        body.put("minGroupSize", minGroup);
        body.put("dimensions", dimensions);
        body.put("synthetic", false);
        return body;
    }

    public Map<String, Object> thresholdSuggestion() {
        UUID tenantId = TenantContext.require().tenantId();
        TenantSettingsEntity settings = settingsOrDefaults(tenantId);
        int warnBelow = settings.getAnalyticsLabelWarnBelow();

        double currentEnter = currentL3Enter(tenantId);
        List<LabelledScore> samples = labelledPeakScores(tenantId);
        int n = samples.size();

        List<Map<String, Object>> curve = new ArrayList<>();
        for (int i = 25; i <= 90; i += 5) {
            double thr = i / 100.0;
            curve.add(operatingPoint(samples, thr, Math.abs(thr - currentEnter) < 0.001));
        }
        // Ensure current threshold appears exactly
        boolean hasCurrent = curve.stream()
                .anyMatch(p -> Math.abs(((Number) p.get("threshold")).doubleValue() - currentEnter) < 0.0005);
        if (!hasCurrent) {
            curve.add(operatingPoint(samples, currentEnter, true));
            curve.sort((a, b) -> Double.compare(
                    ((Number) a.get("threshold")).doubleValue(),
                    ((Number) b.get("threshold")).doubleValue()
            ));
        }

        Map<String, Object> atCurrent = operatingPoint(samples, currentEnter, true);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", "2");
        body.put("tenantId", tenantId.toString());
        body.put("currentL3Enter", round4(currentEnter));
        body.put("sampleSize", n);
        body.put("lowDataWarning", n < warnBelow);
        body.put("labelWarnBelow", warnBelow);
        body.put("atCurrent", atCurrent);
        body.put("curve", curve);
        body.put("summary", buildThresholdSummary(atCurrent, curve, currentEnter, n, warnBelow));
        return body;
    }

    @Transactional
    public Map<String, Object> createDraftFromSuggestion(Double l3EnterOverride) {
        UUID tenantId = TenantContext.require().tenantId();
        double suggested = l3EnterOverride == null ? pickSuggestedThreshold(tenantId) : l3EnterOverride;
        if (suggested < 0.20 || suggested > 0.95) {
            throw new AnalyticsException("INVALID_THRESHOLD", "l3Enter must be between 0.20 and 0.95");
        }

        Map<String, Object> active = fusionConfigService.getActive();
        @SuppressWarnings("unchecked")
        Map<String, Object> config = active.get("config") instanceof Map<?, ?> m
                ? new LinkedHashMap<>((Map<String, Object>) m)
                : FusionConfigDocument.platformDefault().toCanonicalMap();

        @SuppressWarnings("unchecked")
        Map<String, Object> levels = config.get("levels") instanceof Map<?, ?> lm
                ? new LinkedHashMap<>((Map<String, Object>) lm)
                : new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> l3 = levels.get("L3") instanceof Map<?, ?> band
                ? new LinkedHashMap<>((Map<String, Object>) band)
                : new LinkedHashMap<>(Map.of("enter", suggested, "exit", suggested - 0.09, "minDwellMs", 1000));

        double enter = suggested;
        double exit = l3.get("exit") instanceof Number n ? n.doubleValue() : enter - 0.09;
        if (exit >= enter) {
            exit = Math.max(0.05, enter - 0.09);
        }
        l3.put("enter", round4(enter));
        l3.put("exit", round4(exit));
        if (!l3.containsKey("minDwellMs")) {
            l3.put("minDwellMs", 1000);
        }
        levels.put("L3", l3);
        config.put("levels", levels);

        Map<String, Object> draft = fusionConfigService.createDraftFromActive(config);
        Map<String, Object> out = new LinkedHashMap<>(draft);
        out.put("suggestedL3Enter", round4(enter));
        out.put("source", "F16_THRESHOLD_SUGGESTION");
        return out;
    }

    public Map<String, Object> falsePositiveSnapshot(UUID tenantId) {
        Instant from = Instant.now().minus(30, ChronoUnit.DAYS);
        Map<String, Object> proxy = precisionProxy(tenantId, from);
        int labelled = countLabels(tenantId, from);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("available", labelled > 0);
        m.put("windowDays", 30);
        m.put("labelledSessions", labelled);
        Object fpRate = proxy.get("falsePositiveRate");
        m.put("rate", fpRate);
        m.put("precisionProxy", proxy.get("precision"));
        m.put("alertsL3Plus", proxy.get("alertsL3Plus"));
        m.put("confirmedFraud", proxy.get("confirmedFraud"));
        m.put("falsePositives", proxy.get("falsePositives"));
        if (labelled == 0) {
            m.put("note", "No session labels in the last 30 days");
        }
        return m;
    }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    private TelephonyModels.CallSessionDetail requireSession(UUID tenantId, String sessionKey) {
        return callSessionRepository.findDetailByIdOrSvSession(tenantId, sessionKey)
                .orElseThrow(() -> new AnalyticsException("NOT_FOUND", "Session not found"));
    }

    private TenantSettingsEntity settingsOrDefaults(UUID tenantId) {
        return settingsRepository.findById(tenantId).orElseGet(() -> {
            TenantSettingsEntity e = new TenantSettingsEntity();
            e.setTenantId(tenantId);
            return e;
        });
    }

    private int countLabels(UUID tenantId, Instant from) {
        Integer n = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM session_labels
                WHERE tenant_id = ? AND labelled_at >= ?
                """,
                Integer.class, tenantId, Timestamp.from(from)
        );
        return n == null ? 0 : n;
    }

    private Map<String, Object> precisionProxy(UUID tenantId, Instant from) {
        return jdbc.query(
                """
                SELECT
                  COUNT(*) FILTER (
                    WHERE cs.peak_level LIKE 'LEVEL_3%%'
                       OR cs.peak_level LIKE 'LEVEL_4%%'
                       OR cs.peak_level LIKE 'LEVEL_5%%'
                       OR COALESCE(cs.peak_score, 0) >= 0.70
                  ) AS alerts,
                  COUNT(*) FILTER (
                    WHERE sl.label = 'CONFIRMED_FRAUD'
                      AND (cs.peak_level LIKE 'LEVEL_3%%'
                           OR cs.peak_level LIKE 'LEVEL_4%%'
                           OR cs.peak_level LIKE 'LEVEL_5%%'
                           OR COALESCE(cs.peak_score, 0) >= 0.70)
                  ) AS confirmed,
                  COUNT(*) FILTER (
                    WHERE sl.label = 'FALSE_POSITIVE'
                      AND (cs.peak_level LIKE 'LEVEL_3%%'
                           OR cs.peak_level LIKE 'LEVEL_4%%'
                           OR cs.peak_level LIKE 'LEVEL_5%%'
                           OR COALESCE(cs.peak_score, 0) >= 0.70)
                  ) AS fps
                FROM call_sessions cs
                LEFT JOIN session_labels sl
                  ON sl.tenant_id = cs.tenant_id AND sl.session_id = cs.id
                WHERE cs.tenant_id = ? AND cs.started_at >= ?
                """,
                rs -> {
                    rs.next();
                    int alerts = rs.getInt("alerts");
                    int confirmed = rs.getInt("confirmed");
                    int fps = rs.getInt("fps");
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("alertsL3Plus", alerts);
                    m.put("confirmedFraud", confirmed);
                    m.put("falsePositives", fps);
                    m.put("precision", alerts == 0 ? null : round4((double) confirmed / alerts));
                    m.put("falsePositiveRate", alerts == 0 ? null : round4((double) fps / alerts));
                    return m;
                },
                tenantId, Timestamp.from(from)
        );
    }

    private List<Map<String, Object>> fpRateByLevel(UUID tenantId, Instant from) {
        return jdbc.query(
                """
                SELECT COALESCE(cs.peak_level, 'UNKNOWN') AS lvl,
                       COUNT(*) AS total,
                       COUNT(*) FILTER (WHERE sl.label = 'FALSE_POSITIVE') AS fps,
                       COUNT(*) FILTER (WHERE sl.label = 'CONFIRMED_FRAUD') AS tps
                FROM call_sessions cs
                LEFT JOIN session_labels sl
                  ON sl.tenant_id = cs.tenant_id AND sl.session_id = cs.id
                WHERE cs.tenant_id = ? AND cs.started_at >= ?
                GROUP BY 1
                ORDER BY 2 DESC
                """,
                (rs, i) -> {
                    int total = rs.getInt("total");
                    int fps = rs.getInt("fps");
                    int tps = rs.getInt("tps");
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("level", rs.getString("lvl"));
                    m.put("calls", total);
                    m.put("falsePositives", fps);
                    m.put("confirmedFraud", tps);
                    m.put("fpRate", total == 0 ? null : round4((double) fps / total));
                    return m;
                },
                tenantId, Timestamp.from(from)
        );
    }

    private List<Map<String, Object>> alertVolumeDaily(UUID tenantId, int days) {
        return jdbc.query(
                """
                SELECT day_utc, alerts_l3_plus, calls_total
                FROM v_analytics_alert_daily
                WHERE tenant_id = ?
                  AND day_utc >= (CURRENT_DATE - (?::int))
                ORDER BY day_utc
                """,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("day", rs.getDate("day_utc").toLocalDate().toString());
                    m.put("alertsL3Plus", rs.getInt("alerts_l3_plus"));
                    m.put("callsTotal", rs.getInt("calls_total"));
                    return m;
                },
                tenantId, days
        );
    }

    private Map<String, Object> timeToLabelStats(UUID tenantId, Instant from) {
        return jdbc.query(
                """
                SELECT
                  COUNT(*) AS n,
                  AVG(EXTRACT(EPOCH FROM (sl.labelled_at - cs.started_at))) AS avg_sec,
                  PERCENTILE_CONT(0.5) WITHIN GROUP (
                    ORDER BY EXTRACT(EPOCH FROM (sl.labelled_at - cs.started_at))
                  ) AS p50_sec
                FROM session_labels sl
                JOIN call_sessions cs ON cs.id = sl.session_id AND cs.tenant_id = sl.tenant_id
                WHERE sl.tenant_id = ? AND sl.labelled_at >= ?
                  AND cs.started_at IS NOT NULL
                """,
                rs -> {
                    rs.next();
                    Map<String, Object> m = new LinkedHashMap<>();
                    int n = rs.getInt("n");
                    m.put("labelled", n);
                    Double avg = toDouble(rs.getObject("avg_sec"));
                    Double p50 = toDouble(rs.getObject("p50_sec"));
                    m.put("avgSec", avg == null ? null : Math.round(avg * 10.0) / 10.0);
                    m.put("p50Sec", p50 == null ? null : Math.round(p50 * 10.0) / 10.0);
                    return m;
                },
                tenantId, Timestamp.from(from)
        );
    }

    private List<Map<String, Object>> familyContribution(UUID tenantId, Instant from) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String family : FAMILIES) {
            Double avg = null;
            try {
                avg = jdbc.queryForObject(
                        """
                        SELECT AVG( (tick.family_scores ->> ?) :: double precision )
                        FROM session_ticks tick
                        JOIN call_sessions cs
                          ON cs.sv_session_uuid = tick.session_id AND cs.tenant_id = tick.tenant_id
                        JOIN session_labels sl
                          ON sl.tenant_id = cs.tenant_id AND sl.session_id = cs.id
                        WHERE tick.tenant_id = ?
                          AND sl.labelled_at >= ?
                          AND tick.family_scores ? ?
                          AND (tick.family_scores ->> ?) ~ '^[0-9]+(\\.[0-9]+)?$'
                        """,
                        Double.class, family, tenantId, Timestamp.from(from), family, family
                );
            } catch (Exception ignored) {
                avg = null;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("family", family);
            row.put("avgScoreOnLabelled", avg == null ? null : round4(avg));
            out.add(row);
        }
        return out;
    }

    private List<Map<String, Object>> ruleQualityRows(UUID tenantId) {
        TenantSettingsEntity settings = settingsOrDefaults(tenantId);
        double fireMax = settings.getAnalyticsRuleFireRateMax();
        double fpMax = settings.getAnalyticsRuleFpContribMax();

        Integer totalSessions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM call_sessions WHERE tenant_id = ?",
                Integer.class, tenantId
        );
        int denom = totalSessions == null || totalSessions == 0 ? 1 : totalSessions;

        Integer fpSessions = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM session_labels
                WHERE tenant_id = ? AND label = 'FALSE_POSITIVE'
                """,
                Integer.class, tenantId
        );
        int fpDenom = fpSessions == null || fpSessions == 0 ? 0 : fpSessions;

        List<Map<String, Object>> fires;
        try {
            fires = jdbc.query(
                    """
                    SELECT rule_id, sessions_fired
                    FROM mv_analytics_rule_fires
                    WHERE tenant_id = ?
                    ORDER BY sessions_fired DESC
                    LIMIT 100
                    """,
                    (rs, i) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("ruleId", rs.getString("rule_id"));
                        m.put("sessionsFired", rs.getInt("sessions_fired"));
                        return m;
                    },
                    tenantId
            );
        } catch (Exception ex) {
            fires = jdbc.query(
                    """
                    SELECT unnest(fired_rule_ids) AS rule_id, COUNT(DISTINCT session_id) AS sessions_fired
                    FROM session_ticks
                    WHERE tenant_id = ? AND cardinality(fired_rule_ids) > 0
                    GROUP BY 1
                    ORDER BY 2 DESC
                    LIMIT 100
                    """,
                    (rs, i) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("ruleId", rs.getString("rule_id"));
                        m.put("sessionsFired", rs.getInt("sessions_fired"));
                        return m;
                    },
                    tenantId
            );
        }

        Map<String, Integer> fpHits = new LinkedHashMap<>();
        if (fpDenom > 0) {
            jdbc.query(
                    """
                    SELECT unnest(st.fired_rule_ids) AS rule_id, COUNT(DISTINCT cs.id) AS cnt
                    FROM session_ticks st
                    JOIN call_sessions cs
                      ON cs.sv_session_uuid = st.session_id AND cs.tenant_id = st.tenant_id
                    JOIN session_labels sl
                      ON sl.tenant_id = cs.tenant_id AND sl.session_id = cs.id
                    WHERE st.tenant_id = ?
                      AND sl.label = 'FALSE_POSITIVE'
                      AND cardinality(st.fired_rule_ids) > 0
                    GROUP BY 1
                    """,
                    rs -> {
                        while (rs.next()) {
                            fpHits.put(rs.getString("rule_id"), rs.getInt("cnt"));
                        }
                        return null;
                    },
                    tenantId
            );
        }

        Map<String, List<Integer>> sparks = sparklineSeries(tenantId);

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> fire : fires) {
            String ruleId = String.valueOf(fire.get("ruleId"));
            int sessionsFired = ((Number) fire.get("sessionsFired")).intValue();
            double fireRate = (double) sessionsFired / denom;
            int fpHit = fpHits.getOrDefault(ruleId, 0);
            Double fpContrib = fpDenom == 0 ? null : (double) fpHit / fpDenom;
            boolean needsReview = fireRate > fireMax
                    || (fpContrib != null && fpContrib > fpMax);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ruleId", ruleId);
            row.put("sessionsFired", sessionsFired);
            row.put("fireRate", round4(fireRate));
            row.put("fpContribution", fpContrib == null ? null : round4(fpContrib));
            row.put("fpSessionsHit", fpHit);
            row.put("needsReview", needsReview);
            row.put("reviewReasons", needsReviewReasons(fireRate, fireMax, fpContrib, fpMax));
            row.put("sparkline", sparks.getOrDefault(ruleId, List.of()));
            out.add(row);
        }
        out.sort((a, b) -> Boolean.compare(
                Boolean.TRUE.equals(b.get("needsReview")),
                Boolean.TRUE.equals(a.get("needsReview"))
        ));
        return out;
    }

    private List<String> needsReviewReasons(double fireRate, double fireMax, Double fpContrib, double fpMax) {
        List<String> reasons = new ArrayList<>();
        if (fireRate > fireMax) {
            reasons.add("fire_rate_above_limit");
        }
        if (fpContrib != null && fpContrib > fpMax) {
            reasons.add("fp_contribution_high");
        }
        return reasons;
    }

    private Map<String, List<Integer>> sparklineSeries(UUID tenantId) {
        Map<String, List<Integer>> out = new LinkedHashMap<>();
        try {
            jdbc.query(
                    """
                    SELECT unnest(st.fired_rule_ids) AS rule_id,
                           (cs.started_at AT TIME ZONE 'UTC')::date AS day_utc,
                           COUNT(DISTINCT cs.id) AS cnt
                    FROM session_ticks st
                    JOIN call_sessions cs
                      ON cs.sv_session_uuid = st.session_id AND cs.tenant_id = st.tenant_id
                    WHERE st.tenant_id = ?
                      AND cs.started_at >= now() - interval '14 days'
                      AND cardinality(st.fired_rule_ids) > 0
                    GROUP BY 1, 2
                    ORDER BY 1, 2
                    """,
                    rs -> {
                        while (rs.next()) {
                            String ruleId = rs.getString("rule_id");
                            out.computeIfAbsent(ruleId, k -> new ArrayList<>()).add(rs.getInt("cnt"));
                        }
                        return null;
                    },
                    tenantId
            );
        } catch (Exception ignored) {
            /* optional */
        }
        return out;
    }

    private Map<String, Object> fairnessDimension(UUID tenantId, String key, int minGroup, Instant from) {
        List<Map<String, Object>> groups = jdbc.query(
                """
                SELECT COALESCE(e.fairness_tags ->> ?, '(untagged)') AS grp,
                       COUNT(*) AS n,
                       COUNT(*) FILTER (WHERE sl.label = 'FALSE_POSITIVE') AS fps
                FROM session_labels sl
                JOIN call_sessions cs ON cs.id = sl.session_id AND cs.tenant_id = sl.tenant_id
                LEFT JOIN employees e ON e.id = cs.caller_employee_id AND e.tenant_id = cs.tenant_id
                WHERE sl.tenant_id = ?
                  AND sl.labelled_at >= ?
                  AND (
                    COALESCE(cs.peak_level, '') LIKE 'LEVEL_3%%'
                    OR COALESCE(cs.peak_level, '') LIKE 'LEVEL_4%%'
                    OR COALESCE(cs.peak_level, '') LIKE 'LEVEL_5%%'
                    OR COALESCE(cs.peak_score, 0) >= 0.70
                  )
                GROUP BY 1
                ORDER BY 2 DESC
                """,
                (rs, i) -> {
                    int n = rs.getInt("n");
                    int fps = rs.getInt("fps");
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("group", rs.getString("grp"));
                    m.put("n", n);
                    m.put("falsePositives", fps);
                    if (n < minGroup) {
                        m.put("insufficientData", true);
                        m.put("fpRate", null);
                        m.put("ciLow", null);
                        m.put("ciHigh", null);
                    } else {
                        double p = (double) fps / n;
                        double[] ci = wilsonInterval(p, n);
                        m.put("insufficientData", false);
                        m.put("fpRate", round4(p));
                        m.put("ciLow", round4(ci[0]));
                        m.put("ciHigh", round4(ci[1]));
                    }
                    return m;
                },
                key, tenantId, Timestamp.from(from)
        );

        Map<String, Object> dim = new LinkedHashMap<>();
        dim.put("key", key);
        dim.put("groups", groups);
        long usable = groups.stream().filter(g -> !Boolean.TRUE.equals(g.get("insufficientData"))).count();
        dim.put("usableGroups", usable);
        return dim;
    }

    /** Wilson score interval (95%). */
    static double[] wilsonInterval(double p, int n) {
        if (n <= 0) {
            return new double[]{0, 0};
        }
        double z = 1.96;
        double z2 = z * z;
        double denom = 1.0 + z2 / n;
        double centre = (p + z2 / (2.0 * n)) / denom;
        double margin = (z * Math.sqrt((p * (1.0 - p) + z2 / (4.0 * n)) / n)) / denom;
        return new double[]{Math.max(0, centre - margin), Math.min(1, centre + margin)};
    }

    private double currentL3Enter(UUID tenantId) {
        try {
            Map<String, Object> active = fusionConfigService.getActive();
            Object cfg = active.get("config");
            FusionConfigDocument doc = FusionConfigDocument.parse(cfg);
            return doc.level("L3").enter();
        } catch (Exception ex) {
            return 0.55;
        }
    }

    private List<LabelledScore> labelledPeakScores(UUID tenantId) {
        return jdbc.query(
                """
                SELECT sl.label,
                       COALESCE(
                         (SELECT MAX(st.score) FROM session_ticks st
                          WHERE st.tenant_id = cs.tenant_id
                            AND st.session_id = cs.sv_session_uuid),
                         cs.peak_score,
                         0
                       ) AS peak
                FROM session_labels sl
                JOIN call_sessions cs ON cs.id = sl.session_id AND cs.tenant_id = sl.tenant_id
                WHERE sl.tenant_id = ?
                  AND sl.label IN ('CONFIRMED_FRAUD', 'FALSE_POSITIVE', 'BENIGN_HIGH_RISK')
                """,
                (rs, i) -> new LabelledScore(rs.getString("label"), rs.getDouble("peak")),
                tenantId
        );
    }

    private Map<String, Object> operatingPoint(List<LabelledScore> samples, double threshold, boolean current) {
        int alerts = 0;
        int fps = 0;
        int tps = 0;
        for (LabelledScore s : samples) {
            if (s.peakScore() >= threshold) {
                alerts++;
                if ("FALSE_POSITIVE".equals(s.label())) {
                    fps++;
                } else if ("CONFIRMED_FRAUD".equals(s.label())) {
                    tps++;
                }
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("threshold", round4(threshold));
        m.put("current", current);
        m.put("alerts", alerts);
        m.put("falsePositives", fps);
        m.put("confirmedFraud", tps);
        m.put("fpRate", alerts == 0 ? null : round4((double) fps / alerts));
        m.put("precision", alerts == 0 ? null : round4((double) tps / alerts));
        return m;
    }

    private String buildThresholdSummary(
            Map<String, Object> atCurrent,
            List<Map<String, Object>> curve,
            double currentEnter,
            int n,
            int warnBelow
    ) {
        if (n < warnBelow) {
            return "Low data: only " + n + " labelled sessions (need ≥" + warnBelow
                    + "). Treat suggestions as directional.";
        }
        Object fpCur = atCurrent.get("fpRate");
        String fpCurPct = fpCur == null ? "n/a" : String.format(Locale.ROOT, "%.1f%%", ((Number) fpCur).doubleValue() * 100);

        Map<String, Object> lower = null;
        for (Map<String, Object> p : curve) {
            double thr = ((Number) p.get("threshold")).doubleValue();
            if (thr < currentEnter - 0.01) {
                if (lower == null || thr > ((Number) lower.get("threshold")).doubleValue()) {
                    lower = p;
                }
            }
        }
        if (lower == null) {
            return "At current L3 enter=" + round4(currentEnter) + " FP rate = " + fpCurPct + ".";
        }
        Object fpLow = lower.get("fpRate");
        String fpLowPct = fpLow == null ? "n/a" : String.format(Locale.ROOT, "%.1f%%", ((Number) fpLow).doubleValue() * 100);
        Object alertsLow = lower.get("alerts");
        Object alertsCur = atCurrent.get("alerts");
        return "At current threshold FP rate = " + fpCurPct
                + "; lowering to " + lower.get("threshold")
                + " gives FP rate = " + fpLowPct
                + " (alerts " + alertsCur + " → " + alertsLow + ").";
    }

    private double pickSuggestedThreshold(UUID tenantId) {
        Map<String, Object> suggestion = thresholdSuggestion();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> curve = (List<Map<String, Object>>) suggestion.get("curve");
        double current = ((Number) suggestion.get("currentL3Enter")).doubleValue();
        // Prefer a point slightly below current with lower FP if available
        Map<String, Object> best = null;
        for (Map<String, Object> p : curve) {
            double thr = ((Number) p.get("threshold")).doubleValue();
            if (thr >= current) {
                continue;
            }
            Object fp = p.get("fpRate");
            Object curFp = ((Map<?, ?>) suggestion.get("atCurrent")).get("fpRate");
            if (fp instanceof Number && curFp instanceof Number
                    && ((Number) fp).doubleValue() <= ((Number) curFp).doubleValue()) {
                if (best == null || thr > ((Number) best.get("threshold")).doubleValue()) {
                    best = p;
                }
            }
        }
        if (best != null) {
            return ((Number) best.get("threshold")).doubleValue();
        }
        return Math.max(0.20, current - 0.05);
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static Double toDouble(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Double d) {
            return d;
        }
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(raw));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private record LabelledScore(String label, double peakScore) {}
}
