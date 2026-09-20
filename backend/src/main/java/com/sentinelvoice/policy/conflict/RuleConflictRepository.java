package com.sentinelvoice.policy.conflict;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class RuleConflictRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public RuleConflictRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public UUID insert(
            UUID tenantId,
            UUID policySetId,
            String ruleAId,
            String ruleBId,
            UUID ruleAPk,
            UUID ruleBPk,
            String conflictType,
            String summary,
            Map<String, Object> detail,
            boolean advisory,
            String shaA,
            String shaB
    ) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO rule_conflicts (
                  id, tenant_id, policy_set_id, rule_a_id, rule_b_id, rule_a_pk, rule_b_pk,
                  conflict_type, status, summary, detail, advisory, content_sha_a, content_sha_b
                ) VALUES (?,?,?,?,?,?,?,?, 'OPEN', ?, ?::jsonb, ?, ?, ?)
                """,
                id, tenantId, policySetId, ruleAId, ruleBId, ruleAPk, ruleBPk,
                conflictType, summary, toJson(detail == null ? Map.of() : detail),
                advisory, shaA, shaB
        );
        return id;
    }

    public Optional<Map<String, Object>> findOpenPair(
            UUID tenantId, UUID policySetId, String ruleAId, String ruleBId, String type
    ) {
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT * FROM rule_conflicts
                WHERE tenant_id = ? AND status = 'OPEN'
                  AND conflict_type = ?
                  AND (
                    (policy_set_id IS NOT DISTINCT FROM ?)
                  )
                  AND (
                    (rule_a_id = ? AND rule_b_id = ?)
                    OR (rule_a_id = ? AND rule_b_id = ?)
                  )
                LIMIT 1
                """, (rs, i) -> mapRow(rs),
                tenantId, type, policySetId, ruleAId, ruleBId, ruleBId, ruleAId
        );
        return rows.stream().findFirst();
    }

    public Optional<Map<String, Object>> findById(UUID tenantId, UUID id) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT * FROM rule_conflicts WHERE tenant_id = ? AND id = ?",
                (rs, i) -> mapRow(rs), tenantId, id
        );
        return rows.stream().findFirst();
    }

    public List<Map<String, Object>> list(
            UUID tenantId, UUID setId, String status
    ) {
        if (setId != null && status != null) {
            return jdbc.query("""
                    SELECT * FROM rule_conflicts
                    WHERE tenant_id = ? AND policy_set_id = ? AND status = ?
                    ORDER BY created_at DESC
                    """, (rs, i) -> mapRow(rs), tenantId, setId, status);
        }
        if (setId != null) {
            return jdbc.query("""
                    SELECT * FROM rule_conflicts
                    WHERE tenant_id = ? AND policy_set_id = ?
                    ORDER BY created_at DESC
                    """, (rs, i) -> mapRow(rs), tenantId, setId);
        }
        if (status != null) {
            return jdbc.query("""
                    SELECT * FROM rule_conflicts
                    WHERE tenant_id = ? AND status = ?
                    ORDER BY created_at DESC
                    """, (rs, i) -> mapRow(rs), tenantId, status);
        }
        return jdbc.query("""
                SELECT * FROM rule_conflicts
                WHERE tenant_id = ?
                ORDER BY created_at DESC
                LIMIT 500
                """, (rs, i) -> mapRow(rs), tenantId);
    }

    public long countOpen(UUID tenantId, UUID setId) {
        Long n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM rule_conflicts
                WHERE tenant_id = ? AND policy_set_id = ? AND status = 'OPEN'
                  AND advisory = false
                """, Long.class, tenantId, setId);
        return n == null ? 0 : n;
    }

    public long countOpenIncludingAdvisory(UUID tenantId, UUID setId) {
        Long n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM rule_conflicts
                WHERE tenant_id = ? AND policy_set_id = ? AND status = 'OPEN'
                """, Long.class, tenantId, setId);
        return n == null ? 0 : n;
    }

    public void resolve(
            UUID tenantId,
            UUID id,
            String resolution,
            String reason,
            UUID resolvedBy
    ) {
        String safe = sanitizeResolution(resolution);
        jdbc.update("""
                UPDATE rule_conflicts
                SET status = 'RESOLVED',
                    resolution = ?,
                    reason = ?,
                    resolved_by = ?,
                    resolved_at = now(),
                    updated_at = now()
                WHERE tenant_id = ? AND id = ?
                """, safe, reason, resolvedBy, tenantId, id);
    }

    /** DB check only allows these five values. */
    public static String sanitizeResolution(String resolution) {
        if (resolution == null || resolution.isBlank()) {
            return "KEEP_EXISTING";
        }
        return switch (resolution.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "KEEP_NEW" -> "KEEP_NEW";
            case "KEEP_EXISTING" -> "KEEP_EXISTING";
            case "KEEP_BOTH" -> "KEEP_BOTH";
            case "MERGE_EDIT" -> "MERGE_EDIT";
            case "DEFER" -> "DEFER";
            default -> "KEEP_EXISTING";
        };
    }

    /** Close OPEN conflicts that reference a soft-deleted or missing rule (draft + ACTIVE). */
    public int closeOrphansForSet(UUID tenantId, UUID setId) {
        return jdbc.update("""
                UPDATE rule_conflicts c
                SET status = 'RESOLVED',
                    resolution = 'KEEP_EXISTING',
                    reason = 'auto-closed: referenced rule missing or deleted',
                    resolved_at = now(),
                    updated_at = now()
                WHERE c.tenant_id = ?
                  AND c.policy_set_id = ?
                  AND c.status = 'OPEN'
                  AND (
                    NOT EXISTS (
                      SELECT 1 FROM policy_rules r
                      JOIN policy_sets s ON s.id = r.policy_set_id AND s.tenant_id = r.tenant_id
                      WHERE r.tenant_id = c.tenant_id
                        AND r.rule_id = c.rule_a_id
                        AND r.deleted_at IS NULL
                        AND (r.policy_set_id = c.policy_set_id OR s.status = 'ACTIVE')
                    )
                    OR NOT EXISTS (
                      SELECT 1 FROM policy_rules r
                      JOIN policy_sets s ON s.id = r.policy_set_id AND s.tenant_id = r.tenant_id
                      WHERE r.tenant_id = c.tenant_id
                        AND r.rule_id = c.rule_b_id
                        AND r.deleted_at IS NULL
                        AND (r.policy_set_id = c.policy_set_id OR s.status = 'ACTIVE')
                    )
                  )
                """, tenantId, setId);
    }

    public void reopen(UUID tenantId, UUID id) {
        jdbc.update("""
                UPDATE rule_conflicts
                SET status = 'OPEN',
                    resolution = NULL,
                    reason = NULL,
                    resolved_by = NULL,
                    resolved_at = NULL,
                    updated_at = now()
                WHERE tenant_id = ? AND id = ?
                """, tenantId, id);
    }

    public void reopenIfContentChanged(
            UUID tenantId, String ruleId, String newSha
    ) {
        jdbc.update("""
                UPDATE rule_conflicts
                SET status = 'OPEN',
                    resolution = NULL,
                    reason = NULL,
                    resolved_by = NULL,
                    resolved_at = NULL,
                    updated_at = now()
                WHERE tenant_id = ?
                  AND status = 'RESOLVED'
                  AND (rule_a_id = ? OR rule_b_id = ?)
                  AND (
                    (rule_a_id = ? AND content_sha_a IS DISTINCT FROM ?)
                    OR (rule_b_id = ? AND content_sha_b IS DISTINCT FROM ?)
                  )
                """, tenantId, ruleId, ruleId, ruleId, newSha, ruleId, newSha);
    }

    public void updateContentHashes(UUID tenantId, UUID id, String shaA, String shaB) {
        jdbc.update("""
                UPDATE rule_conflicts
                SET content_sha_a = ?, content_sha_b = ?, updated_at = now()
                WHERE tenant_id = ? AND id = ?
                """, shaA, shaB, tenantId, id);
    }

    public void updateDetail(UUID tenantId, UUID id, Map<String, Object> detail) {
        jdbc.update("""
                UPDATE rule_conflicts
                SET detail = ?::jsonb, updated_at = now()
                WHERE tenant_id = ? AND id = ?
                """, toJson(detail == null ? Map.of() : detail), tenantId, id);
    }

    private Map<String, Object> mapRow(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getObject("id", UUID.class).toString());
        m.put("tenantId", rs.getObject("tenant_id", UUID.class).toString());
        UUID setId = rs.getObject("policy_set_id", UUID.class);
        m.put("policySetId", setId == null ? null : setId.toString());
        m.put("ruleAId", rs.getString("rule_a_id"));
        m.put("ruleBId", rs.getString("rule_b_id"));
        UUID apk = rs.getObject("rule_a_pk", UUID.class);
        UUID bpk = rs.getObject("rule_b_pk", UUID.class);
        m.put("ruleAPk", apk == null ? null : apk.toString());
        m.put("ruleBPk", bpk == null ? null : bpk.toString());
        m.put("type", rs.getString("conflict_type"));
        m.put("status", rs.getString("status"));
        m.put("resolution", rs.getString("resolution"));
        m.put("reason", rs.getString("reason"));
        m.put("summary", rs.getString("summary"));
        m.put("detail", parseMap(rs.getString("detail")));
        m.put("advisory", rs.getBoolean("advisory"));
        UUID rb = rs.getObject("resolved_by", UUID.class);
        m.put("resolvedBy", rb == null ? null : rb.toString());
        Timestamp ra = rs.getTimestamp("resolved_at");
        m.put("resolvedAt", ra == null ? null : ra.toInstant().toString());
        m.put("contentShaA", rs.getString("content_sha_a"));
        m.put("contentShaB", rs.getString("content_sha_b"));
        Timestamp ca = rs.getTimestamp("created_at");
        m.put("createdAt", ca == null ? Instant.now().toString() : ca.toInstant().toString());
        return m;
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }
}
