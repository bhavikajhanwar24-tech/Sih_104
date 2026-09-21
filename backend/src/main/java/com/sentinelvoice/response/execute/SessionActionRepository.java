package com.sentinelvoice.response.execute;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class SessionActionRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public SessionActionRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /**
     * Insert PENDING row; returns empty if the idempotency key already exists.
     */
    public Optional<UUID> tryInsertPending(
            UUID tenantId,
            String sessionId,
            String level,
            int stepIndex,
            String action,
            String entryToken
    ) {
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO session_actions (
                      id, tenant_id, session_id, level, step_index, action, status, entry_token, started_at
                    ) VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, now())
                    """,
                    id, tenantId, sessionId, level, stepIndex, action, entryToken
            );
            return Optional.of(id);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return Optional.empty();
        } catch (Exception e) {
            // Unique violation may surface as DataIntegrityViolationException
            if (e.getMessage() != null && e.getMessage().contains("session_actions_idempotent")) {
                return Optional.empty();
            }
            throw e;
        }
    }

    public void finish(UUID id, String status, Map<String, Object> result) {
        jdbc.update("""
                UPDATE session_actions
                SET status = ?, finished_at = now(), result = ?::jsonb
                WHERE id = ?
                """, status, toJson(result), id);
    }

    public List<Map<String, Object>> listForSession(UUID tenantId, String sessionId) {
        return jdbc.query(
                """
                SELECT id, level, step_index, action, status, started_at, finished_at, result::text AS result, entry_token
                FROM session_actions
                WHERE tenant_id = ? AND session_id = ?
                ORDER BY created_at ASC
                """,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getObject("id", UUID.class).toString());
                    m.put("level", rs.getString("level"));
                    m.put("stepIndex", rs.getInt("step_index"));
                    m.put("action", rs.getString("action"));
                    m.put("status", rs.getString("status"));
                    Timestamp s = rs.getTimestamp("started_at");
                    Timestamp f = rs.getTimestamp("finished_at");
                    m.put("startedAt", s == null ? null : s.toInstant().toString());
                    m.put("finishedAt", f == null ? null : f.toInstant().toString());
                    m.put("result", parse(rs.getString("result")));
                    m.put("entryToken", rs.getString("entry_token"));
                    return m;
                },
                tenantId, sessionId
        );
    }

    public void markOverridden(UUID tenantId, String sessionId, UUID actionId, String reason, String actorId) {
        jdbc.update("""
                UPDATE session_actions
                SET status = 'OVERRIDDEN',
                    finished_at = now(),
                    result = COALESCE(result, '{}'::jsonb) || ?::jsonb
                WHERE tenant_id = ? AND session_id = ? AND id = ?
                """,
                toJson(Map.of(
                        "overrideReason", reason == null ? "" : reason,
                        "overriddenBy", actorId == null ? "" : actorId,
                        "overriddenAt", Instant.now().toString()
                )),
                tenantId, sessionId, actionId
        );
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o == null ? Map.of() : o);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, Object> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return mapper.readValue(raw, new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }
}
