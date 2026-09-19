package com.sentinelvoice.policy.sets;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelvoice.policy.compile.PolicyCompileException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PolicySetRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public PolicySetRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public UUID createDraft(UUID tenantId, UUID createdBy, String name, int version) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO policy_sets (id, tenant_id, name, status, version, created_by)
                VALUES (?, ?, ?, 'DRAFT', ?, ?)
                """, id, tenantId, name, version, createdBy);
        return id;
    }

    public int nextVersion(UUID tenantId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM policy_sets WHERE tenant_id = ?",
                Integer.class,
                tenantId
        );
        return (max == null ? 0 : max) + 1;
    }

    public Optional<Map<String, Object>> findSet(UUID tenantId, UUID id) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT * FROM policy_sets WHERE tenant_id = ? AND id = ?",
                (rs, i) -> mapSet(rs),
                tenantId, id
        );
        return rows.stream().findFirst();
    }

    public List<Map<String, Object>> listSets(UUID tenantId) {
        return jdbc.query(
                "SELECT * FROM policy_sets WHERE tenant_id = ? ORDER BY version DESC",
                (rs, i) -> mapSet(rs),
                tenantId
        );
    }

    public List<Map<String, Object>> listRules(UUID tenantId, UUID setId) {
        return jdbc.query("""
                SELECT * FROM policy_rules
                WHERE tenant_id = ? AND policy_set_id = ?
                ORDER BY rule_id NULLS LAST, created_at
                """, (rs, i) -> mapRule(rs), tenantId, setId);
    }

    public Optional<Map<String, Object>> findRule(UUID tenantId, UUID rulePk) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT * FROM policy_rules WHERE tenant_id = ? AND id = ?",
                (rs, i) -> mapRule(rs),
                tenantId, rulePk
        );
        return rows.stream().findFirst();
    }

    public void updateRuleStatus(UUID tenantId, UUID rulePk, String status, Map<String, Object> body) {
        jdbc.update("""
                UPDATE policy_rules
                SET status = ?,
                    rule_body = COALESCE(?::jsonb, rule_body),
                    title = COALESCE(?, title),
                    description = COALESCE(?, description),
                    source = COALESCE(?::jsonb, source),
                    applies_to = COALESCE(?::jsonb, applies_to),
                    when_json = COALESCE(?::jsonb, when_json),
                    then_json = COALESCE(?::jsonb, then_json),
                    severity = COALESCE(?, severity),
                    warnings = COALESCE(?::jsonb, warnings),
                    updated_at = now()
                WHERE tenant_id = ? AND id = ?
                """,
                status,
                body == null ? null : toJson(body),
                body == null ? null : body.get("title"),
                body == null ? null : body.get("description"),
                body == null ? null : toJson(body.get("source")),
                body == null ? null : toJson(body.get("appliesTo")),
                body == null ? null : toJson(body.get("when")),
                body == null ? null : toJson(body.get("then")),
                body == null ? null : (String) body.get("severity"),
                body == null ? null : toJson(body.get("warnings")),
                tenantId,
                rulePk
        );
    }

    public void insertManualRule(UUID tenantId, UUID setId, Map<String, Object> rule) {
        jdbc.update("""
                INSERT INTO policy_rules (
                  tenant_id, policy_set_id, rule_id, title, description, source,
                  applies_to, when_json, then_json, severity, status, origin, warnings, rule_body
                ) VALUES (?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?,?,?,?,?::jsonb,?::jsonb)
                """,
                tenantId,
                setId,
                rule.get("ruleId"),
                rule.get("title"),
                rule.get("description"),
                toJson(rule.get("source")),
                toJson(rule.get("appliesTo")),
                toJson(rule.get("when")),
                toJson(rule.get("then")),
                rule.getOrDefault("severity", "MEDIUM"),
                rule.getOrDefault("status", "ACCEPTED"),
                "MANUAL",
                toJson(rule.getOrDefault("warnings", List.of())),
                toJson(rule)
        );
    }

    public List<Map<String, Object>> listKeywords(UUID tenantId, UUID setId) {
        return jdbc.query("""
                SELECT id::text AS id, term, lang, category, weight, source_rule_id AS "sourceRuleId"
                FROM policy_keywords WHERE tenant_id = ? AND policy_set_id = ?
                ORDER BY category, term
                """, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getString("id"));
            m.put("term", rs.getString("term"));
            m.put("lang", rs.getString("lang"));
            m.put("category", rs.getString("category"));
            m.put("weight", rs.getDouble("weight"));
            m.put("sourceRuleId", rs.getString("sourceRuleId"));
            return m;
        }, tenantId, setId);
    }

    public void addKeyword(UUID tenantId, UUID setId, String term, String lang, String category, double weight) {
        jdbc.update("""
                INSERT INTO policy_keywords (tenant_id, policy_set_id, term, lang, category, weight)
                VALUES (?,?,?,?,?,?)
                """, tenantId, setId, term, lang, category, weight);
    }

    public void deleteKeyword(UUID tenantId, UUID keywordId) {
        jdbc.update("DELETE FROM policy_keywords WHERE tenant_id = ? AND id = ?", tenantId, keywordId);
    }

    public List<Map<String, Object>> listFacts(UUID tenantId, UUID setId) {
        return jdbc.query("""
                SELECT id::text AS id, source_rule_id AS "sourceRuleId", text
                FROM policy_facts WHERE tenant_id = ? AND policy_set_id = ?
                ORDER BY source_rule_id
                """, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getString("id"));
            m.put("sourceRuleId", rs.getString("sourceRuleId"));
            m.put("text", rs.getString("text"));
            return m;
        }, tenantId, setId);
    }

    public void submit(UUID tenantId, UUID setId, UUID submitterId) {
        int n = jdbc.update("""
                UPDATE policy_sets
                SET status = 'PENDING_APPROVAL', submitted_by = ?, updated_at = now()
                WHERE tenant_id = ? AND id = ? AND status IN ('DRAFT', 'SUPERSEDED')
                """, submitterId, tenantId, setId);
        if (n == 0) {
            throw new PolicyCompileException("BAD_STATE", "Set could not be submitted");
        }
    }

    public void approve(UUID tenantId, UUID setId, UUID approverId, String comment, String sha) {
        jdbc.update("""
                UPDATE policy_sets SET status = 'SUPERSEDED', updated_at = now()
                WHERE tenant_id = ? AND status = 'ACTIVE'
                """, tenantId);
        jdbc.update("""
                UPDATE policy_sets
                SET status = 'ACTIVE',
                    approved_by = ?,
                    approved_at = now(),
                    comment = ?,
                    content_sha256 = ?,
                    updated_at = now()
                WHERE tenant_id = ? AND id = ? AND status = 'PENDING_APPROVAL'
                """, approverId, comment, sha, tenantId, setId);
    }

    public void reject(UUID tenantId, UUID setId, UUID approverId, String comment) {
        jdbc.update("""
                UPDATE policy_sets
                SET status = 'REJECTED',
                    approved_by = ?,
                    approved_at = now(),
                    comment = ?,
                    updated_at = now()
                WHERE tenant_id = ? AND id = ? AND status = 'PENDING_APPROVAL'
                """, approverId, comment, tenantId, setId);
    }

    public String computeContentSha(UUID tenantId, UUID setId) {
        List<Map<String, Object>> rules = listRules(tenantId, setId);
        try {
            String canonical = mapper.writeValueAsString(rules);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public long countAccepted(UUID tenantId, UUID setId) {
        Long n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM policy_rules
                WHERE tenant_id = ? AND policy_set_id = ?
                  AND status IN ('ACCEPTED','EDITED')
                  AND NOT (warnings @> '[{"code":"HALLUCINATED_QUOTE"}]'::jsonb)
                  AND NOT (warnings @> '[{"code":"REJECTED_VALUE_NOT_IN_SOURCE"}]'::jsonb)
                """, Long.class, tenantId, setId);
        return n == null ? 0 : n;
    }

    public void deleteSet(UUID tenantId, UUID setId) {
        // Null out compilation FK first if present
        jdbc.update("UPDATE policy_compilations SET policy_set_id = NULL WHERE tenant_id = ? AND policy_set_id = ?",
                tenantId, setId);
        jdbc.update("DELETE FROM policy_sets WHERE tenant_id = ? AND id = ? AND status = 'DRAFT'",
                tenantId, setId);
    }

    private Map<String, Object> mapSet(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getObject("id", UUID.class).toString());
        m.put("tenantId", rs.getObject("tenant_id", UUID.class).toString());
        m.put("name", rs.getString("name"));
        m.put("status", rs.getString("status"));
        m.put("version", rs.getInt("version"));
        m.put("createdBy", uuidStr(rs, "created_by"));
        m.put("submittedBy", uuidStr(rs, "submitted_by"));
        m.put("approvedBy", uuidStr(rs, "approved_by"));
        m.put("approvedAt", toIso(rs.getTimestamp("approved_at")));
        m.put("comment", rs.getString("comment"));
        m.put("contentSha256", rs.getString("content_sha256"));
        m.put("compilationId", uuidStr(rs, "compilation_id"));
        m.put("createdAt", toIso(rs.getTimestamp("created_at")));
        m.put("updatedAt", toIso(rs.getTimestamp("updated_at")));
        return m;
    }

    private Map<String, Object> mapRule(ResultSet rs) throws SQLException {
        Map<String, Object> body = parseMap(rs.getString("rule_body"));
        if (body.isEmpty()) {
            body = new LinkedHashMap<>();
            body.put("ruleId", rs.getString("rule_id"));
            body.put("title", rs.getString("title"));
            body.put("description", rs.getString("description"));
            body.put("source", parseMap(rs.getString("source")));
            body.put("appliesTo", parseMap(rs.getString("applies_to")));
            body.put("when", parseMap(rs.getString("when_json")));
            body.put("then", parseMap(rs.getString("then_json")));
            body.put("severity", rs.getString("severity"));
            body.put("status", rs.getString("status"));
            body.put("origin", rs.getString("origin"));
            body.put("warnings", parseList(rs.getString("warnings")));
        }
        Map<String, Object> m = new LinkedHashMap<>(body);
        m.put("id", rs.getObject("id", UUID.class).toString());
        m.put("policySetId", rs.getObject("policy_set_id", UUID.class).toString());
        m.put("status", rs.getString("status"));
        m.put("origin", rs.getString("origin"));
        m.put("plainEnglish", com.sentinelvoice.policy.dsl.ConditionEnglish.render(
                asMap(m.get("when")), asMap(m.get("then")), asMap(m.get("appliesTo"))
        ));
        return m;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private String uuidStr(ResultSet rs, String col) throws SQLException {
        UUID u = rs.getObject(col, UUID.class);
        return u == null ? null : u.toString();
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o == null ? Map.of() : o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> parseMap(String raw) {
        if (raw == null || raw.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return mapper.readValue(raw, new TypeReference<>() {
            });
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private List<Map<String, Object>> parseList(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return mapper.readValue(raw, new TypeReference<>() {
            });
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static String toIso(Timestamp ts) {
        return ts == null ? null : ts.toInstant().toString();
    }
}
