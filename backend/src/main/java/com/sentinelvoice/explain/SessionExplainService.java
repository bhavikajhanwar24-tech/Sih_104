package com.sentinelvoice.explain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelvoice.fusion.ReasonCode;
import com.sentinelvoice.fusion.ReasonTemplates;
import com.sentinelvoice.response.execute.SessionActionRepository;
import com.sentinelvoice.telephony.CallSessionRepository;
import com.sentinelvoice.telephony.TelephonyModels;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * F12 — read-side explainability payloads for SessionsController.
 */
@Service
public class SessionExplainService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final CallSessionRepository callSessionRepository;
    private final SessionActionRepository sessionActionRepository;
    private final ReasonTemplates reasonTemplates;

    public SessionExplainService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            CallSessionRepository callSessionRepository,
            SessionActionRepository sessionActionRepository,
            ReasonTemplates reasonTemplates
    ) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.callSessionRepository = callSessionRepository;
        this.sessionActionRepository = sessionActionRepository;
        this.reasonTemplates = reasonTemplates;
    }

    public TelephonyModels.CallSessionDetail requireSession(UUID tenantId, String id) {
        return callSessionRepository.findDetailByIdOrSvSession(tenantId, id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found"));
    }

    public Map<String, Object> explainPayload(UUID tenantId, String id) {
        return explainPayload(tenantId, id, Locale.ENGLISH);
    }

    public Map<String, Object> explainPayload(UUID tenantId, String id, Locale locale) {
        TelephonyModels.CallSessionDetail cs = requireSession(tenantId, id);
        UUID sv = cs.svSessionUuid();
        Locale loc = locale == null ? Locale.ENGLISH : locale;
        List<Map<String, Object>> ticks = listTicks(tenantId, sv);
        List<Map<String, Object>> reasons = listReasons(tenantId, sv, loc);
        List<Map<String, Object>> actions = sessionActionRepository.listForSession(tenantId, sv.toString());
        Map<String, Object> extraction = latestExtraction(tenantId, sv);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("id", cs.id().toString());
        meta.put("svSessionUuid", sv.toString());
        meta.put("tenantId", cs.tenantId().toString());
        meta.put("startedAt", cs.startedAt() == null ? null : cs.startedAt().toString());
        meta.put("endedAt", cs.endedAt() == null ? null : cs.endedAt().toString());
        meta.put("durationMs", cs.durationMs());
        meta.put("direction", cs.direction());
        meta.put("callerNumber", cs.callerNumber());
        meta.put("calleeNumber", cs.calleeNumber());
        meta.put("callerEmployeeId", cs.callerEmployeeId() == null ? null : cs.callerEmployeeId().toString());
        meta.put("calleeEmployeeId", cs.calleeEmployeeId() == null ? null : cs.calleeEmployeeId().toString());
        meta.put("callerName", displayName(cs.callerName(), cs.callerTitle(), cs.callerNumber()));
        meta.put("calleeName", displayName(cs.calleeName(), cs.calleeTitle(), cs.calleeNumber()));
        meta.put("peakScore", cs.peakScore());
        meta.put("peakLevel", cs.peakLevel());
        meta.put("finalOutcome", cs.finalOutcome());
        meta.put("reviewStatus", cs.reviewStatus() == null ? "UNREVIEWED" : cs.reviewStatus());
        meta.put("reviewedAt", cs.reviewedAt() == null ? null : cs.reviewedAt().toString());
        meta.put("active", cs.active());

        Map<String, Object> versions = new LinkedHashMap<>();
        versions.put("policyVersion", cs.snapshotPolicyVersion());
        versions.put("fusionVersion", cs.snapshotFusionVersion());
        versions.put("responsePlanVersion", cs.snapshotResponsePlanVersion());

        Map<String, Object> ticksSummary = new LinkedHashMap<>();
        ticksSummary.put("count", ticks.size());
        ticksSummary.put("firstTMs", ticks.isEmpty() ? null : ticks.getFirst().get("tMs"));
        ticksSummary.put("lastTMs", ticks.isEmpty() ? null : ticks.getLast().get("tMs"));
        ticksSummary.put("latest", ticks.isEmpty() ? null : ticks.getLast());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", "2");
        body.put("tenantId", tenantId.toString());
        body.put("metadata", meta);
        body.put("ticksSummary", ticksSummary);
        body.put("ticks", ticks);
        body.put("reasons", reasons);
        body.put("actions", actions);
        body.put("extractionsLatest", extraction);
        body.put("versions", versions);
        body.put("locale", loc.getLanguage());
        body.put("availability", Map.of(
                "ticks", ticks.size(),
                "reasons", reasons.size(),
                "actions", actions.size(),
                "actionsNote", actions.isEmpty()
                        ? "No response-plan steps recorded for this session (F9 may not have actuated)."
                        : "Response-plan steps from session_actions.",
                "extractionsNote", extraction.isEmpty()
                        ? "No linguistic extraction enums retained (F11 media path may not have run)."
                        : "Latest ask/category enums only.",
                "ticksNote", ticks.isEmpty()
                        ? "No sampled ticks — FeatureFrame ingest did not persist explain rows for this call."
                        : "Sampled from session_ticks."
        ));
        return body;
    }

    public List<Map<String, Object>> listTicks(UUID tenantId, UUID sessionId) {
        return jdbc.query(
                """
                SELECT t_ms, score, level, family_scores::text AS family_scores,
                       missing_families, fired_rule_ids, llm_state
                FROM session_ticks
                WHERE tenant_id = ? AND session_id = ?
                ORDER BY t_ms ASC
                """,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("tMs", rs.getLong("t_ms"));
                    m.put("score", rs.getDouble("score"));
                    m.put("level", rs.getString("level"));
                    m.put("familyScores", parseMap(rs.getString("family_scores")));
                    m.put("missingFamilies", toStringList(rs.getArray("missing_families")));
                    m.put("firedRuleIds", toStringList(rs.getArray("fired_rule_ids")));
                    m.put("llmState", rs.getString("llm_state"));
                    return m;
                },
                tenantId, sessionId
        );
    }

    public List<Map<String, Object>> listReasons(UUID tenantId, UUID sessionId) {
        return listReasons(tenantId, sessionId, Locale.ENGLISH);
    }

    public List<Map<String, Object>> listReasons(UUID tenantId, UUID sessionId, Locale locale) {
        Locale loc = locale == null ? Locale.ENGLISH : locale;
        return jdbc.query(
                """
                SELECT seq, t_ms, code, title, detail, contribution, family, severity,
                       evidence::text AS evidence, rule_id, policy_version, source_clause::text AS source_clause
                FROM session_reasons
                WHERE tenant_id = ? AND session_id = ?
                ORDER BY seq ASC
                """,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    String code = rs.getString("code");
                    m.put("seq", rs.getInt("seq"));
                    m.put("tMs", rs.getLong("t_ms"));
                    m.put("code", code);
                    String storedTitle = rs.getString("title");
                    String localizedTitle = localizeTitle(code, loc, storedTitle);
                    m.put("title", localizedTitle);
                    m.put("detail", rs.getString("detail"));
                    m.put("contribution", rs.getObject("contribution") == null ? null : rs.getDouble("contribution"));
                    m.put("family", rs.getString("family"));
                    m.put("severity", rs.getString("severity"));
                    m.put("evidence", parseMap(rs.getString("evidence")));
                    m.put("ruleId", rs.getString("rule_id"));
                    m.put("policyVersion", rs.getObject("policy_version") == null ? null : rs.getInt("policy_version"));
                    m.put("sourceClause", parseMap(rs.getString("source_clause")));
                    return m;
                },
                tenantId, sessionId
        );
    }

    private String localizeTitle(String code, Locale locale, String fallback) {
        if (code == null || code.isBlank()) {
            return fallback;
        }
        try {
            ReasonCode rc = ReasonCode.valueOf(code.trim()).canonical();
            return reasonTemplates.title(rc, locale);
        } catch (Exception e) {
            return fallback == null ? code : fallback;
        }
    }

    private Map<String, Object> latestExtraction(UUID tenantId, UUID sessionId) {
        List<Map<String, Object>> rows = jdbc.query(
                """
                SELECT t_ms, ask::text AS ask, categories::text AS categories
                FROM session_extractions
                WHERE tenant_id = ? AND session_id = ?
                ORDER BY t_ms DESC
                LIMIT 1
                """,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("tMs", rs.getLong("t_ms"));
                    m.put("ask", parseMap(rs.getString("ask")));
                    m.put("categories", parseMap(rs.getString("categories")));
                    return m;
                },
                tenantId, sessionId
        );
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    private Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static List<String> toStringList(java.sql.Array array) {
        if (array == null) {
            return List.of();
        }
        try {
            Object raw = array.getArray();
            if (raw instanceof String[] s) {
                return List.of(s);
            }
            if (raw instanceof Object[] objs) {
                List<String> out = new ArrayList<>();
                for (Object o : objs) {
                    if (o != null) {
                        out.add(String.valueOf(o));
                    }
                }
                return out;
            }
        } catch (Exception ignored) {
            // empty
        }
        return List.of();
    }

    private static String displayName(String fullName, String title, String fallbackNumber) {
        if (fullName != null && !fullName.isBlank()) {
            if (title != null && !title.isBlank()) {
                return fullName + " (" + title + ")";
            }
            return fullName;
        }
        return fallbackNumber == null || fallbackNumber.isBlank() ? "Unknown" : fallbackNumber;
    }
}
