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

    public Optional<Map<String, Object>> findActiveSet(UUID tenantId) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT * FROM policy_sets WHERE tenant_id = ? AND status = 'ACTIVE' LIMIT 1",
                (rs, i) -> mapSet(rs),
                tenantId
        );
        return rows.stream().findFirst();
    }

    /**
     * ACCEPTED/EDITED rules only — PROPOSED and REJECTED are never evaluated at runtime.
     * Hallucination / value-not-in-source rejects stay excluded (same gate as approval).
     */
    public List<Map<String, Object>> listRuntimeRules(UUID tenantId, UUID setId) {
        return jdbc.query("""
                SELECT * FROM policy_rules
                WHERE tenant_id = ? AND policy_set_id = ?
                  AND deleted_at IS NULL
                  AND status IN ('ACCEPTED', 'EDITED')
                  AND NOT (warnings @> '[{"code":"HALLUCINATED_QUOTE"}]'::jsonb)
                  AND NOT (warnings @> '[{"code":"VALUE_NOT_IN_SOURCE"}]'::jsonb)
                  AND NOT (warnings @> '[{"code":"REJECTED_VALUE_NOT_IN_SOURCE"}]'::jsonb)
                ORDER BY rule_id NULLS LAST, created_at
                """, (rs, i) -> mapRule(rs), tenantId, setId);
    }

    public List<Map<String, Object>> listLiveRulesIncludingDeleted(UUID tenantId, UUID setId) {
        return jdbc.query("""
                SELECT * FROM policy_rules
                WHERE tenant_id = ? AND policy_set_id = ?
                ORDER BY (deleted_at IS NULL) DESC, rule_id NULLS LAST, created_at
                """, (rs, i) -> mapRule(rs), tenantId, setId);
    }

    public Optional<Map<String, Object>> findRuleByRuleId(UUID tenantId, UUID setId, String ruleId) {
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT * FROM policy_rules
                WHERE tenant_id = ? AND policy_set_id = ? AND rule_id = ?
                LIMIT 1
                """, (rs, i) -> mapRule(rs), tenantId, setId, ruleId);
        return rows.stream().findFirst();
    }

    public Optional<Map<String, Object>> findOpenDraft(UUID tenantId) {
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT * FROM policy_sets
                WHERE tenant_id = ? AND status = 'DRAFT'
                ORDER BY updated_at DESC
                LIMIT 1
                """, (rs, i) -> mapSet(rs), tenantId);
        return rows.stream().findFirst();
    }

    public void updateSetMeta(UUID tenantId, UUID setId, Map<String, Object> meta) {
        jdbc.update("""
                UPDATE policy_sets SET meta = ?::jsonb, updated_at = now()
                WHERE tenant_id = ? AND id = ?
                """, toJson(meta), tenantId, setId);
    }

    public void softDeleteRule(UUID tenantId, UUID rulePk, UUID deletedBy, String reason) {
        // Drop keywords tied to this rule before soft-delete
        findRule(tenantId, rulePk).ifPresent(rule -> {
            String ruleId = String.valueOf(rule.get("ruleId"));
            UUID setId = UUID.fromString(String.valueOf(rule.get("policySetId")));
            deleteKeywordsForRule(tenantId, setId, ruleId);
        });
        jdbc.update("""
                UPDATE policy_rules
                SET deleted_at = now(), deleted_by = ?, delete_reason = ?, updated_at = now()
                WHERE tenant_id = ? AND id = ? AND deleted_at IS NULL
                """, deletedBy, reason, tenantId, rulePk);
    }

    public void markReplacedBy(UUID tenantId, UUID rulePk, String replacedByRuleId) {
        jdbc.update("""
                UPDATE policy_rules
                SET replaced_by_rule_id = ?, updated_at = now()
                WHERE tenant_id = ? AND id = ?
                """, replacedByRuleId, tenantId, rulePk);
    }

    public void restoreRule(UUID tenantId, UUID rulePk) {
        jdbc.update("""
                UPDATE policy_rules
                SET deleted_at = NULL, deleted_by = NULL, delete_reason = NULL, updated_at = now()
                WHERE tenant_id = ? AND id = ? AND deleted_at IS NOT NULL
                """, tenantId, rulePk);
    }

    public void hardDeleteRule(UUID tenantId, UUID rulePk) {
        jdbc.update("DELETE FROM policy_rules WHERE tenant_id = ? AND id = ?", tenantId, rulePk);
    }

    public void deleteAllRules(UUID tenantId, UUID setId) {
        jdbc.update("DELETE FROM policy_rules WHERE tenant_id = ? AND policy_set_id = ?", tenantId, setId);
    }

    public void copyRulesExcluding(
            UUID tenantId, UUID fromSetId, UUID toSetId, java.util.Collection<String> excludeRuleIds
    ) {
        List<Map<String, Object>> rules = listRules(tenantId, fromSetId);
        for (Map<String, Object> rule : rules) {
            if (rule.get("deletedAt") != null) {
                continue;
            }
            String rid = String.valueOf(rule.get("ruleId"));
            if (excludeRuleIds != null && excludeRuleIds.contains(rid)) {
                continue;
            }
            insertCopiedRule(tenantId, toSetId, rule);
        }
        copyKeywords(tenantId, fromSetId, toSetId, excludeRuleIds);
    }

    public void copyKeywords(
            UUID tenantId, UUID fromSetId, UUID toSetId, java.util.Collection<String> excludeRuleIds
    ) {
        List<Map<String, Object>> kws = listKeywords(tenantId, fromSetId);
        for (Map<String, Object> k : kws) {
            String src = k.get("sourceRuleId") == null ? null : String.valueOf(k.get("sourceRuleId"));
            if (src != null && excludeRuleIds != null && excludeRuleIds.contains(src)) {
                continue;
            }
            addKeywordForRule(
                    tenantId, toSetId,
                    String.valueOf(k.get("term")),
                    String.valueOf(k.getOrDefault("lang", "en")),
                    String.valueOf(k.getOrDefault("category", "CUSTOM")),
                    k.get("weight") instanceof Number n ? n.doubleValue() : 1.0,
                    src
            );
        }
    }

    public void insertCopiedRule(UUID tenantId, UUID setId, Map<String, Object> rule) {
        String origin = rule.get("origin") == null ? "MANUAL" : String.valueOf(rule.get("origin"));
        jdbc.update("""
                INSERT INTO policy_rules (
                  tenant_id, policy_set_id, rule_id, title, description, source,
                  applies_to, when_json, then_json, severity, status, origin, warnings, rule_body,
                  simulation_examples
                ) VALUES (?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?,?,?,?::jsonb,?::jsonb,?::jsonb)
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
                origin,
                toJson(rule.getOrDefault("warnings", List.of())),
                toJson(rule),
                toJson(rule.getOrDefault("simulationExamples", List.of()))
        );
    }

    public void updateSimulationExamples(UUID tenantId, UUID rulePk, Object examples) {
        jdbc.update("""
                UPDATE policy_rules
                SET simulation_examples = ?::jsonb, updated_at = now()
                WHERE tenant_id = ? AND id = ?
                """, toJson(examples), tenantId, rulePk);
    }

    public CoverageCounts coverageFromCompilation(UUID tenantId, UUID compilationId) {
        if (compilationId == null) {
            return new CoverageCounts(0, 0, 0);
        }
        Integer procedural = jdbc.queryForObject("""
                SELECT COUNT(*) FROM policy_compile_chunk_results
                WHERE tenant_id = ? AND compilation_id = ? AND status = 'SKIPPED_PREFILTER'
                """, Integer.class, tenantId, compilationId);
        Integer enforceable = jdbc.queryForObject("""
                SELECT COUNT(*) FROM policy_compile_chunk_results
                WHERE tenant_id = ? AND compilation_id = ?
                  AND status <> 'SKIPPED_PREFILTER'
                """, Integer.class, tenantId, compilationId);
        return new CoverageCounts(
                enforceable == null ? 0 : enforceable,
                procedural == null ? 0 : procedural,
                0
        );
    }

    public record CoverageCounts(int enforceable, int procedural, int unmapped) {
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
                ORDER BY (deleted_at IS NULL) DESC, rule_id NULLS LAST, created_at
                """, (rs, i) -> mapRule(rs), tenantId, setId);
    }

    public List<Map<String, Object>> listActiveRules(UUID tenantId, UUID setId) {
        return jdbc.query("""
                SELECT * FROM policy_rules
                WHERE tenant_id = ? AND policy_set_id = ? AND deleted_at IS NULL
                ORDER BY rule_id NULLS LAST, created_at
                """, (rs, i) -> mapRule(rs), tenantId, setId);
    }

    public List<Map<String, Object>> listDeletedRules(UUID tenantId, UUID setId) {
        return jdbc.query("""
                SELECT * FROM policy_rules
                WHERE tenant_id = ? AND policy_set_id = ? AND deleted_at IS NOT NULL
                ORDER BY deleted_at DESC
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
                  applies_to, when_json, then_json, severity, status, origin, warnings, rule_body,
                  simulation_examples
                ) VALUES (?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?,?,?,?::jsonb,?::jsonb,?::jsonb)
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
                toJson(rule),
                toJson(rule.getOrDefault("simulationExamples", List.of()))
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

    public void addKeywordForRule(
            UUID tenantId, UUID setId, String term, String lang, String category, double weight, String sourceRuleId
    ) {
        jdbc.update("""
                INSERT INTO policy_keywords
                  (tenant_id, policy_set_id, term, lang, category, weight, source_rule_id)
                VALUES (?,?,?,?,?,?,?)
                """, tenantId, setId, term, lang, category, weight, sourceRuleId);
    }

    public void deleteKeywordsForRule(UUID tenantId, UUID setId, String ruleId) {
        if (ruleId == null || ruleId.isBlank()) {
            return;
        }
        jdbc.update("""
                DELETE FROM policy_keywords
                WHERE tenant_id = ? AND policy_set_id = ? AND source_rule_id = ?
                """, tenantId, setId, ruleId);
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
        List<Map<String, Object>> rules = listActiveRules(tenantId, setId);
        try {
            // Canonicalise to stable fields only (exclude ephemeral UI fields)
            List<Map<String, Object>> canonical = new ArrayList<>();
            for (Map<String, Object> r : rules) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("ruleId", r.get("ruleId"));
                row.put("title", r.get("title"));
                row.put("when", r.get("when"));
                row.put("then", r.get("then"));
                row.put("appliesTo", r.get("appliesTo"));
                row.put("status", r.get("status"));
                row.put("severity", r.get("severity"));
                row.put("origin", r.get("origin"));
                canonical.add(row);
            }
            String json = mapper.writeValueAsString(canonical);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public long countAccepted(UUID tenantId, UUID setId) {
        Long n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM policy_rules
                WHERE tenant_id = ? AND policy_set_id = ?
                  AND deleted_at IS NULL
                  AND status IN ('ACCEPTED','EDITED')
                  AND NOT (warnings @> '[{"code":"HALLUCINATED_QUOTE"}]'::jsonb)
                  AND NOT (warnings @> '[{"code":"VALUE_NOT_IN_SOURCE"}]'::jsonb)
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
        try {
            m.put("meta", parseMap(rs.getString("meta")));
        } catch (SQLException ignored) {
            m.put("meta", Map.of());
        }
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
        m.put("ruleId", rs.getString("rule_id") != null ? rs.getString("rule_id") : m.get("ruleId"));
        m.put("title", rs.getString("title") != null ? rs.getString("title") : m.get("title"));
        m.put("status", rs.getString("status"));
        m.put("origin", rs.getString("origin"));
        m.put("warnings", parseList(rs.getString("warnings")));
        m.put("when", parseMap(rs.getString("when_json")).isEmpty()
                ? asMap(m.get("when")) : parseMap(rs.getString("when_json")));
        m.put("then", parseMap(rs.getString("then_json")).isEmpty()
                ? asMap(m.get("then")) : parseMap(rs.getString("then_json")));
        m.put("appliesTo", parseMap(rs.getString("applies_to")).isEmpty()
                ? asMap(m.get("appliesTo")) : parseMap(rs.getString("applies_to")));
        m.put("source", parseMap(rs.getString("source")).isEmpty()
                ? asMap(m.get("source")) : parseMap(rs.getString("source")));
        try {
            m.put("deletedAt", toIso(rs.getTimestamp("deleted_at")));
            m.put("deletedBy", uuidStr(rs, "deleted_by"));
            m.put("deleteReason", rs.getString("delete_reason"));
        } catch (SQLException ignored) {
            m.put("deletedAt", null);
        }
        try {
            m.put("simulationExamples", parseList(rs.getString("simulation_examples")));
        } catch (SQLException ignored) {
            m.put("simulationExamples", List.of());
        }
        Map<String, Object> when = asMap(m.get("when"));
        Map<String, Object> then = asMap(m.get("then"));
        Map<String, Object> applies = asMap(m.get("appliesTo"));
        m.put("plainEnglish", com.sentinelvoice.policy.dsl.ConditionEnglish.render(when, then, applies));
        m.put("firesWhen", com.sentinelvoice.policy.dsl.ConditionEnglish.firesWhen(when));
        m.put("doesNotFireWhen", com.sentinelvoice.policy.dsl.ConditionEnglish.doesNotFireWhen(when));
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
