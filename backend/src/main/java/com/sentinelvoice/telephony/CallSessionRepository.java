package com.sentinelvoice.telephony;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
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
                       callee.full_name AS callee_name, callee.job_title AS callee_title
                FROM call_sessions cs
                LEFT JOIN employees caller ON caller.id = cs.caller_employee_id
                LEFT JOIN employees callee ON callee.id = cs.callee_employee_id
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
                rs.getString("direction"),
                rs.getObject("peak_score") == null ? null : rs.getDouble("peak_score"),
                rs.getString("peak_level"),
                rs.getString("final_outcome"),
                rs.getString("sip_call_id"),
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
