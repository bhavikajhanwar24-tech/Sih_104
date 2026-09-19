package com.sentinelvoice.policy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Citation lookups against policy_rules / policy_sets (F5 addendum / F6 precursor).
 */
@Repository
public class PolicyRuleCitationRepository {

    private final JdbcTemplate jdbc;

    public PolicyRuleCitationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Rules in ACTIVE or PENDING_APPROVAL sets that cite {@code documentId}.
     */
    public List<Map<String, Object>> findBlockingCitations(UUID tenantId, UUID documentId) {
        String sql = """
                SELECT r.id::text AS id, r.title AS title
                FROM policy_rules r
                INNER JOIN policy_sets s ON s.id = r.policy_set_id AND s.tenant_id = r.tenant_id
                WHERE r.tenant_id = ?
                  AND s.status IN ('ACTIVE', 'PENDING_APPROVAL')
                  AND (r.source ->> 'documentId') = ?
                ORDER BY r.title
                """;
        return jdbc.query(sql, (rs, rowNum) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getString("id"));
            m.put("title", rs.getString("title"));
            return m;
        }, tenantId, documentId.toString());
    }

    /**
     * Mark DRAFT / SUPERSEDED rules that cited the document with sourceDeleted=true.
     */
    public int markSourceDeleted(UUID tenantId, UUID documentId) {
        String sql = """
                UPDATE policy_rules r
                SET source = COALESCE(r.source, '{}'::jsonb) || '{"sourceDeleted": true}'::jsonb
                FROM policy_sets s
                WHERE r.policy_set_id = s.id
                  AND r.tenant_id = ?
                  AND s.tenant_id = ?
                  AND s.status IN ('DRAFT', 'SUPERSEDED')
                  AND (r.source ->> 'documentId') = ?
                """;
        return jdbc.update(sql, tenantId, tenantId, documentId.toString());
    }
}
