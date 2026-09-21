package com.sentinelvoice.telephony;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class TrunkRepository {

    private final JdbcTemplate jdbc;

    public TrunkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<TelephonyModels.TrunkView> list(UUID tenantId) {
        return jdbc.query(
                """
                SELECT id, tenant_id, name, type, cli_prefixes, provider_metadata::text AS provider_metadata,
                       created_at, updated_at
                FROM trunks
                WHERE tenant_id = ?
                ORDER BY name
                """,
                (rs, i) -> mapRow(rs),
                tenantId
        );
    }

    public Optional<TelephonyModels.TrunkView> findById(UUID tenantId, UUID id) {
        List<TelephonyModels.TrunkView> rows = jdbc.query(
                """
                SELECT id, tenant_id, name, type, cli_prefixes, provider_metadata::text AS provider_metadata,
                       created_at, updated_at
                FROM trunks
                WHERE tenant_id = ? AND id = ?
                """,
                (rs, i) -> mapRow(rs),
                tenantId, id
        );
        return rows.stream().findFirst();
    }

    public TelephonyModels.TrunkView insert(
            UUID tenantId,
            String name,
            String type,
            List<String> cliPrefixes,
            String providerMetadataJson
    ) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO trunks (id, tenant_id, name, type, cli_prefixes, provider_metadata)
                VALUES (?, ?, ?, ?, ?, ?::jsonb)
                """,
                id,
                tenantId,
                name,
                type,
                cliPrefixes == null ? new String[0] : cliPrefixes.toArray(String[]::new),
                providerMetadataJson == null ? "{}" : providerMetadataJson
        );
        return findById(tenantId, id).orElseThrow();
    }

    /**
     * Longest matching CLI prefix for this tenant (CARRIER / CCAAS). Used for SUSPECT_TRUNK.
     */
    public Optional<MatchedPrefix> findMatchingPrefix(UUID tenantId, String cli) {
        if (cli == null || cli.isBlank()) {
            return Optional.empty();
        }
        String digits = cli.replaceAll("[^0-9+]", "");
        List<MatchedPrefix> matches = jdbc.query(
                """
                SELECT id, prefix
                FROM trunks t,
                     LATERAL unnest(t.cli_prefixes) AS prefix
                WHERE t.tenant_id = ?
                  AND t.type IN ('CARRIER', 'CCAAS')
                  AND (? LIKE prefix || '%' OR ? LIKE replace(prefix, '+', '') || '%')
                ORDER BY length(prefix) DESC
                LIMIT 1
                """,
                (rs, i) -> new MatchedPrefix(
                        rs.getObject("id", UUID.class),
                        rs.getString("prefix")
                ),
                tenantId, digits, digits.replace("+", "")
        );
        return matches.stream().findFirst();
    }

    public record MatchedPrefix(UUID trunkId, String prefix) {
    }

    private TelephonyModels.TrunkView mapRow(ResultSet rs) throws SQLException {
        return new TelephonyModels.TrunkView(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("name"),
                rs.getString("type"),
                toStringList(rs.getArray("cli_prefixes")),
                parseJsonMap(rs.getString("provider_metadata")),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at"))
        );
    }

    private static List<String> toStringList(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        Object raw = array.getArray();
        if (raw instanceof String[] s) {
            return List.of(s);
        }
        if (raw instanceof Object[] o) {
            return Arrays.stream(o).map(String::valueOf).toList();
        }
        return List.of();
    }

    private static Map<String, Object> parseJsonMap(String raw) {
        if (raw == null || raw.isBlank() || "{}".equals(raw.trim())) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(raw, LinkedHashMap.class);
            return m;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
