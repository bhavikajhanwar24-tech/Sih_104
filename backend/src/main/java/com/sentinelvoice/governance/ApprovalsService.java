package com.sentinelvoice.governance;

import com.sentinelvoice.fusion.config.FusionConfigService;
import com.sentinelvoice.policy.sets.PolicySetService;
import com.sentinelvoice.response.ResponsePlanService;
import com.sentinelvoice.security.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * F14 — unified approvals inbox across policy / fusion / response plans.
 */
@Service
public class ApprovalsService {

    private final JdbcTemplate jdbc;
    private final PolicySetService policySetService;
    private final FusionConfigService fusionConfigService;
    private final ResponsePlanService responsePlanService;

    public ApprovalsService(
            JdbcTemplate jdbc,
            PolicySetService policySetService,
            FusionConfigService fusionConfigService,
            ResponsePlanService responsePlanService
    ) {
        this.jdbc = jdbc;
        this.policySetService = policySetService;
        this.fusionConfigService = fusionConfigService;
        this.responsePlanService = responsePlanService;
    }

    public Map<String, Object> list() {
        UUID tenantId = TenantContext.require().tenantId();
        List<Map<String, Object>> items = new ArrayList<>();
        items.addAll(listPolicy(tenantId));
        items.addAll(listFusion(tenantId));
        items.addAll(listResponse(tenantId));
        items.sort((a, b) -> String.valueOf(b.get("submittedAt")).compareTo(String.valueOf(a.get("submittedAt"))));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", "2");
        body.put("count", items.size());
        body.put("items", items);
        return body;
    }

    public Map<String, Object> detail(String area, UUID id) {
        UUID tenantId = TenantContext.require().tenantId();
        return switch (area.toUpperCase()) {
            case "POLICY_SET", "POLICY" -> policyDetail(tenantId, id);
            case "FUSION_CONFIG", "FUSION" -> fusionDetail(tenantId, id);
            case "RESPONSE_PLAN", "RESPONSE" -> responseDetail(tenantId, id);
            default -> throw new IllegalArgumentException("unknown area: " + area);
        };
    }

    @Transactional
    public Map<String, Object> decide(String area, UUID id, boolean approve, String comment) {
        TenantContext ctx = TenantContext.require();
        String c = comment == null ? "" : comment.trim();
        return switch (area.toUpperCase()) {
            case "POLICY_SET", "POLICY" -> approve
                    ? policySetService.approve(ctx.tenantId(), ctx.userId(), id, c)
                    : policySetService.reject(ctx.tenantId(), ctx.userId(), id, c);
            case "FUSION_CONFIG", "FUSION" -> approve
                    ? fusionConfigService.approve(id)
                    : fusionConfigService.reject(id, c);
            case "RESPONSE_PLAN", "RESPONSE" -> approve
                    ? responsePlanService.approve(id)
                    : responsePlanService.reject(id, c);
            default -> throw new IllegalArgumentException("unknown area: " + area);
        };
    }

    private List<Map<String, Object>> listPolicy(UUID tenantId) {
        return jdbc.query(
                """
                SELECT id, name, updated_at, submitted_by, content_sha256
                FROM policy_sets WHERE tenant_id = ? AND status = 'PENDING_APPROVAL'
                ORDER BY updated_at DESC
                """,
                (rs, i) -> item(
                        rs.getObject("id", UUID.class),
                        "POLICY_SET",
                        rs.getString("name"),
                        rs.getTimestamp("updated_at").toInstant(),
                        rs.getObject("submitted_by", UUID.class),
                        rs.getString("content_sha256")
                ),
                tenantId
        );
    }

    private List<Map<String, Object>> listFusion(UUID tenantId) {
        return jdbc.query(
                """
                SELECT id, version, updated_at, submitted_by, content_sha256
                FROM fusion_configs WHERE tenant_id = ? AND status = 'PENDING_APPROVAL'
                ORDER BY updated_at DESC
                """,
                (rs, i) -> item(
                        rs.getObject("id", UUID.class),
                        "FUSION_CONFIG",
                        "Fusion config v" + rs.getInt("version"),
                        rs.getTimestamp("updated_at").toInstant(),
                        rs.getObject("submitted_by", UUID.class),
                        rs.getString("content_sha256")
                ),
                tenantId
        );
    }

    private List<Map<String, Object>> listResponse(UUID tenantId) {
        return jdbc.query(
                """
                SELECT id, version, updated_at, submitted_by, content_sha256
                FROM response_plans WHERE tenant_id = ? AND status = 'PENDING_APPROVAL'
                ORDER BY updated_at DESC
                """,
                (rs, i) -> item(
                        rs.getObject("id", UUID.class),
                        "RESPONSE_PLAN",
                        "Response plan v" + rs.getInt("version"),
                        rs.getTimestamp("updated_at").toInstant(),
                        rs.getObject("submitted_by", UUID.class),
                        rs.getString("content_sha256")
                ),
                tenantId
        );
    }

    private Map<String, Object> policyDetail(UUID tenantId, UUID id) {
        Map<String, Object> row = jdbc.query(
                """
                SELECT id, name, status, content_sha256, updated_at, submitted_by
                FROM policy_sets WHERE tenant_id = ? AND id = ?
                """,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getObject("id", UUID.class).toString());
                    m.put("area", "POLICY_SET");
                    m.put("title", rs.getString("name"));
                    m.put("status", rs.getString("status"));
                    m.put("contentSha256", rs.getString("content_sha256"));
                    m.put("submittedAt", rs.getTimestamp("updated_at").toInstant().toString());
                    m.put("submittedBy", uuidStr(rs.getObject("submitted_by", UUID.class)));
                    return m;
                },
                tenantId, id
        ).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("not found"));
        // Diff: active vs pending rule counts
        Integer pendingRules = jdbc.queryForObject(
                "SELECT COUNT(*) FROM policy_rules WHERE tenant_id = ? AND policy_set_id = ?",
                Integer.class, tenantId, id
        );
        Integer activeRules = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM policy_rules r
                JOIN policy_sets s ON s.id = r.policy_set_id AND s.tenant_id = r.tenant_id
                WHERE r.tenant_id = ? AND s.status = 'ACTIVE'
                """,
                Integer.class, tenantId
        );
        row.put("diff", Map.of(
                "summary", "Pending set ruleCount=" + (pendingRules == null ? 0 : pendingRules)
                        + " vs ACTIVE ruleCount=" + (activeRules == null ? 0 : activeRules),
                "before", Map.of("activeRuleCount", activeRules == null ? 0 : activeRules),
                "after", Map.of("pendingRuleCount", pendingRules == null ? 0 : pendingRules)
        ));
        return row;
    }

    private Map<String, Object> fusionDetail(UUID tenantId, UUID id) {
        return jdbc.query(
                """
                SELECT p.id, p.version, p.status, p.content_sha256, p.updated_at, p.submitted_by, p.config,
                       a.config AS active_config, a.content_sha256 AS active_sha
                FROM fusion_configs p
                LEFT JOIN fusion_configs a ON a.tenant_id = p.tenant_id AND a.status = 'ACTIVE'
                WHERE p.tenant_id = ? AND p.id = ?
                """,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getObject("id", UUID.class).toString());
                    m.put("area", "FUSION_CONFIG");
                    m.put("title", "Fusion config v" + rs.getInt("version"));
                    m.put("status", rs.getString("status"));
                    m.put("contentSha256", rs.getString("content_sha256"));
                    m.put("submittedAt", rs.getTimestamp("updated_at").toInstant().toString());
                    m.put("submittedBy", uuidStr(rs.getObject("submitted_by", UUID.class)));
                    m.put("diff", Map.of(
                            "beforeSha", rs.getString("active_sha"),
                            "afterSha", rs.getString("content_sha256"),
                            "before", rs.getObject("active_config"),
                            "after", rs.getObject("config")
                    ));
                    return m;
                },
                tenantId, id
        ).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("not found"));
    }

    private Map<String, Object> responseDetail(UUID tenantId, UUID id) {
        return jdbc.query(
                """
                SELECT p.id, p.version, p.status, p.content_sha256, p.updated_at, p.submitted_by, p.plan,
                       a.plan AS active_plan, a.content_sha256 AS active_sha
                FROM response_plans p
                LEFT JOIN response_plans a ON a.tenant_id = p.tenant_id AND a.status = 'ACTIVE'
                WHERE p.tenant_id = ? AND p.id = ?
                """,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getObject("id", UUID.class).toString());
                    m.put("area", "RESPONSE_PLAN");
                    m.put("title", "Response plan v" + rs.getInt("version"));
                    m.put("status", rs.getString("status"));
                    m.put("contentSha256", rs.getString("content_sha256"));
                    m.put("submittedAt", rs.getTimestamp("updated_at").toInstant().toString());
                    m.put("submittedBy", uuidStr(rs.getObject("submitted_by", UUID.class)));
                    m.put("diff", Map.of(
                            "beforeSha", rs.getString("active_sha"),
                            "afterSha", rs.getString("content_sha256"),
                            "before", rs.getObject("active_plan"),
                            "after", rs.getObject("plan")
                    ));
                    return m;
                },
                tenantId, id
        ).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("not found"));
    }

    private static Map<String, Object> item(
            UUID id, String area, String title, Instant at, UUID submittedBy, String sha
    ) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id.toString());
        m.put("area", area);
        m.put("title", title);
        m.put("submittedAt", at.toString());
        m.put("ageMinutes", Math.max(0, Duration.between(at, Instant.now()).toMinutes()));
        m.put("submittedBy", uuidStr(submittedBy));
        m.put("contentSha256", sha);
        return m;
    }

    private static String uuidStr(UUID id) {
        return id == null ? null : id.toString();
    }
}
