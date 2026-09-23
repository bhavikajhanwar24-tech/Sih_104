package com.sentinelvoice.governance;

import com.sentinelvoice.security.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * F14 — unified configuration change timeline from audit_blocks.
 */
@Service
public class ChangeHistoryService {

    private static final Set<String> CONFIG_EVENTS = Set.of(
            "TENANT_SETTINGS_UPDATED",
            "POLICY_SET_SUBMITTED", "POLICY_SET_APPROVED", "POLICY_SET_REJECTED",
            "POLICY_SET_DELETED", "POLICY_RULE_UPDATED", "POLICY_RULE_CREATED",
            "POLICY_RULE_DELETED", "POLICY_RULES_JSON_REPLACED",
            "FUSION_CONFIG_DRAFT_CREATED", "FUSION_CONFIG_SUBMITTED",
            "FUSION_CONFIG_APPROVED", "FUSION_CONFIG_REJECTED",
            "RESPONSE_PLAN_DRAFT_CREATED", "RESPONSE_PLAN_SUBMITTED",
            "RESPONSE_PLAN_APPROVED", "RESPONSE_PLAN_REJECTED",
            "EMERGENCY_MODE_ENABLED", "EMERGENCY_MODE_DISABLED", "EMERGENCY_MODE_EXPIRED",
            "CONFIG_CHANGE", "USER_UPDATED", "DIRECTORY_UPDATED"
    );

    private final JdbcTemplate jdbc;

    public ChangeHistoryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> list(String area, int limit) {
        UUID tenantId = TenantContext.require().tenantId();
        int lim = Math.max(1, Math.min(limit, 200));
        List<Map<String, Object>> items = jdbc.query(
                """
                SELECT seq, event_type, actor_type, actor_id, payload, prev_hash, hash, created_at
                FROM audit_blocks
                WHERE tenant_id = ?
                  AND (
                    event_type IN (
                      'TENANT_SETTINGS_UPDATED','POLICY_SET_SUBMITTED','POLICY_SET_APPROVED',
                      'POLICY_SET_REJECTED','POLICY_SET_DELETED','POLICY_RULE_UPDATED',
                      'POLICY_RULE_CREATED','POLICY_RULE_DELETED','POLICY_RULES_JSON_REPLACED',
                      'FUSION_CONFIG_DRAFT_CREATED','FUSION_CONFIG_SUBMITTED',
                      'FUSION_CONFIG_APPROVED','FUSION_CONFIG_REJECTED',
                      'RESPONSE_PLAN_DRAFT_CREATED','RESPONSE_PLAN_SUBMITTED',
                      'RESPONSE_PLAN_APPROVED','RESPONSE_PLAN_REJECTED',
                      'EMERGENCY_MODE_ENABLED','EMERGENCY_MODE_DISABLED','EMERGENCY_MODE_EXPIRED',
                      'MODE_CHANGED','CONFIG_CHANGE','USER_UPDATED','DIRECTORY_UPDATED'
                    )
                    OR jsonb_exists(payload, 'change')
                  )
                ORDER BY seq DESC
                LIMIT ?
                """,
                (rs, i) -> mapRow(rs.getLong("seq"), rs.getString("event_type"),
                        rs.getString("actor_type"), rs.getString("actor_id"),
                        rs.getObject("payload"), rs.getString("prev_hash"),
                        rs.getString("hash"), rs.getTimestamp("created_at").toInstant()),
                tenantId,
                lim
        );
        if (area != null && !area.isBlank()) {
            String a = area.trim().toLowerCase();
            items = items.stream().filter(row -> {
                String ev = String.valueOf(row.get("eventType")).toLowerCase();
                String ar = String.valueOf(row.get("area")).toLowerCase();
                return ev.contains(a) || ar.contains(a);
            }).toList();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", "2");
        body.put("count", items.size());
        body.put("items", items);
        return body;
    }

    public byte[] exportCsv(String area) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) list(area, 500).get("items");
        StringBuilder sb = new StringBuilder();
        sb.append("seq,when,eventType,area,actorType,actorId,hash,summary\n");
        for (Map<String, Object> row : items) {
            sb.append(csv(row.get("seq"))).append(',')
                    .append(csv(row.get("createdAt"))).append(',')
                    .append(csv(row.get("eventType"))).append(',')
                    .append(csv(row.get("area"))).append(',')
                    .append(csv(row.get("actorType"))).append(',')
                    .append(csv(row.get("actorId"))).append(',')
                    .append(csv(row.get("blockHash"))).append(',')
                    .append(csv(row.get("summary"))).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapRow(
            long seq, String eventType, String actorType, String actorId,
            Object payloadObj, String prevHash, String blockHash, Instant createdAt
    ) {
        Map<String, Object> payload = payloadObj instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : Map.of();
        Object changeObj = payload.get("change");
        Map<String, Object> change = changeObj instanceof Map<?, ?> cm
                ? (Map<String, Object>) cm
                : null;
        String area = change != null && change.get("area") != null
                ? String.valueOf(change.get("area"))
                : areaFromEvent(eventType);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("seq", seq);
        row.put("eventType", eventType);
        row.put("actorType", actorType);
        row.put("actorId", actorId);
        row.put("area", area);
        row.put("createdAt", createdAt.toString());
        row.put("prevHash", prevHash);
        row.put("blockHash", blockHash);
        row.put("change", change);
        row.put("before", change == null ? null : change.get("before"));
        row.put("after", change == null ? null : change.get("after"));
        row.put("summary", change != null && change.get("summary") != null
                ? change.get("summary")
                : eventType);
        row.put("payload", payload);
        return row;
    }

    private static String areaFromEvent(String eventType) {
        if (eventType == null) return "other";
        if (eventType.startsWith("POLICY_")) return "policy";
        if (eventType.startsWith("FUSION_")) return "fusion";
        if (eventType.startsWith("RESPONSE_")) return "response";
        if (eventType.startsWith("EMERGENCY_")) return "emergency";
        if (eventType.startsWith("TENANT_SETTINGS")) return "settings";
        if (eventType.startsWith("DIRECTORY_")) return "directory";
        if (eventType.startsWith("USER_")) return "users";
        return "other";
    }

    private static String csv(Object v) {
        if (v == null) return "";
        String s = String.valueOf(v).replace("\"", "\"\"");
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s + "\"";
        }
        return s;
    }
}
