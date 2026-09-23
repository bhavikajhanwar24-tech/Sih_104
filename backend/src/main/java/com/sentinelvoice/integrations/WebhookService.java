package com.sentinelvoice.integrations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.audit.AuditLedgerService;
import com.sentinelvoice.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Outbound webhook endpoints + delivery worker (F17).
 */
@Service
public class WebhookService {

    public static final Set<String> EVENT_TYPES = Set.of(
            "risk.level_changed",
            "action.executed",
            "action.failed",
            "session.ended",
            "config.approved"
    );

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);
    private static final long REPLAY_WINDOW_SEC = 300;

    private final JdbcTemplate jdbc;
    private final AuditLedgerService auditLedgerService;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate = new RestTemplate();

    public WebhookService(JdbcTemplate jdbc, AuditLedgerService auditLedgerService, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.auditLedgerService = auditLedgerService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listEndpoints(UUID tenantId) {
        return jdbc.query("""
                SELECT id, url, events, status, description, created_at, updated_at
                FROM webhook_endpoints
                WHERE tenant_id = ?
                ORDER BY created_at DESC
                """, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getObject("id", UUID.class).toString());
            m.put("url", rs.getString("url"));
            m.put("events", arrayToList(rs.getArray("events")));
            m.put("status", rs.getString("status"));
            m.put("description", rs.getString("description"));
            m.put("createdAt", ts(rs.getTimestamp("created_at")));
            m.put("updatedAt", ts(rs.getTimestamp("updated_at")));
            return m;
        }, tenantId);
    }

    @Transactional
    public Map<String, Object> createEndpoint(
            UUID tenantId,
            UUID actorId,
            String url,
            List<String> events,
            String description
    ) {
        if (url == null || url.isBlank() || !(url.startsWith("https://") || url.startsWith("http://"))) {
            throw new IllegalArgumentException("url must be http(s)");
        }
        List<String> ev = normalizeEvents(events);
        if (ev.isEmpty()) {
            throw new IllegalArgumentException("at least one event required");
        }
        UUID id = UUID.randomUUID();
        String secret = "whsec_" + UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO webhook_endpoints (
                      id, tenant_id, url, secret, events, status, description, created_by
                    ) VALUES (?,?,?,?,?,'ACTIVE',?,?)
                    """);
            ps.setObject(1, id);
            ps.setObject(2, tenantId);
            ps.setString(3, url.trim());
            ps.setString(4, secret);
            ps.setArray(5, con.createArrayOf("text", ev.toArray()));
            ps.setString(6, description);
            ps.setObject(7, actorId);
            return ps;
        });

        auditLedgerService.append(
                tenantId, null, AuditEventType.CONFIG_CHANGE, "USER",
                actorId == null ? null : actorId.toString(),
                Map.of("area", "webhooks", "action", "created", "endpointId", id.toString())
        );

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id.toString());
        out.put("url", url.trim());
        out.put("events", ev);
        out.put("status", "ACTIVE");
        out.put("description", description);
        out.put("secret", secret);
        out.put("message", "Store the signing secret now — used for HMAC verification.");
        return out;
    }

    @Transactional
    public void disableEndpoint(UUID tenantId, UUID actorId, UUID endpointId) {
        int n = jdbc.update("""
                UPDATE webhook_endpoints SET status = 'DISABLED', updated_at = now()
                WHERE id = ? AND tenant_id = ?
                """, endpointId, tenantId);
        if (n == 0) {
            throw new IllegalArgumentException("endpoint not found");
        }
        auditLedgerService.append(
                tenantId, null, AuditEventType.CONFIG_CHANGE, "USER",
                actorId == null ? null : actorId.toString(),
                Map.of("area", "webhooks", "action", "disabled", "endpointId", endpointId.toString())
        );
    }

    @Transactional
    public Map<String, Object> enqueueTest(UUID tenantId, UUID endpointId) {
        return enqueue(tenantId, endpointId, "risk.level_changed", Map.of(
                "test", true,
                "level", "LEVEL_2_SOFT_NUDGE",
                "previousLevel", "LEVEL_1_SILENT",
                "sessionId", "test-session"
        ));
    }

    @Transactional
    public Map<String, Object> enqueue(UUID tenantId, String eventType, Map<String, Object> payload) {
        List<UUID> endpoints = jdbc.query("""
                SELECT id FROM webhook_endpoints
                WHERE tenant_id = ? AND status = 'ACTIVE' AND ? = ANY(events)
                """, (rs, i) -> rs.getObject("id", UUID.class), tenantId, eventType);
        Map<String, Object> last = Map.of();
        for (UUID endpointId : endpoints) {
            last = enqueue(tenantId, endpointId, eventType, payload);
        }
        return Map.of("enqueued", endpoints.size(), "last", last);
    }

    @Transactional
    public Map<String, Object> enqueue(UUID tenantId, UUID endpointId, String eventType, Map<String, Object> payload) {
        UUID id = UUID.randomUUID();
        String json;
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", id.toString());
            body.put("type", eventType);
            body.put("createdAt", Instant.now().toString());
            body.put("data", payload == null ? Map.of() : payload);
            json = objectMapper.writeValueAsString(body);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        jdbc.update("""
                INSERT INTO webhook_deliveries (
                  id, tenant_id, endpoint_id, event_type, payload, status, next_attempt_at
                ) VALUES (?,?,?,?,?::jsonb,'PENDING',now())
                """, id, tenantId, endpointId, eventType, json);
        return Map.of("deliveryId", id.toString(), "status", "PENDING", "eventType", eventType);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listDeliveries(UUID tenantId, UUID endpointId, int limit) {
        int lim = Math.min(Math.max(limit, 1), 100);
        if (endpointId == null) {
            return jdbc.query("""
                    SELECT id, endpoint_id, event_type, status, attempt_count, last_status_code,
                           last_error, delivered_at, created_at, next_attempt_at
                    FROM webhook_deliveries
                    WHERE tenant_id = ?
                    ORDER BY created_at DESC
                    LIMIT ?
                    """, this::mapDelivery, tenantId, lim);
        }
        return jdbc.query("""
                SELECT id, endpoint_id, event_type, status, attempt_count, last_status_code,
                       last_error, delivered_at, created_at, next_attempt_at
                FROM webhook_deliveries
                WHERE tenant_id = ? AND endpoint_id = ?
                ORDER BY created_at DESC
                LIMIT ?
                """, this::mapDelivery, tenantId, endpointId, lim);
    }

    private Map<String, Object> mapDelivery(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getObject("id", UUID.class).toString());
        m.put("endpointId", rs.getObject("endpoint_id", UUID.class).toString());
        m.put("eventType", rs.getString("event_type"));
        m.put("status", rs.getString("status"));
        m.put("attemptCount", rs.getInt("attempt_count"));
        m.put("lastStatusCode", rs.getObject("last_status_code"));
        m.put("lastError", rs.getString("last_error"));
        m.put("deliveredAt", ts(rs.getTimestamp("delivered_at")));
        m.put("createdAt", ts(rs.getTimestamp("created_at")));
        m.put("nextAttemptAt", ts(rs.getTimestamp("next_attempt_at")));
        return m;
    }

    @Scheduled(fixedDelayString = "${sentinelvoice.webhooks.poll-ms:5000}")
    public void pollDeliveries() {
        List<Map<String, Object>> due;
        try {
            due = TenantContext.runAsPlatform(() -> jdbc.queryForList(
                    "SELECT * FROM fn_claim_webhook_deliveries(20)"));
        } catch (Exception ex) {
            return;
        }
        for (Map<String, Object> row : due) {
            try {
                UUID tenantId = (UUID) row.get("tenant_id");
                TenantContext.runAs(tenantId, () -> deliverOne(row));
            } catch (Exception ex) {
                log.warn("webhook_delivery_failed id={} err={}", row.get("delivery_id"), ex.toString());
            }
        }
    }

    private void deliverOne(Map<String, Object> row) {
        UUID deliveryId = (UUID) row.get("delivery_id");
        String url = String.valueOf(row.get("url"));
        String secret = String.valueOf(row.get("secret"));
        Object payloadObj = row.get("payload");
        String body;
        try {
            if (payloadObj instanceof String s) {
                body = s;
            } else {
                body = objectMapper.writeValueAsString(payloadObj);
            }
        } catch (Exception ex) {
            body = "{}";
        }
        long tsSec = Instant.now().getEpochSecond();
        String signature = sign(secret, tsSec, body);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-SentinelVoice-Timestamp", String.valueOf(tsSec));
        headers.set("X-SentinelVoice-Signature", "v1=" + signature);
        headers.set("X-SentinelVoice-Event", String.valueOf(row.get("event_type")));

        int attempt = ((Number) row.get("attempt_count")).intValue() + 1;
        int max = ((Number) row.get("max_attempts")).intValue();
        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            int code = resp.getStatusCode().value();
            if (code >= 200 && code < 300) {
                jdbc.update("""
                        UPDATE webhook_deliveries
                        SET status = 'DELIVERED', attempt_count = ?, last_status_code = ?,
                            delivered_at = now(), last_error = NULL,
                            response_body = LEFT(?, 2000)
                        WHERE id = ?
                        """, attempt, code, resp.getBody(), deliveryId);
            } else {
                scheduleRetry(deliveryId, attempt, max, code, "HTTP " + code);
            }
        } catch (Exception ex) {
            scheduleRetry(deliveryId, attempt, max, null, ex.getMessage());
        }
    }

    private void scheduleRetry(UUID deliveryId, int attempt, int max, Integer code, String error) {
        if (attempt >= max) {
            jdbc.update("""
                    UPDATE webhook_deliveries
                    SET status = 'DEAD', attempt_count = ?, last_status_code = ?, last_error = LEFT(?, 1000)
                    WHERE id = ?
                    """, attempt, code, error, deliveryId);
            return;
        }
        long backoffSec = Math.min(3600, (long) Math.pow(2, Math.min(attempt, 10)));
        jdbc.update("""
                UPDATE webhook_deliveries
                SET status = 'PENDING', attempt_count = ?, last_status_code = ?, last_error = LEFT(?, 1000),
                    next_attempt_at = now() + (? || ' seconds')::interval
                WHERE id = ?
                """, attempt, code, error, String.valueOf(backoffSec), deliveryId);
    }

    public static String sign(String secret, long timestampSec, String body) {
        try {
            String signed = timestampSec + "." + body;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    public static boolean verify(String secret, String timestampHeader, String signatureHeader, String body) {
        if (secret == null || timestampHeader == null || signatureHeader == null) {
            return false;
        }
        long ts;
        try {
            ts = Long.parseLong(timestampHeader.trim());
        } catch (NumberFormatException ex) {
            return false;
        }
        if (Math.abs(Instant.now().getEpochSecond() - ts) > REPLAY_WINDOW_SEC) {
            return false;
        }
        String expected = "v1=" + sign(secret, ts, body == null ? "" : body);
        String provided = signatureHeader.trim();
        return MessageDigestConstantTime.equals(expected, provided);
    }

    private static List<String> normalizeEvents(List<String> events) {
        if (events == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String e : events) {
            if (e != null && EVENT_TYPES.contains(e.trim())) {
                out.add(e.trim());
            }
        }
        return out;
    }

    private static List<String> arrayToList(Array array) throws java.sql.SQLException {
        if (array == null) {
            return List.of();
        }
        Object raw = array.getArray();
        if (raw instanceof String[] strings) {
            return Arrays.asList(strings);
        }
        return List.of();
    }

    private static String ts(Timestamp t) {
        return t == null ? null : t.toInstant().toString();
    }

    /** Constant-time string compare for signatures. */
    private static final class MessageDigestConstantTime {
        static boolean equals(String a, String b) {
            if (a == null || b == null || a.length() != b.length()) {
                return false;
            }
            int r = 0;
            for (int i = 0; i < a.length(); i++) {
                r |= a.charAt(i) ^ b.charAt(i);
            }
            return r == 0;
        }
    }
}
