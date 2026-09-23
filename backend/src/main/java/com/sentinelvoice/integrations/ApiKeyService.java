package com.sentinelvoice.integrations;

import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.audit.AuditLedgerService;
import com.sentinelvoice.security.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Array;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Tenant-scoped API key lifecycle (F17).
 */
@Service
public class ApiKeyService {

    public static final String SECRET_PREFIX = "sv_live_";

    private final JdbcTemplate jdbc;
    private final AuditLedgerService auditLedgerService;
    private final SecureRandom secureRandom = new SecureRandom();

    public ApiKeyService(JdbcTemplate jdbc, AuditLedgerService auditLedgerService) {
        this.jdbc = jdbc;
        this.auditLedgerService = auditLedgerService;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(UUID tenantId) {
        return jdbc.query("""
                SELECT id, name, prefix, scopes, created_by, last_used_at, expires_at,
                       status, usage_count, rate_limit_rps, created_at, revoked_at
                FROM api_keys
                WHERE tenant_id = ?
                ORDER BY created_at DESC
                """, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getObject("id", UUID.class).toString());
            m.put("name", rs.getString("name"));
            m.put("prefix", rs.getString("prefix"));
            m.put("scopes", arrayToList(rs.getArray("scopes")));
            UUID createdBy = rs.getObject("created_by", UUID.class);
            m.put("createdBy", createdBy == null ? null : createdBy.toString());
            m.put("lastUsedAt", ts(rs.getTimestamp("last_used_at")));
            m.put("expiresAt", ts(rs.getTimestamp("expires_at")));
            m.put("status", rs.getString("status"));
            m.put("usageCount", rs.getLong("usage_count"));
            m.put("rateLimitRps", rs.getInt("rate_limit_rps"));
            m.put("createdAt", ts(rs.getTimestamp("created_at")));
            m.put("revokedAt", ts(rs.getTimestamp("revoked_at")));
            return m;
        }, tenantId);
    }

    @Transactional
    public Map<String, Object> create(UUID tenantId, UUID actorId, String name, List<String> scopes, Instant expiresAt, Integer rateLimitRps) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name required");
        }
        Set<String> normalized = normalizeScopes(scopes);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("at least one scope required");
        }
        String secret = SECRET_PREFIX + randomToken(32);
        String prefix = secret.substring(0, Math.min(16, secret.length()));
        String hash = sha256Hex(secret);
        UUID id = UUID.randomUUID();
        int rps = rateLimitRps == null ? 60 : Math.max(1, Math.min(rateLimitRps, 1000));

        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO api_keys (
                      id, tenant_id, name, prefix, key_hash, scopes, created_by,
                      expires_at, status, rate_limit_rps
                    ) VALUES (?,?,?,?,?,?,?,?,'ACTIVE',?)
                    """);
            ps.setObject(1, id);
            ps.setObject(2, tenantId);
            ps.setString(3, name.trim());
            ps.setString(4, prefix);
            ps.setString(5, hash);
            ps.setArray(6, con.createArrayOf("text", normalized.toArray()));
            ps.setObject(7, actorId);
            if (expiresAt == null) {
                ps.setTimestamp(8, null);
            } else {
                ps.setTimestamp(8, Timestamp.from(expiresAt));
            }
            ps.setInt(9, rps);
            return ps;
        });

        auditLedgerService.append(
                tenantId,
                null,
                AuditEventType.CONFIG_CHANGE,
                "USER",
                actorId == null ? null : actorId.toString(),
                Map.of(
                        "area", "api_keys",
                        "action", "created",
                        "keyId", id.toString(),
                        "prefix", prefix,
                        "scopes", normalized
                )
        );

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id.toString());
        out.put("name", name.trim());
        out.put("prefix", prefix);
        out.put("scopes", List.copyOf(normalized));
        out.put("expiresAt", expiresAt == null ? null : expiresAt.toString());
        out.put("rateLimitRps", rps);
        out.put("status", "ACTIVE");
        out.put("secret", secret);
        out.put("message", "Store this secret now — it will not be shown again.");
        return out;
    }

    @Transactional
    public void revoke(UUID tenantId, UUID actorId, UUID keyId) {
        int n = jdbc.update("""
                UPDATE api_keys
                SET status = 'REVOKED', revoked_at = now()
                WHERE id = ? AND tenant_id = ? AND status = 'ACTIVE'
                """, keyId, tenantId);
        if (n == 0) {
            throw new IllegalArgumentException("api key not found or already revoked");
        }
        auditLedgerService.append(
                tenantId,
                null,
                AuditEventType.CONFIG_CHANGE,
                "USER",
                actorId == null ? null : actorId.toString(),
                Map.of("area", "api_keys", "action", "revoked", "keyId", keyId.toString())
        );
    }

    @Transactional(readOnly = true)
    public Optional<ResolvedKey> resolveBySecret(String secret) {
        if (secret == null || secret.isBlank() || !secret.startsWith(SECRET_PREFIX)) {
            return Optional.empty();
        }
        String hash = sha256Hex(secret.trim());
        // Lookup by hash is global (unique) — use platform path so RLS does not hide the row
        // before we know the tenant. Key material never leaves the server.
        List<ResolvedKey> rows = TenantContext.runAsPlatform(() -> jdbc.query("""
                SELECT id, tenant_id, name, prefix, scopes, status, expires_at, rate_limit_rps
                FROM fn_resolve_api_key(?)
                """, (rs, i) -> {
            Instant expires = rs.getTimestamp("expires_at") == null
                    ? null
                    : rs.getTimestamp("expires_at").toInstant();
            String status = rs.getString("status");
            if (!"ACTIVE".equals(status)) {
                return null;
            }
            if (expires != null && expires.isBefore(Instant.now())) {
                return null;
            }
            Set<String> scopes = Set.copyOf(arrayToList(rs.getArray("scopes")));
            return new ResolvedKey(
                    rs.getObject("id", UUID.class),
                    rs.getObject("tenant_id", UUID.class),
                    rs.getString("name"),
                    rs.getString("prefix"),
                    scopes,
                    rs.getInt("rate_limit_rps")
            );
        }, hash));
        return rows.stream().filter(r -> r != null).findFirst();
    }

    @Transactional
    public void touchUsage(UUID keyId) {
        TenantContext.runAsPlatform(() -> {
            jdbc.update("SELECT fn_touch_api_key(?)", keyId);
            return null;
        });
    }

    public static String sha256Hex(String secret) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(secret.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private String randomToken(int bytes) {
        byte[] buf = new byte[bytes];
        secureRandom.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static Set<String> normalizeScopes(List<String> scopes) {
        if (scopes == null) {
            return Set.of();
        }
        return scopes.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(ApiKeyScopes::isValid)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private static List<String> arrayToList(Array array) throws java.sql.SQLException {
        if (array == null) {
            return List.of();
        }
        Object raw = array.getArray();
        if (raw instanceof String[] strings) {
            return Arrays.asList(strings);
        }
        if (raw instanceof Object[] objects) {
            List<String> out = new ArrayList<>();
            for (Object o : objects) {
                if (o != null) {
                    out.add(String.valueOf(o));
                }
            }
            return out;
        }
        return List.of();
    }

    private static String ts(Timestamp t) {
        return t == null ? null : t.toInstant().toString();
    }

    public record ResolvedKey(
            UUID keyId,
            UUID tenantId,
            String name,
            String prefix,
            Set<String> scopes,
            int rateLimitRps
    ) {
    }
}
