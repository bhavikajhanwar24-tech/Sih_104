package com.sentinelvoice.telephony;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class CallSessionRepository {

    private final JdbcTemplate jdbc;

    public CallSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public TelephonyModels.CallSessionView insert(
            UUID tenantId,
            UUID svSessionUuid,
            String callerNumber,
            String calleeNumber,
            UUID callerEmployeeId,
            UUID calleeEmployeeId,
            String direction,
            UUID trunkId,
            Integer policyVersion,
            Integer fusionVersion,
            Integer responsePlanVersion,
            String sipCallId
    ) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO call_sessions (
                  id, tenant_id, started_at, caller_number, callee_number,
                  caller_employee_id, callee_employee_id, direction, trunk_id,
                  snapshot_policy_version, snapshot_fusion_version, snapshot_response_plan_version,
                  sip_call_id, sv_session_uuid
                ) VALUES (?, ?, now(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, tenantId, callerNumber, calleeNumber,
                callerEmployeeId, calleeEmployeeId, direction, trunkId,
                policyVersion, fusionVersion, responsePlanVersion,
                sipCallId, svSessionUuid
        );
        return findBySvSession(tenantId, svSessionUuid).orElseThrow();
    }

    public Optional<TelephonyModels.CallSessionView> findBySvSession(UUID tenantId, UUID svSessionUuid) {
        List<TelephonyModels.CallSessionView> rows = jdbc.query(
                """
                SELECT * FROM call_sessions
                WHERE tenant_id = ? AND sv_session_uuid = ?
                """,
                (rs, i) -> mapRow(rs),
                tenantId, svSessionUuid
        );
        return rows.stream().findFirst();
    }

    public Optional<TelephonyModels.CallSessionView> findBySvSessionAnyTenant(UUID svSessionUuid) {
        // Used at end-of-call when we already know the session UUID from the wire.
        List<TelephonyModels.CallSessionView> rows = jdbc.query(
                "SELECT * FROM call_sessions WHERE sv_session_uuid = ?",
                (rs, i) -> mapRow(rs),
                svSessionUuid
        );
        return rows.stream().findFirst();
    }

    public void finalizeSession(
            UUID tenantId,
            UUID svSessionUuid,
            Double peakScore,
            String peakLevel,
            String finalOutcome
    ) {
        jdbc.update(
                """
                UPDATE call_sessions
                SET ended_at = now(),
                    peak_score = ?,
                    peak_level = ?,
                    final_outcome = ?
                WHERE tenant_id = ? AND sv_session_uuid = ? AND ended_at IS NULL
                """,
                peakScore, peakLevel, finalOutcome, tenantId, svSessionUuid
        );
    }

    public void updatePeakLive(
            UUID tenantId,
            UUID svSessionUuid,
            Double peakScore,
            String peakLevel
    ) {
        jdbc.update(
                """
                UPDATE call_sessions
                SET peak_score = GREATEST(COALESCE(peak_score, 0), COALESCE(?, 0)),
                    peak_level = ?
                WHERE tenant_id = ? AND sv_session_uuid = ? AND ended_at IS NULL
                """,
                peakScore, peakLevel, tenantId, svSessionUuid
        );
    }

    /**
     * Analyst review disposition (F12) — FALSE_POSITIVE | CONFIRMED_FRAUD | UNREVIEWED.
     */
    public int updateReviewStatus(
            UUID tenantId,
            UUID callSessionId,
            String reviewStatus,
            UUID reviewedBy
    ) {
        if (reviewStatus == null || reviewStatus.isBlank()) {
            throw new IllegalArgumentException("reviewStatus is required");
        }
        String status = reviewStatus.trim().toUpperCase();
        if (!status.equals("UNREVIEWED")
                && !status.equals("FALSE_POSITIVE")
                && !status.equals("CONFIRMED_FRAUD")) {
            throw new IllegalArgumentException("invalid reviewStatus: " + reviewStatus);
        }
        if ("UNREVIEWED".equals(status)) {
            return jdbc.update(
                    """
                    UPDATE call_sessions
                    SET review_status = 'UNREVIEWED', reviewed_at = NULL, reviewed_by = NULL
                    WHERE tenant_id = ? AND id = ?
                    """,
                    tenantId, callSessionId
            );
        }
        return jdbc.update(
                """
                UPDATE call_sessions
                SET review_status = ?, reviewed_at = now(), reviewed_by = ?
                WHERE tenant_id = ? AND id = ?
                """,
                status, reviewedBy, tenantId, callSessionId
        );
    }

    /**
     * AGI hangup path — SECURITY DEFINER so finalize works without JWT tenant GUC.
     */
    public FinalizeResult finalizeSessionDefiner(
            UUID svSessionUuid,
            Double peakScore,
            String peakLevel,
            String finalOutcome
    ) {
        List<FinalizeResult> rows = jdbc.query(
                """
                SELECT finalized, tenant_id, caller_employee_id, callee_employee_id
                FROM fn_telephony_finalize_session(?, ?, ?, ?)
                """,
                (rs, i) -> new FinalizeResult(
                        rs.getBoolean("finalized"),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("caller_employee_id", UUID.class),
                        rs.getObject("callee_employee_id", UUID.class)
                ),
                svSessionUuid,
                peakScore,
                peakLevel,
                finalOutcome == null || finalOutcome.isBlank() ? "ENDED" : finalOutcome
        );
        return rows.isEmpty()
                ? new FinalizeResult(false, null, null, null)
                : rows.getFirst();
    }

    public record FinalizeResult(
            boolean finalized,
            UUID tenantId,
            UUID callerEmployeeId,
            UUID calleeEmployeeId
    ) {
    }

    /** Employee IDs currently on an open call (caller or callee). */
    public java.util.Set<UUID> activeEmployeeIds(UUID tenantId) {
        List<UUID> ids = jdbc.query(
                """
                SELECT DISTINCT emp_id FROM (
                  SELECT caller_employee_id AS emp_id FROM call_sessions
                    WHERE tenant_id = ? AND ended_at IS NULL AND caller_employee_id IS NOT NULL
                  UNION
                  SELECT callee_employee_id FROM call_sessions
                    WHERE tenant_id = ? AND ended_at IS NULL AND callee_employee_id IS NOT NULL
                ) t
                """,
                (rs, i) -> rs.getObject("emp_id", UUID.class),
                tenantId, tenantId
        );
        return new java.util.HashSet<>(ids);
    }

    public int countActive(UUID tenantId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM call_sessions WHERE tenant_id = ? AND ended_at IS NULL",
                Integer.class,
                tenantId
        );
        return n == null ? 0 : n;
    }

    /**
     * Recent call metadata with directory names for Live Calls (F10).
     */
    public List<TelephonyModels.CallSessionListItem> listRecent(UUID tenantId, int limit, boolean activeOnly) {
        int lim = Math.max(1, Math.min(limit, 200));
        String activeClause = activeOnly ? "AND cs.ended_at IS NULL" : "";
        return jdbc.query(
                """
                SELECT cs.id, cs.tenant_id, cs.started_at, cs.ended_at,
                       cs.caller_number, cs.callee_number,
                       cs.caller_employee_id, cs.callee_employee_id,
                       cs.direction, cs.peak_score, cs.peak_level, cs.final_outcome,
                       cs.sip_call_id, cs.sv_session_uuid,
                       caller.full_name AS caller_name, caller.job_title AS caller_title,
                       callee.full_name AS callee_name, callee.job_title AS callee_title,
                       caller_dept.name AS caller_department,
                       callee_dept.name AS callee_department
                FROM call_sessions cs
                LEFT JOIN employees caller ON caller.id = cs.caller_employee_id
                LEFT JOIN employees callee ON callee.id = cs.callee_employee_id
                LEFT JOIN departments caller_dept ON caller_dept.id = caller.department_id
                LEFT JOIN departments callee_dept ON callee_dept.id = callee.department_id
                WHERE cs.tenant_id = ?
                %s
                ORDER BY cs.started_at DESC
                LIMIT ?
                """.formatted(activeClause),
                (rs, i) -> mapListItem(rs),
                tenantId,
                lim
        );
    }

    /**
     * Resolve by {@code call_sessions.id} or {@code sv_session_uuid} for the tenant.
     */
    public Optional<TelephonyModels.CallSessionDetail> findDetailByIdOrSvSession(UUID tenantId, String key) {
        UUID parsed = parseUuid(key);
        if (parsed == null) {
            return Optional.empty();
        }
        List<TelephonyModels.CallSessionDetail> rows = jdbc.query(
                """
                SELECT cs.id, cs.tenant_id, cs.started_at, cs.ended_at,
                       cs.caller_number, cs.callee_number,
                       cs.caller_employee_id, cs.callee_employee_id,
                       cs.direction, cs.peak_score, cs.peak_level, cs.final_outcome,
                       cs.sip_call_id, cs.sv_session_uuid,
                       cs.snapshot_policy_version, cs.snapshot_fusion_version,
                       cs.snapshot_response_plan_version,
                       cs.review_status, cs.reviewed_at, cs.reviewed_by,
                       caller.full_name AS caller_name, caller.job_title AS caller_title,
                       callee.full_name AS callee_name, callee.job_title AS callee_title
                FROM call_sessions cs
                LEFT JOIN employees caller ON caller.id = cs.caller_employee_id
                LEFT JOIN employees callee ON callee.id = cs.callee_employee_id
                WHERE cs.tenant_id = ? AND (cs.id = ? OR cs.sv_session_uuid = ?)
                LIMIT 1
                """,
                (rs, i) -> mapDetail(rs),
                tenantId, parsed, parsed
        );
        return rows.stream().findFirst();
    }

    /**
     * F12 Call History — filtered list with directory names + review status.
     */
    public List<TelephonyModels.CallSessionHistoryItem> listFiltered(
            UUID tenantId,
            Instant from,
            Instant to,
            String minLevel,
            UUID employeeId,
            String outcome,
            Boolean reviewed,
            int limit,
            int offset
    ) {
        int lim = Math.max(1, Math.min(limit, 200));
        int off = Math.max(0, offset);
        StringBuilder sql = new StringBuilder("""
                SELECT cs.id, cs.tenant_id, cs.started_at, cs.ended_at,
                       cs.caller_number, cs.callee_number,
                       cs.caller_employee_id, cs.callee_employee_id,
                       cs.direction, cs.peak_score, cs.peak_level, cs.final_outcome,
                       cs.review_status, cs.sv_session_uuid,
                       caller.full_name AS caller_name, caller.job_title AS caller_title,
                       callee.full_name AS callee_name, callee.job_title AS callee_title
                FROM call_sessions cs
                LEFT JOIN employees caller ON caller.id = cs.caller_employee_id
                LEFT JOIN employees callee ON callee.id = cs.callee_employee_id
                WHERE cs.tenant_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (from != null) {
            sql.append(" AND cs.started_at >= ?");
            args.add(Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" AND cs.started_at <= ?");
            args.add(Timestamp.from(to));
        }
        if (minLevel != null && !minLevel.isBlank()) {
            sql.append("""
                     AND CASE cs.peak_level
                       WHEN 'LEVEL_1_SILENT' THEN 1
                       WHEN 'LEVEL_2_SOFT_NUDGE' THEN 2
                       WHEN 'LEVEL_3_STEP_UP_MFA' THEN 3
                       WHEN 'LEVEL_4_AUTO_HOLD' THEN 4
                       WHEN 'LEVEL_5_TERMINATE' THEN 5
                       ELSE 0 END >= CASE ?
                       WHEN 'LEVEL_1_SILENT' THEN 1
                       WHEN 'LEVEL_2_SOFT_NUDGE' THEN 2
                       WHEN 'LEVEL_3_STEP_UP_MFA' THEN 3
                       WHEN 'LEVEL_4_AUTO_HOLD' THEN 4
                       WHEN 'LEVEL_5_TERMINATE' THEN 5
                       ELSE 0 END
                    """);
            args.add(minLevel.trim());
        }
        if (employeeId != null) {
            sql.append(" AND (cs.caller_employee_id = ? OR cs.callee_employee_id = ?)");
            args.add(employeeId);
            args.add(employeeId);
        }
        if (outcome != null && !outcome.isBlank()) {
            sql.append(" AND cs.final_outcome = ?");
            args.add(outcome.trim());
        }
        if (reviewed != null) {
            if (reviewed) {
                sql.append(" AND cs.review_status IS NOT NULL AND cs.review_status <> 'UNREVIEWED'");
            } else {
                sql.append(" AND (cs.review_status IS NULL OR cs.review_status = 'UNREVIEWED')");
            }
        }
        sql.append(" ORDER BY cs.started_at DESC LIMIT ? OFFSET ?");
        args.add(lim);
        args.add(off);
        return jdbc.query(sql.toString(), (rs, i) -> mapHistoryItem(rs), args.toArray());
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static TelephonyModels.CallSessionListItem mapListItem(ResultSet rs) throws SQLException {
        return new TelephonyModels.CallSessionListItem(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                toInstant(rs.getTimestamp("started_at")),
                toInstant(rs.getTimestamp("ended_at")),
                rs.getString("caller_number"),
                rs.getString("callee_number"),
                rs.getObject("caller_employee_id", UUID.class),
                rs.getObject("callee_employee_id", UUID.class),
                rs.getString("caller_name"),
                rs.getString("caller_title"),
                rs.getString("callee_name"),
                rs.getString("callee_title"),
                rs.getString("caller_department"),
                rs.getString("callee_department"),
                rs.getString("direction"),
                rs.getObject("peak_score") == null ? null : rs.getDouble("peak_score"),
                rs.getString("peak_level"),
                rs.getString("final_outcome"),
                rs.getString("sip_call_id"),
                rs.getObject("sv_session_uuid", UUID.class)
        );
    }

    private static TelephonyModels.CallSessionDetail mapDetail(ResultSet rs) throws SQLException {
        return new TelephonyModels.CallSessionDetail(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                toInstant(rs.getTimestamp("started_at")),
                toInstant(rs.getTimestamp("ended_at")),
                rs.getString("caller_number"),
                rs.getString("callee_number"),
                rs.getObject("caller_employee_id", UUID.class),
                rs.getObject("callee_employee_id", UUID.class),
                rs.getString("caller_name"),
                rs.getString("caller_title"),
                rs.getString("callee_name"),
                rs.getString("callee_title"),
                rs.getString("direction"),
                rs.getObject("peak_score") == null ? null : rs.getDouble("peak_score"),
                rs.getString("peak_level"),
                rs.getString("final_outcome"),
                rs.getString("sip_call_id"),
                rs.getObject("sv_session_uuid", UUID.class),
                (Integer) rs.getObject("snapshot_policy_version"),
                (Integer) rs.getObject("snapshot_fusion_version"),
                (Integer) rs.getObject("snapshot_response_plan_version"),
                rs.getString("review_status"),
                toInstant(rs.getTimestamp("reviewed_at")),
                rs.getObject("reviewed_by", UUID.class)
        );
    }

    private static TelephonyModels.CallSessionHistoryItem mapHistoryItem(ResultSet rs) throws SQLException {
        Instant started = toInstant(rs.getTimestamp("started_at"));
        Instant ended = toInstant(rs.getTimestamp("ended_at"));
        long durationMs = 0L;
        if (started != null) {
            Instant end = ended == null ? Instant.now() : ended;
            durationMs = Math.max(0L, end.toEpochMilli() - started.toEpochMilli());
        }
        return new TelephonyModels.CallSessionHistoryItem(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                started,
                ended,
                durationMs,
                rs.getString("caller_number"),
                rs.getString("callee_number"),
                rs.getObject("caller_employee_id", UUID.class),
                rs.getObject("callee_employee_id", UUID.class),
                rs.getString("caller_name"),
                rs.getString("caller_title"),
                rs.getString("callee_name"),
                rs.getString("callee_title"),
                rs.getString("direction"),
                rs.getObject("peak_score") == null ? null : rs.getDouble("peak_score"),
                rs.getString("peak_level"),
                rs.getString("final_outcome"),
                rs.getString("review_status"),
                rs.getObject("sv_session_uuid", UUID.class)
        );
    }

    private static TelephonyModels.CallSessionView mapRow(ResultSet rs) throws SQLException {
        return new TelephonyModels.CallSessionView(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                toInstant(rs.getTimestamp("started_at")),
                toInstant(rs.getTimestamp("ended_at")),
                rs.getString("caller_number"),
                rs.getString("callee_number"),
                rs.getObject("caller_employee_id", UUID.class),
                rs.getObject("callee_employee_id", UUID.class),
                rs.getString("direction"),
                rs.getObject("trunk_id", UUID.class),
                (Integer) rs.getObject("snapshot_policy_version"),
                (Integer) rs.getObject("snapshot_fusion_version"),
                (Integer) rs.getObject("snapshot_response_plan_version"),
                rs.getObject("peak_score") == null ? null : rs.getDouble("peak_score"),
                rs.getString("peak_level"),
                rs.getString("final_outcome"),
                rs.getString("sip_call_id"),
                rs.getObject("sv_session_uuid", UUID.class)
        );
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
