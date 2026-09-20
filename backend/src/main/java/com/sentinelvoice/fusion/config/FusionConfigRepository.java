package com.sentinelvoice.fusion.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class FusionConfigRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public FusionConfigRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Optional<Map<String, Object>> findActive(UUID tenantId) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT * FROM fusion_configs WHERE tenant_id = ? AND status = 'ACTIVE' LIMIT 1",
                (rs, i) -> mapRow(rs),
                tenantId
        );
        return rows.stream().findFirst();
    }

    public List<Map<String, Object>> listHistory(UUID tenantId) {
        return jdbc.query(
                """
                SELECT * FROM fusion_configs
                WHERE tenant_id = ?
                ORDER BY version DESC, created_at DESC
                """,
                (rs, i) -> mapRow(rs),
                tenantId
        );
    }

    public Optional<Map<String, Object>> findById(UUID tenantId, UUID id) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT * FROM fusion_configs WHERE tenant_id = ? AND id = ?",
                (rs, i) -> mapRow(rs),
                tenantId, id
        );
        return rows.stream().findFirst();
    }

    public UUID insertDraft(UUID tenantId, int version, Map<String, Object> config, String sha, UUID createdBy) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fusion_configs (
                  id, tenant_id, version, status, config, created_by, content_sha256
                ) VALUES (?, ?, ?, 'DRAFT', ?::jsonb, ?, ?)
                """,
                id, tenantId, version, toJson(config), createdBy, sha
        );
        return id;
    }

    public void updateDraftConfig(UUID tenantId, UUID id, Map<String, Object> config, String sha) {
        int n = jdbc.update("""
                UPDATE fusion_configs
                SET config = ?::jsonb,
                    content_sha256 = ?,
                    updated_at = now()
                WHERE tenant_id = ? AND id = ? AND status = 'DRAFT'
                """, toJson(config), sha, tenantId, id);
        if (n == 0) {
            throw new FusionConfigException("BAD_STATE", "Only DRAFT fusion configs can be updated");
        }
    }

    public void submit(UUID tenantId, UUID id, UUID submitterId) {
        int n = jdbc.update("""
                UPDATE fusion_configs
                SET status = 'PENDING_APPROVAL',
                    submitted_by = ?,
                    updated_at = now()
                WHERE tenant_id = ? AND id = ? AND status = 'DRAFT'
                """, submitterId, tenantId, id);
        if (n == 0) {
            throw new FusionConfigException("BAD_STATE", "Fusion config could not be submitted");
        }
    }

    public void approve(UUID tenantId, UUID id, UUID approverId) {
        jdbc.update("""
                UPDATE fusion_configs SET status = 'SUPERSEDED', updated_at = now()
                WHERE tenant_id = ? AND status = 'ACTIVE'
                """, tenantId);
        int n = jdbc.update("""
                UPDATE fusion_configs
                SET status = 'ACTIVE',
                    approved_by = ?,
                    approved_at = now(),
                    updated_at = now()
                WHERE tenant_id = ? AND id = ? AND status = 'PENDING_APPROVAL'
                """, approverId, tenantId, id);
        if (n == 0) {
            throw new FusionConfigException("BAD_STATE", "Fusion config could not be approved");
        }
    }

    public void reject(UUID tenantId, UUID id, UUID approverId, String comment) {
        int n = jdbc.update("""
                UPDATE fusion_configs
                SET status = 'REJECTED',
                    approved_by = ?,
                    approved_at = now(),
                    reject_comment = ?,
                    updated_at = now()
                WHERE tenant_id = ? AND id = ? AND status = 'PENDING_APPROVAL'
                """, approverId, comment, tenantId, id);
        if (n == 0) {
            throw new FusionConfigException("BAD_STATE", "Fusion config could not be rejected");
        }
    }

    public int nextVersion(UUID tenantId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM fusion_configs WHERE tenant_id = ?",
                Integer.class,
                tenantId
        );
        return (max == null ? 0 : max) + 1;
    }

    public Map<String, Object> platformDefaultConfig() {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT config::text AS config FROM platform_fusion_defaults WHERE id = 1",
                (rs, i) -> parseMap(rs.getString("config"))
        );
        if (rows.isEmpty()) {
            throw new FusionConfigException("PLATFORM_DEFAULT_MISSING", "platform fusion default missing");
        }
        return rows.get(0);
    }

    public String sha256Canonical(Map<String, Object> config) {
        try {
            FusionConfigDocument doc = FusionConfigDocument.parse(config, mapper);
            String json = mapper.writeValueAsString(doc.toCanonicalMap());
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash fusion config", e);
        }
    }

    private Map<String, Object> mapRow(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getObject("id", UUID.class).toString());
        m.put("tenantId", rs.getObject("tenant_id", UUID.class).toString());
        m.put("version", rs.getInt("version"));
        m.put("status", rs.getString("status"));
        m.put("config", parseMap(rs.getString("config")));
        m.put("createdBy", uuidStr(rs, "created_by"));
        m.put("submittedBy", uuidStr(rs, "submitted_by"));
        m.put("approvedBy", uuidStr(rs, "approved_by"));
        m.put("approvedAt", toIso(rs.getTimestamp("approved_at")));
        m.put("rejectComment", rs.getString("reject_comment"));
        m.put("contentSha256", rs.getString("content_sha256"));
        m.put("createdAt", toIso(rs.getTimestamp("created_at")));
        m.put("updatedAt", toIso(rs.getTimestamp("updated_at")));
        return m;
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

    private static String toIso(Timestamp ts) {
        return ts == null ? null : ts.toInstant().toString();
    }
}
