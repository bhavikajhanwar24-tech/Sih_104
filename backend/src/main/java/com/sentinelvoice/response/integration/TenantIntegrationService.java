package com.sentinelvoice.response.integration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelvoice.response.crypto.SecretBox;
import com.sentinelvoice.security.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class TenantIntegrationService {

    public static final Set<String> KINDS = Set.of(
            "SMS_NOTIFICATION",
            "SUPERVISOR_NOTIFY",
            "SUPERVISOR_BRIDGE",
            "CORE_BANKING",
            "ITSM",
            "CUSTOM_WEBHOOK"
    );

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final SecretBox secretBox;
    private final RestTemplate restTemplate;

    public TenantIntegrationService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            SecretBox secretBox,
            RestTemplate restTemplate
    ) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.secretBox = secretBox;
        this.restTemplate = restTemplate;
    }

    public List<Map<String, Object>> list() {
        UUID tenantId = TenantContext.require().tenantId();
        return jdbc.query(
                "SELECT * FROM tenant_integrations WHERE tenant_id = ? ORDER BY kind",
                (rs, i) -> mapRow(rs, false),
                tenantId
        );
    }

    public Optional<Map<String, Object>> get(String kind) {
        UUID tenantId = TenantContext.require().tenantId();
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT * FROM tenant_integrations WHERE tenant_id = ? AND kind = ?",
                (rs, i) -> mapRow(rs, false),
                tenantId, kind
        );
        return rows.stream().findFirst();
    }

    public boolean isConfigured(UUID tenantId, String kind) {
        if (kind == null || kind.isBlank()) {
            return true;
        }
        List<Boolean> rows = jdbc.query(
                """
                SELECT enabled FROM tenant_integrations
                WHERE tenant_id = ? AND kind = ? AND enabled = true
                LIMIT 1
                """,
                (rs, i) -> rs.getBoolean("enabled"),
                tenantId, kind
        );
        return !rows.isEmpty();
    }

    /** Internal: decrypt secrets for executors. */
    public Optional<IntegrationSecrets> loadSecrets(UUID tenantId, String kind) {
        List<IntegrationSecrets> rows = jdbc.query(
                """
                SELECT enabled, config::text AS config, secrets_enc
                FROM tenant_integrations
                WHERE tenant_id = ? AND kind = ?
                """,
                (rs, i) -> {
                    boolean enabled = rs.getBoolean("enabled");
                    Map<String, Object> config = parseMap(rs.getString("config"));
                    byte[] enc = rs.getBytes("secrets_enc");
                    Map<String, Object> secrets = Map.of();
                    if (enc != null) {
                        String json = secretBox.decrypt(enc);
                        if (json != null && !json.isBlank()) {
                            secrets = parseMap(json);
                        }
                    }
                    return new IntegrationSecrets(kind, enabled, config, secrets);
                },
                tenantId, kind
        );
        return rows.stream().findFirst();
    }

    @Transactional
    public Map<String, Object> upsert(String kind, Map<String, Object> body) {
        if (!KINDS.contains(kind)) {
            throw new IllegalArgumentException("Unknown integration kind: " + kind);
        }
        TenantContext ctx = TenantContext.require();
        UUID tenantId = ctx.tenantId();
        UUID userId = ctx.userId();
        boolean enabled = body.get("enabled") instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(body.getOrDefault("enabled", "false")));
        @SuppressWarnings("unchecked")
        Map<String, Object> config = body.get("config") instanceof Map<?, ?> m
                ? new LinkedHashMap<>((Map<String, Object>) m)
                : new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> secrets = body.get("secrets") instanceof Map<?, ?> m
                ? new LinkedHashMap<>((Map<String, Object>) m)
                : null;

        byte[] secretsEnc = null;
        if (secrets != null && !secrets.isEmpty()) {
            try {
                secretsEnc = secretBox.encrypt(mapper.writeValueAsString(secrets));
            } catch (Exception e) {
                throw new IllegalStateException("Failed to encrypt secrets", e);
            }
        }

        Optional<Map<String, Object>> existing = get(kind);
        if (existing.isEmpty()) {
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO tenant_integrations (
                      id, tenant_id, kind, config, secrets_enc, enabled, updated_by
                    ) VALUES (?, ?, ?, ?::jsonb, ?, ?, ?)
                    """,
                    id, tenantId, kind, toJson(config), secretsEnc, enabled, userId
            );
        } else {
            if (secretsEnc != null) {
                jdbc.update("""
                        UPDATE tenant_integrations
                        SET config = ?::jsonb, secrets_enc = ?, enabled = ?, updated_by = ?, updated_at = now()
                        WHERE tenant_id = ? AND kind = ?
                        """,
                        toJson(config), secretsEnc, enabled, userId, tenantId, kind
                );
            } else {
                jdbc.update("""
                        UPDATE tenant_integrations
                        SET config = ?::jsonb, enabled = ?, updated_by = ?, updated_at = now()
                        WHERE tenant_id = ? AND kind = ?
                        """,
                        toJson(config), enabled, userId, tenantId, kind
                );
            }
        }
        return get(kind).orElseThrow();
    }

    @Transactional
    public Map<String, Object> test(String kind) {
        TenantContext ctx = TenantContext.require();
        UUID tenantId = ctx.tenantId();
        Optional<IntegrationSecrets> loaded = loadSecrets(tenantId, kind);
        boolean ok = false;
        String detail;
        try {
            if (loaded.isEmpty() || !loaded.get().enabled()) {
                detail = "Integration not configured or disabled";
            } else {
                Map<String, Object> secrets = loaded.get().secrets();
                Map<String, Object> config = loaded.get().config();
                String pingUrl = firstNonBlank(
                        str(config.get("testUrl")),
                        str(config.get("webhookUrl")),
                        str(secrets.get("webhookUrl"))
                );
                if (pingUrl != null) {
                    restTemplate.postForEntity(pingUrl, Map.of(
                            "type", "SENTINELVOICE_PING",
                            "kind", kind,
                            "ts", Instant.now().toString()
                    ), String.class);
                    ok = true;
                    detail = "Ping accepted by " + pingUrl;
                } else if ("SUPERVISOR_BRIDGE".equals(kind)) {
                    String ext = firstNonBlank(str(config.get("extension")), str(secrets.get("extension")));
                    ok = ext != null && !ext.isBlank();
                    detail = ok ? "Supervisor extension present: " + ext : "No supervisor extension configured";
                } else if ("SMS_NOTIFICATION".equals(kind)) {
                    ok = true;
                    detail = "Log-only SMS provider — ping simulated OK";
                } else {
                    ok = true;
                    detail = "No webhook URL — configuration accepted (dry-run)";
                }
            }
        } catch (Exception e) {
            ok = false;
            detail = "Ping failed: " + e.getMessage();
        }
        jdbc.update("""
                UPDATE tenant_integrations
                SET last_test_at = now(), last_test_ok = ?, last_test_detail = ?, updated_at = now()
                WHERE tenant_id = ? AND kind = ?
                """, ok, detail, tenantId, kind);
        Map<String, Object> out = new LinkedHashMap<>(get(kind).orElse(Map.of("kind", kind)));
        out.put("lastTestOk", ok);
        out.put("lastTestDetail", detail);
        return out;
    }

    public List<String> degradationWarnings(UUID tenantId, com.sentinelvoice.response.ResponsePlanDocument doc) {
        List<String> warnings = new ArrayList<>();
        Map<String, com.sentinelvoice.response.ActionCatalogue.ActionDef> catalogue =
                com.sentinelvoice.response.ActionCatalogue.byKey();
        for (String level : com.sentinelvoice.response.ResponsePlanDocument.LEVEL_KEYS) {
            var lp = doc.level(level);
            if (lp == null) {
                continue;
            }
            for (var step : lp.steps()) {
                var def = catalogue.get(step.action());
                if (def == null || def.integrationKind() == null) {
                    continue;
                }
                if (def.needsIntegration() && !isConfigured(tenantId, def.integrationKind())) {
                    warnings.add(level + " " + step.action()
                            + " will degrade to advisory (integration " + def.integrationKind() + " not configured)");
                }
            }
        }
        return warnings;
    }

    public record IntegrationSecrets(
            String kind,
            boolean enabled,
            Map<String, Object> config,
            Map<String, Object> secrets
    ) {
    }

    private Map<String, Object> mapRow(ResultSet rs, boolean includeSecrets) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getObject("id", UUID.class).toString());
        m.put("tenantId", rs.getObject("tenant_id", UUID.class).toString());
        m.put("kind", rs.getString("kind"));
        m.put("config", parseMap(rs.getString("config")));
        m.put("enabled", rs.getBoolean("enabled"));
        m.put("hasSecrets", rs.getBytes("secrets_enc") != null);
        m.put("lastTestAt", toIso(rs.getTimestamp("last_test_at")));
        m.put("lastTestOk", rs.getObject("last_test_ok"));
        m.put("lastTestDetail", rs.getString("last_test_detail"));
        m.put("updatedAt", toIso(rs.getTimestamp("updated_at")));
        return m;
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

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o == null ? Map.of() : o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String toIso(Timestamp ts) {
        return ts == null ? null : ts.toInstant().toString();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (v != null && !v.isBlank() && !"null".equals(v)) {
                return v;
            }
        }
        return null;
    }
}
