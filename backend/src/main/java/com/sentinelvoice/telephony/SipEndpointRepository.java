package com.sentinelvoice.telephony;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class SipEndpointRepository {

    private final JdbcTemplate jdbc;

    public SipEndpointRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<TelephonyModels.SipEndpointView> list(UUID tenantId) {
        return jdbc.query(
                """
                SELECT id, tenant_id, employee_id, extension, username, status,
                       last_registered_at, created_at, updated_at
                FROM sip_endpoints
                WHERE tenant_id = ?
                ORDER BY extension
                """,
                (rs, i) -> mapRow(rs),
                tenantId
        );
    }

    public Optional<TelephonyModels.SipEndpointView> findById(UUID tenantId, UUID id) {
        List<TelephonyModels.SipEndpointView> rows = jdbc.query(
                """
                SELECT id, tenant_id, employee_id, extension, username, status,
                       last_registered_at, created_at, updated_at
                FROM sip_endpoints
                WHERE tenant_id = ? AND id = ?
                """,
                (rs, i) -> mapRow(rs),
                tenantId, id
        );
        return rows.stream().findFirst();
    }

    public Optional<TelephonyModels.SipEndpointView> findByEmployee(UUID tenantId, UUID employeeId) {
        List<TelephonyModels.SipEndpointView> rows = jdbc.query(
                """
                SELECT id, tenant_id, employee_id, extension, username, status,
                       last_registered_at, created_at, updated_at
                FROM sip_endpoints
                WHERE tenant_id = ? AND employee_id = ?
                ORDER BY created_at ASC
                LIMIT 1
                """,
                (rs, i) -> mapRow(rs),
                tenantId, employeeId
        );
        return rows.stream().findFirst();
    }

    public Optional<TelephonyModels.SipEndpointView> findByExtension(UUID tenantId, String extension) {
        List<TelephonyModels.SipEndpointView> rows = jdbc.query(
                """
                SELECT id, tenant_id, employee_id, extension, username, status,
                       last_registered_at, created_at, updated_at
                FROM sip_endpoints
                WHERE tenant_id = ? AND extension = ?
                """,
                (rs, i) -> mapRow(rs),
                tenantId, extension
        );
        return rows.stream().findFirst();
    }

    public Optional<String> findPasswordCiphertext(UUID tenantId, UUID id) {
        List<String> rows = jdbc.query(
                "SELECT password_ciphertext FROM sip_endpoints WHERE tenant_id = ? AND id = ?",
                (rs, i) -> rs.getString(1),
                tenantId, id
        );
        return rows.stream().findFirst();
    }

    public int maxSuffixInBlock(UUID tenantId, int blockBase) {
        Integer max = jdbc.queryForObject(
                """
                SELECT COALESCE(MAX(CAST(extension AS INTEGER)), ?)
                FROM sip_endpoints
                WHERE tenant_id = ?
                  AND extension ~ '^[0-9]+$'
                  AND CAST(extension AS INTEGER) >= ?
                  AND CAST(extension AS INTEGER) < ?
                """,
                Integer.class,
                blockBase,
                tenantId,
                blockBase + 1,
                blockBase + 1000
        );
        return max == null ? blockBase : max;
    }

    public TelephonyModels.SipEndpointView insert(
            UUID tenantId,
            UUID employeeId,
            String extension,
            String username,
            String passwordCiphertext,
            String status
    ) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO sip_endpoints (
                  id, tenant_id, employee_id, extension, username, password_ciphertext, status
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                id, tenantId, employeeId, extension, username, passwordCiphertext, status
        );
        return findById(tenantId, id).orElseThrow();
    }

    public void updatePassword(UUID tenantId, UUID id, String passwordCiphertext) {
        int n = jdbc.update(
                """
                UPDATE sip_endpoints
                SET password_ciphertext = ?, updated_at = now()
                WHERE tenant_id = ? AND id = ?
                """,
                passwordCiphertext, tenantId, id
        );
        if (n == 0) {
            throw new IllegalArgumentException("sip endpoint not found");
        }
    }

    public void updateStatus(UUID tenantId, UUID id, String status) {
        int n = jdbc.update(
                """
                UPDATE sip_endpoints
                SET status = ?, updated_at = now()
                WHERE tenant_id = ? AND id = ?
                """,
                status, tenantId, id
        );
        if (n == 0) {
            throw new IllegalArgumentException("sip endpoint not found");
        }
    }

    public void delete(UUID tenantId, UUID id) {
        jdbc.update("DELETE FROM sip_endpoints WHERE tenant_id = ? AND id = ?", tenantId, id);
    }

    public Optional<TelephonyModels.ExtensionResolveResult> resolveExtensionCrossTenant(String extension) {
        List<TelephonyModels.ExtensionResolveResult> rows = jdbc.query(
                """
                SELECT tenant_id, employee_id, endpoint_id, username, extension, status
                FROM fn_telephony_resolve_extension(?)
                """,
                (rs, i) -> mapResolve(rs),
                extension
        );
        return rows.stream().findFirst();
    }

    /**
     * Extension lookup scoped to one tenant. Binds {@code app.tenant_id} on the borrowed
     * connection for RLS (internal callers have no tenant context) and restores it afterwards.
     */
    public Optional<TelephonyModels.ExtensionResolveResult> resolveExtensionInTenant(UUID tenantId, String extension) {
        return jdbc.execute((ConnectionCallback<Optional<TelephonyModels.ExtensionResolveResult>>) con -> {
            String previous;
            try (Statement st = con.createStatement();
                 ResultSet rs = st.executeQuery("SELECT current_setting('app.tenant_id', true)")) {
                previous = rs.next() ? rs.getString(1) : null;
            }
            setTenantSetting(con, tenantId.toString());
            try (PreparedStatement ps = con.prepareStatement(
                    """
                    SELECT tenant_id, employee_id, id AS endpoint_id, username, extension, status
                    FROM sip_endpoints
                    WHERE tenant_id = ? AND extension = ?
                      AND status IN ('ACTIVE', 'LAB_ATTACKER')
                    ORDER BY created_at ASC
                    LIMIT 1
                    """)) {
                ps.setObject(1, tenantId);
                ps.setString(2, extension);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(mapResolve(rs)) : Optional.empty();
                }
            } finally {
                setTenantSetting(con, previous == null ? "" : previous);
            }
        });
    }

    private static void setTenantSetting(Connection con, String value) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT set_config('app.tenant_id', ?, false)")) {
            ps.setString(1, value);
            ps.execute();
        }
    }

    public Optional<TelephonyModels.ExtensionResolveResult> resolveUsernameCrossTenant(String username) {
        List<TelephonyModels.ExtensionResolveResult> rows = jdbc.query(
                """
                SELECT tenant_id, employee_id, endpoint_id, username, extension, status
                FROM fn_telephony_resolve_username(?)
                """,
                (rs, i) -> mapResolve(rs),
                username
        );
        return rows.stream().findFirst();
    }

    public int telephonyOrdinal(UUID tenantId) {
        Integer ordinal = jdbc.queryForObject(
                "SELECT telephony_ordinal FROM tenants WHERE id = ?",
                Integer.class,
                tenantId
        );
        if (ordinal == null) {
            throw new IllegalStateException("tenant missing telephony_ordinal: " + tenantId);
        }
        return ordinal;
    }

    private static TelephonyModels.SipEndpointView mapRow(ResultSet rs) throws SQLException {
        return new TelephonyModels.SipEndpointView(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("employee_id", UUID.class),
                rs.getString("extension"),
                rs.getString("username"),
                rs.getString("status"),
                toInstant(rs.getTimestamp("last_registered_at")),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at"))
        );
    }

    private static TelephonyModels.ExtensionResolveResult mapResolve(ResultSet rs) throws SQLException {
        return new TelephonyModels.ExtensionResolveResult(
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("employee_id", UUID.class),
                rs.getObject("endpoint_id", UUID.class),
                rs.getString("username"),
                rs.getString("extension"),
                rs.getString("status")
        );
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
