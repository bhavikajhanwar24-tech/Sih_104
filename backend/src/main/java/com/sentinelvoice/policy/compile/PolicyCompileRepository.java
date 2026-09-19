package com.sentinelvoice.policy.compile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PolicyCompileRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public PolicyCompileRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public UUID insertCompilation(UUID tenantId, String mode, UUID createdBy, List<UUID> documentIds) {
        UUID id = UUID.randomUUID();
        jdbc.execute((Connection con) -> {
            try (var ps = con.prepareStatement("""
                    INSERT INTO policy_compilations
                      (id, tenant_id, mode, status, document_ids, created_by, progress)
                    VALUES (?, ?, ?, 'QUEUED', ?, ?, '{}'::jsonb)
                    """)) {
                ps.setObject(1, id);
                ps.setObject(2, tenantId);
                ps.setString(3, mode);
                // Array MUST be created on the same connection as the insert (pooled connections).
                Array arr = con.createArrayOf("uuid", documentIds.toArray(new UUID[0]));
                ps.setArray(4, arr);
                ps.setObject(5, createdBy);
                ps.executeUpdate();
            }
            return null;
        });
        return id;
    }

    public void bindPolicySet(UUID compilationId, UUID policySetId) {
        jdbc.update("UPDATE policy_compilations SET policy_set_id = ? WHERE id = ?", policySetId, compilationId);
        jdbc.update("UPDATE policy_sets SET compilation_id = ? WHERE id = ?", compilationId, policySetId);
    }

    public void updateStatus(UUID id, String status, String error) {
        jdbc.update("""
                UPDATE policy_compilations
                SET status = ?,
                    error = ?,
                    started_at = COALESCE(started_at, now()),
                    finished_at = CASE WHEN ? IN ('COMPLETED','COMPLETED_NO_RULES','FAILED','CANCELLED')
                                       THEN now() ELSE finished_at END
                WHERE id = ?
                """, status, error, status, id);
    }

    public void updateProgress(UUID id, Map<String, Object> progress) {
        jdbc.update("UPDATE policy_compilations SET progress = ?::jsonb WHERE id = ?", toJson(progress), id);
    }

    public Optional<Map<String, Object>> findCompilation(UUID tenantId, UUID id) {
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT * FROM policy_compilations WHERE tenant_id = ? AND id = ?
                """, (rs, i) -> mapCompilation(rs), tenantId, id);
        return rows.stream().findFirst();
    }

    public List<Map<String, Object>> listCompilations(UUID tenantId, int limit) {
        return jdbc.query("""
                SELECT * FROM policy_compilations
                WHERE tenant_id = ?
                ORDER BY created_at DESC
                LIMIT ?
                """, (rs, i) -> mapCompilation(rs), tenantId, limit);
    }

    public void insertChunkJob(
            UUID tenantId, UUID compilationId, UUID documentId, UUID chunkId, String status, String skipReason
    ) {
        jdbc.update("""
                INSERT INTO policy_compilation_chunks
                  (tenant_id, compilation_id, document_id, chunk_id, status, skip_reason)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (compilation_id, chunk_id) DO NOTHING
                """, tenantId, compilationId, documentId, chunkId, status, skipReason);
    }

    public List<Map<String, Object>> listPendingChunks(UUID compilationId) {
        return jdbc.query("""
                SELECT * FROM policy_compilation_chunks
                WHERE compilation_id = ? AND status IN ('PENDING','FAILED')
                ORDER BY id
                """, (rs, i) -> mapChunk(rs), compilationId);
    }

    public List<Map<String, Object>> listChunkJobs(UUID compilationId) {
        return jdbc.query("""
                SELECT * FROM policy_compilation_chunks WHERE compilation_id = ? ORDER BY id
                """, (rs, i) -> mapChunk(rs), compilationId);
    }

    public void updateChunk(
            UUID compilationId, UUID chunkId, String status, int proposed, int rejected, String error
    ) {
        jdbc.update("""
                UPDATE policy_compilation_chunks
                SET status = ?, rules_proposed = ?, rules_rejected = ?, error = ?, updated_at = now()
                WHERE compilation_id = ? AND chunk_id = ?
                """, status, proposed, rejected, error, compilationId, chunkId);
    }

    public void upsertChunkResult(
            UUID tenantId,
            UUID compilationId,
            UUID documentId,
            UUID chunkId,
            String status,
            String reason,
            int latencyMs,
            int rulesProposed
    ) {
        jdbc.update("""
                INSERT INTO policy_compile_chunk_results
                  (tenant_id, compilation_id, document_id, chunk_id, status, reason, latency_ms, rules_proposed)
                VALUES (?,?,?,?,?,?,?,?)
                ON CONFLICT (compilation_id, chunk_id) DO UPDATE SET
                  status = EXCLUDED.status,
                  reason = EXCLUDED.reason,
                  latency_ms = EXCLUDED.latency_ms,
                  rules_proposed = EXCLUDED.rules_proposed,
                  updated_at = now()
                """,
                tenantId, compilationId, documentId, chunkId, status, reason, latencyMs, rulesProposed
        );
    }

    public List<Map<String, Object>> listChunkResults(UUID compilationId) {
        return jdbc.query("""
                SELECT * FROM policy_compile_chunk_results
                WHERE compilation_id = ?
                ORDER BY created_at, chunk_id
                """, (rs, i) -> mapChunkResult(rs), compilationId);
    }

    public Optional<UUID> findCompilationIdByPolicySet(UUID tenantId, UUID policySetId) {
        List<UUID> rows = jdbc.query("""
                SELECT id FROM policy_compilations
                WHERE tenant_id = ? AND policy_set_id = ?
                ORDER BY created_at DESC
                LIMIT 1
                """, (rs, i) -> rs.getObject("id", UUID.class), tenantId, policySetId);
        return rows.stream().findFirst();
    }

    public void resetFailedChunksForRerun(UUID compilationId) {
        jdbc.update("""
                UPDATE policy_compilation_chunks
                SET status = 'PENDING', error = NULL, updated_at = now()
                WHERE compilation_id = ?
                  AND chunk_id IN (
                    SELECT chunk_id FROM policy_compile_chunk_results
                    WHERE compilation_id = ?
                      AND status IN ('LLM_TIMEOUT','LLM_SCHEMA_ERROR','LLM_EMPTY','REJECTED_VALIDATION')
                  )
                """, compilationId, compilationId);
        // Also reset jobs that FAILED without a result row
        jdbc.update("""
                UPDATE policy_compilation_chunks
                SET status = 'PENDING', error = NULL, updated_at = now()
                WHERE compilation_id = ? AND status = 'FAILED'
                """, compilationId);
    }

    public int countRulesInSet(UUID tenantId, UUID policySetId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM policy_rules WHERE tenant_id = ? AND policy_set_id = ?",
                Long.class,
                tenantId,
                policySetId
        );
        return n == null ? 0 : n.intValue();
    }

    private Map<String, Object> mapChunkResult(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getObject("id", UUID.class).toString());
        m.put("documentId", rs.getObject("document_id", UUID.class).toString());
        m.put("chunkId", rs.getObject("chunk_id", UUID.class).toString());
        m.put("status", rs.getString("status"));
        m.put("reason", rs.getString("reason"));
        m.put("latencyMs", rs.getInt("latency_ms"));
        m.put("rulesProposed", rs.getInt("rules_proposed"));
        return m;
    }

    public void insertRule(UUID tenantId, UUID policySetId, Map<String, Object> rule) {
        String ruleId = String.valueOf(rule.get("ruleId"));
        jdbc.update("""
                DELETE FROM policy_rules
                WHERE tenant_id = ? AND policy_set_id = ? AND rule_id = ?
                """, tenantId, policySetId, ruleId);
        jdbc.update("""
                INSERT INTO policy_rules (
                  tenant_id, policy_set_id, rule_id, title, description, source,
                  applies_to, when_json, then_json, severity, status, origin, warnings, rule_body
                ) VALUES (?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?,?,?,?::jsonb,?::jsonb)
                """,
                tenantId,
                policySetId,
                ruleId,
                rule.get("title"),
                rule.get("description"),
                toJson(rule.get("source")),
                toJson(rule.get("appliesTo")),
                toJson(rule.get("when")),
                toJson(rule.get("then")),
                rule.getOrDefault("severity", "MEDIUM"),
                rule.getOrDefault("status", "PROPOSED"),
                rule.getOrDefault("origin", "LLM"),
                toJson(rule.getOrDefault("warnings", List.of())),
                toJson(rule)
        );
    }

    public void insertKeyword(
            UUID tenantId, UUID policySetId, String term, String lang, String category, double weight, String ruleId
    ) {
        jdbc.update("""
                INSERT INTO policy_keywords
                  (tenant_id, policy_set_id, term, lang, category, weight, source_rule_id)
                VALUES (?,?,?,?,?,?,?)
                """, tenantId, policySetId, term, lang, category, weight, ruleId);
    }

    public void insertFact(UUID tenantId, UUID policySetId, String ruleId, String text) {
        jdbc.update("""
                INSERT INTO policy_facts (tenant_id, policy_set_id, source_rule_id, text)
                VALUES (?,?,?,?)
                """, tenantId, policySetId, ruleId, text);
    }

    private Map<String, Object> mapCompilation(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getObject("id", UUID.class).toString());
        m.put("tenantId", rs.getObject("tenant_id", UUID.class).toString());
        m.put("mode", rs.getString("mode"));
        m.put("status", rs.getString("status"));
        m.put("documentIds", fromUuidArray(rs.getArray("document_ids")));
        UUID setId = rs.getObject("policy_set_id", UUID.class);
        m.put("policySetId", setId == null ? null : setId.toString());
        UUID createdBy = rs.getObject("created_by", UUID.class);
        m.put("createdBy", createdBy == null ? null : createdBy.toString());
        m.put("createdAt", toIso(rs.getTimestamp("created_at")));
        m.put("startedAt", toIso(rs.getTimestamp("started_at")));
        m.put("finishedAt", toIso(rs.getTimestamp("finished_at")));
        m.put("error", rs.getString("error"));
        m.put("progress", parseJson(rs.getString("progress")));
        return m;
    }

    private Map<String, Object> mapChunk(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getObject("id", UUID.class).toString());
        m.put("documentId", rs.getObject("document_id", UUID.class).toString());
        m.put("chunkId", rs.getObject("chunk_id", UUID.class).toString());
        m.put("status", rs.getString("status"));
        m.put("skipReason", rs.getString("skip_reason"));
        m.put("rulesProposed", rs.getInt("rules_proposed"));
        m.put("rulesRejected", rs.getInt("rules_rejected"));
        m.put("error", rs.getString("error"));
        return m;
    }

    private List<String> fromUuidArray(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        Object[] objs = (Object[]) array.getArray();
        List<String> out = new ArrayList<>();
        for (Object o : objs) {
            out.add(String.valueOf(o));
        }
        return out;
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o == null ? Map.of() : o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Object parseJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(raw, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String toIso(Timestamp ts) {
        return ts == null ? null : ts.toInstant().toString();
    }
}
