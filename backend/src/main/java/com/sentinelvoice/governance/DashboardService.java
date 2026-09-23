package com.sentinelvoice.governance;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sentinelvoice.analytics.AnalyticsService;
import com.sentinelvoice.llm.LlmGatewayClient;
import com.sentinelvoice.security.TenantContext;
import com.sentinelvoice.tenant.TenantSettingsRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * F14 — tenant-scoped dashboard aggregates (briefly cached).
 */
@Service
public class DashboardService {

    private final JdbcTemplate jdbc;
    private final LlmGatewayClient llmGatewayClient;
    private final TenantSettingsRepository settingsRepository;
    private final EmergencyModeService emergencyModeService;
    private final AnalyticsService analyticsService;
    private final Cache<UUID, Map<String, Object>> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(15))
            .maximumSize(500)
            .build();

    public DashboardService(
            JdbcTemplate jdbc,
            LlmGatewayClient llmGatewayClient,
            TenantSettingsRepository settingsRepository,
            EmergencyModeService emergencyModeService,
            AnalyticsService analyticsService
    ) {
        this.jdbc = jdbc;
        this.llmGatewayClient = llmGatewayClient;
        this.settingsRepository = settingsRepository;
        this.emergencyModeService = emergencyModeService;
        this.analyticsService = analyticsService;
    }

    public Map<String, Object> snapshot() {
        UUID tenantId = TenantContext.require().tenantId();
        return cache.get(tenantId, this::build);
    }

    private Map<String, Object> build(UUID tenantId) {
        Instant now = Instant.now();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", "2");
        body.put("serverTime", now.toString());
        try {
            body.put("emergency", EmergencyModeService.toBody(emergencyModeService.current(tenantId)));
        } catch (Exception ex) {
            body.put("emergency", Map.of("active", false, "mode", null));
        }

        Map<String, Object> kpis = new LinkedHashMap<>();
        kpis.put("today", safeKpis(tenantId, now.minus(1, ChronoUnit.DAYS), now));
        kpis.put("d7", safeKpis(tenantId, now.minus(7, ChronoUnit.DAYS), now));
        kpis.put("d30", safeKpis(tenantId, now.minus(30, ChronoUnit.DAYS), now));
        body.put("kpis", kpis);
        body.put("callsByMaxLevel", safeList(() -> callsByLevel(tenantId, now.minus(7, ChronoUnit.DAYS), now)));
        body.put("pendingApprovals", safeList(() -> pendingApprovals(tenantId)));
        try {
            body.put("configHealth", configHealth(tenantId));
        } catch (Exception ex) {
            body.put("configHealth", Map.of("ok", false, "checklist", List.of(), "error", ex.getMessage()));
        }
        body.put("topRiskReasons", safeList(() -> topRiskReasons(tenantId, now.minus(7, ChronoUnit.DAYS))));
        body.put("topTargets", safeList(() -> topTargets(tenantId, now.minus(7, ChronoUnit.DAYS))));
        body.put("highRiskCalls", safeList(() -> highRiskCalls(tenantId, 15)));

        Map<String, Object> llm;
        try {
            llm = llmGatewayClient.health();
        } catch (Exception ex) {
            llm = Map.of("ok", false, "degraded", true, "gateway", "down");
        }
        double llmAvail = Boolean.TRUE.equals(llm.get("ok")) || "ok".equals(String.valueOf(llm.get("gateway")))
                ? 100.0
                : (Boolean.TRUE.equals(llm.get("degraded")) ? 50.0 : 0.0);
        body.put("llmAvailabilityPct", llmAvail);
        body.put("pipelineLatencyP95Ms", llm.get("p95LatencyMs"));
        try {
            body.put("falsePositiveRate", analyticsService.falsePositiveSnapshot(tenantId));
        } catch (Exception ex) {
            body.put("falsePositiveRate", Map.of(
                    "available", false,
                    "note", "Analytics unavailable: " + ex.getMessage()
            ));
        }
        try {
            body.put("rulesNeedingReview", analyticsService.rulesNeedingReview(tenantId));
        } catch (Exception ex) {
            body.put("rulesNeedingReview", List.of());
        }
        return body;
    }

    private Map<String, Object> safeKpis(UUID tenantId, Instant from, Instant to) {
        try {
            return windowKpis(tenantId, from, to);
        } catch (Exception ex) {
            return Map.of("callsMonitored", 0, "actionsExecuted", 0, "avgTimeToInterventionSec", null);
        }
    }

    private List<Map<String, Object>> safeList(java.util.function.Supplier<List<Map<String, Object>>> s) {
        try {
            List<Map<String, Object>> v = s.get();
            return v == null ? List.of() : v;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private Map<String, Object> windowKpis(UUID tenantId, Instant from, Instant to) {
        Integer calls = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM call_sessions
                WHERE tenant_id = ? AND started_at >= ? AND started_at < ?
                """,
                Integer.class, tenantId, java.sql.Timestamp.from(from), java.sql.Timestamp.from(to)
        );
        Integer actions = 0;
        try {
            actions = jdbc.queryForObject(
                    """
                    SELECT COUNT(*) FROM session_actions
                    WHERE tenant_id = ? AND created_at >= ? AND created_at < ?
                      AND status = 'EXECUTED'
                    """,
                    Integer.class, tenantId, java.sql.Timestamp.from(from), java.sql.Timestamp.from(to)
            );
        } catch (Exception ignored) {
            actions = 0;
        }
        Double avgTti = null;
        try {
            avgTti = jdbc.queryForObject(
                    """
                    SELECT AVG(EXTRACT(EPOCH FROM (sa.created_at - cs.started_at)))
                    FROM session_actions sa
                    JOIN call_sessions cs ON cs.sv_session_uuid = CAST(sa.session_id AS uuid)
                      AND cs.tenant_id = sa.tenant_id
                    WHERE sa.tenant_id = ? AND sa.created_at >= ? AND sa.created_at < ?
                      AND sa.action NOT IN ('LOG_ONLY','OPERATOR_ADVISORY')
                    """,
                    Double.class, tenantId, java.sql.Timestamp.from(from), java.sql.Timestamp.from(to)
            );
        } catch (Exception ignored) {
            /* optional metric */
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("callsMonitored", calls == null ? 0 : calls);
        m.put("actionsExecuted", actions == null ? 0 : actions);
        m.put("avgTimeToInterventionSec", avgTti == null ? null : Math.round(avgTti * 10.0) / 10.0);
        return m;
    }

    private List<Map<String, Object>> callsByLevel(UUID tenantId, Instant from, Instant to) {
        return jdbc.query(
                """
                SELECT COALESCE(peak_level, 'UNKNOWN') AS lvl, COUNT(*) AS cnt
                FROM call_sessions
                WHERE tenant_id = ? AND started_at >= ? AND started_at < ?
                GROUP BY 1 ORDER BY 2 DESC
                """,
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("level", rs.getString("lvl"));
                    row.put("count", rs.getInt("cnt"));
                    return row;
                },
                tenantId, java.sql.Timestamp.from(from), java.sql.Timestamp.from(to)
        );
    }

    private List<Map<String, Object>> pendingApprovals(UUID tenantId) {
        List<Map<String, Object>> out = new ArrayList<>();
        out.addAll(jdbc.query(
                """
                SELECT id, 'POLICY_SET' AS area, name AS title, updated_at
                FROM policy_sets WHERE tenant_id = ? AND status = 'PENDING_APPROVAL'
                """,
                (rs, i) -> pendingRow(rs.getObject("id", UUID.class), rs.getString("area"),
                        rs.getString("title"), rs.getTimestamp("updated_at").toInstant()),
                tenantId
        ));
        try {
            out.addAll(jdbc.query(
                    """
                    SELECT id, 'FUSION_CONFIG' AS area,
                           ('Fusion config v' || version) AS title, updated_at
                    FROM fusion_configs WHERE tenant_id = ? AND status = 'PENDING_APPROVAL'
                    """,
                    (rs, i) -> pendingRow(rs.getObject("id", UUID.class), rs.getString("area"),
                            rs.getString("title"), rs.getTimestamp("updated_at").toInstant()),
                    tenantId
            ));
        } catch (Exception ignored) { /* schema variance */ }
        try {
            out.addAll(jdbc.query(
                    """
                    SELECT id, 'RESPONSE_PLAN' AS area,
                           ('Response plan v' || version) AS title, updated_at
                    FROM response_plans WHERE tenant_id = ? AND status = 'PENDING_APPROVAL'
                    """,
                    (rs, i) -> pendingRow(rs.getObject("id", UUID.class), rs.getString("area"),
                            rs.getString("title"), rs.getTimestamp("updated_at").toInstant()),
                    tenantId
            ));
        } catch (Exception ignored) { /* schema variance */ }
        out.sort((a, b) -> String.valueOf(b.get("submittedAt")).compareTo(String.valueOf(a.get("submittedAt"))));
        return out;
    }

    private static Map<String, Object> pendingRow(UUID id, String area, String title, Instant at) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id.toString());
        m.put("area", area);
        m.put("title", title);
        m.put("submittedAt", at.toString());
        m.put("ageMinutes", Math.max(0, Duration.between(at, Instant.now()).toMinutes()));
        return m;
    }

    private Map<String, Object> configHealth(UUID tenantId) {
        boolean policyActive = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM policy_sets WHERE tenant_id = ? AND status = 'ACTIVE')",
                Boolean.class, tenantId
        ));
        boolean planActive = false;
        try {
            planActive = Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM response_plans WHERE tenant_id = ? AND status = 'ACTIVE')",
                    Boolean.class, tenantId
            ));
        } catch (Exception ignored) { /* */ }
        Integer empTotal = jdbc.queryForObject(
                "SELECT COUNT(*) FROM employees WHERE tenant_id = ? AND status <> 'DELETED'",
                Integer.class, tenantId
        );
        Integer withPhone = jdbc.queryForObject(
                """
                SELECT COUNT(DISTINCT e.id) FROM employees e
                JOIN employee_phones p ON p.employee_id = e.id AND p.tenant_id = e.tenant_id
                WHERE e.tenant_id = ? AND e.status <> 'DELETED'
                """,
                Integer.class, tenantId
        );
        Integer withAuth = jdbc.queryForObject(
                """
                SELECT COUNT(DISTINCT e.id) FROM employees e
                JOIN employee_authority a ON a.employee_id = e.id AND a.tenant_id = e.tenant_id
                WHERE e.tenant_id = ? AND e.status <> 'DELETED'
                """,
                Integer.class, tenantId
        );
        int total = empTotal == null ? 0 : empTotal;
        boolean directoryPresent = total > 0;
        boolean directoryPhonesOk = total > 0 && withPhone != null && withPhone > 0;
        double phonePct = total == 0 ? 0 : 100.0 * (withPhone == null ? 0 : withPhone) / total;
        double authPct = total == 0 ? 0 : 100.0 * (withAuth == null ? 0 : withAuth) / total;

        List<Map<String, Object>> checklist = new ArrayList<>();
        checklist.add(checkItem(
                "policy",
                "Active policy set",
                policyActive,
                "/app/policies",
                "No ACTIVE policy set — upload/compile and approve a policy"
        ));
        checklist.add(checkItem(
                "response_plan",
                "Active response plan",
                planActive,
                "/app/response",
                "No ACTIVE response plan — create, submit, and approve floors"
        ));
        checklist.add(checkItem(
                "directory",
                "Directory employees",
                directoryPresent,
                "/app/directory",
                "No employees in directory — add people and departments"
        ));
        checklist.add(checkItem(
                "directory_phones",
                "Employees with phone numbers",
                directoryPhonesOk,
                "/app/directory",
                "No phone numbers on employees — add phones / SIP extensions"
        ));
        checklist.add(checkItem(
                "directory_authority",
                "Employees with authority limits",
                total > 0 && withAuth != null && withAuth > 0,
                "/app/directory",
                "No authority limits — set limits on employees"
        ));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", checklist.stream().allMatch(c -> Boolean.TRUE.equals(c.get("ok"))));
        m.put("checklist", checklist);
        m.put("policyActive", policyActive);
        m.put("responsePlanActive", planActive);
        m.put("responsePlanFloorsSatisfied", planActive);
        m.put("integrationsTestedRecently", null);
        m.put("directoryPhoneCoveragePct", Math.round(phonePct * 10) / 10.0);
        m.put("directoryAuthorityCoveragePct", Math.round(authPct * 10) / 10.0);
        m.put("voicePassportEnrolled", 0);
        m.put("employeeCount", total);
        settingsRepository.findById(tenantId).ifPresent(s -> {
            m.put("timezone", s.getTimezone());
            m.put("retentionDays", s.getRetentionDays());
        });
        return m;
    }

    private static Map<String, Object> checkItem(
            String id, String label, boolean ok, String href, String missingHint
    ) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("label", label);
        row.put("ok", ok);
        row.put("href", href);
        row.put("hint", ok ? null : missingHint);
        return row;
    }

    private List<Map<String, Object>> topRiskReasons(UUID tenantId, Instant from) {
        try {
            return jdbc.query(
                    """
                    SELECT payload->>'code' AS code, COUNT(*) AS cnt
                    FROM audit_blocks
                    WHERE tenant_id = ? AND created_at >= ?
                      AND event_type IN ('RISK_LEVEL_CHANGED', 'FEATURE_FRAME_SCORED')
                      AND jsonb_exists(payload, 'code')
                    GROUP BY 1 ORDER BY 2 DESC LIMIT 8
                    """,
                    (rs, i) -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("code", rs.getString("code"));
                        row.put("count", rs.getInt("cnt"));
                        return row;
                    },
                    tenantId, java.sql.Timestamp.from(from)
            );
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<Map<String, Object>> topTargets(UUID tenantId, Instant from) {
        boolean hideNames = settingsRepository.findById(tenantId)
                .map(s -> Boolean.TRUE.equals(s.getExtras().get("hideCallerNames")))
                .orElse(false);
        try {
            return jdbc.query(
                    """
                    SELECT COALESCE(callee_dept.name, 'Unknown') AS department,
                           COUNT(*) AS cnt
                    FROM call_sessions cs
                    LEFT JOIN employees callee ON callee.id = cs.callee_employee_id
                    LEFT JOIN departments callee_dept ON callee_dept.id = callee.department_id
                    WHERE cs.tenant_id = ? AND cs.started_at >= ?
                      AND (cs.peak_level LIKE 'LEVEL_3%' OR cs.peak_level LIKE 'LEVEL_4%'
                           OR cs.peak_level LIKE 'LEVEL_5%')
                    GROUP BY 1 ORDER BY 2 DESC LIMIT 8
                    """,
                    (rs, i) -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("department", rs.getString("department"));
                        row.put("count", rs.getInt("cnt"));
                        row.put("privacy", hideNames ? "names_hidden" : "department_aggregate");
                        return row;
                    },
                    tenantId, java.sql.Timestamp.from(from)
            );
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<Map<String, Object>> highRiskCalls(UUID tenantId, int limit) {
        return jdbc.query(
                """
                SELECT cs.id, cs.sv_session_uuid, cs.started_at, cs.peak_score, cs.peak_level,
                       cs.final_outcome,
                       COALESCE(callee.full_name, cs.callee_number) AS callee_label,
                       callee_dept.name AS department
                FROM call_sessions cs
                LEFT JOIN employees callee ON callee.id = cs.callee_employee_id
                LEFT JOIN departments callee_dept ON callee_dept.id = callee.department_id
                WHERE cs.tenant_id = ?
                  AND (cs.peak_level LIKE 'LEVEL_3%' OR cs.peak_level LIKE 'LEVEL_4%'
                       OR cs.peak_level LIKE 'LEVEL_5%')
                ORDER BY cs.started_at DESC
                LIMIT ?
                """,
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getObject("id", UUID.class).toString());
                    UUID sv = rs.getObject("sv_session_uuid", UUID.class);
                    row.put("svSessionUuid", sv == null ? null : sv.toString());
                    row.put("startedAt", rs.getTimestamp("started_at").toInstant().toString());
                    row.put("peakScore", rs.getObject("peak_score"));
                    row.put("peakLevel", rs.getString("peak_level"));
                    row.put("finalOutcome", rs.getString("final_outcome"));
                    row.put("calleeLabel", rs.getString("callee_label"));
                    row.put("department", rs.getString("department"));
                    return row;
                },
                tenantId, limit
        );
    }
}
