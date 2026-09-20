package com.sentinelvoice.policy.engine;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Produces cross-channel facts from {@code cross_channel_events} (F7).
 * Replaces v1 CrossChannelScoring YAML boosts with fact-driven rules.
 */
@Service
public class CrossChannelFactService {

    public record CrossFacts(boolean recentEmailFromSameIdentity, boolean recentSmsLinkClicked) {
        public static CrossFacts none() {
            return new CrossFacts(false, false);
        }
    }

    private final JdbcTemplate jdbc;
    private final PolicyEngineProperties props;

    public CrossChannelFactService(JdbcTemplate jdbc, PolicyEngineProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    public CrossFacts factsFor(UUID tenantId, String targetEmployeeId) {
        if (tenantId == null || targetEmployeeId == null || targetEmployeeId.isBlank()) {
            return CrossFacts.none();
        }
        Instant since = Instant.now().minus(props.crossChannelWindowHours(), ChronoUnit.HOURS);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT channel, indicator
                FROM cross_channel_events
                WHERE tenant_id = ?
                  AND target_employee_id = ?
                  AND occurred_at >= ?
                """, tenantId, targetEmployeeId, Timestamp.from(since));

        boolean email = false;
        boolean smsLink = false;
        for (Map<String, Object> row : rows) {
            String channel = String.valueOf(row.get("channel")).toUpperCase(Locale.ROOT);
            String indicator = String.valueOf(row.getOrDefault("indicator", "")).toLowerCase(Locale.ROOT);
            if ("EMAIL".equals(channel)) {
                email = true;
            }
            if ("SMS".equals(channel) && (indicator.contains("link") || indicator.contains("click"))) {
                smsLink = true;
            }
        }
        return new CrossFacts(email, smsLink);
    }

    public UUID insert(
            UUID tenantId,
            String channel,
            String targetEmployeeId,
            Instant occurredAt,
            String severity,
            String indicator,
            String campaignId,
            String description
    ) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO cross_channel_events (
                  id, tenant_id, channel, target_employee_id, occurred_at,
                  severity, indicator, campaign_id, description
                ) VALUES (?,?,?,?,?,?,?,?,?)
                """,
                id,
                tenantId,
                channel,
                targetEmployeeId,
                Timestamp.from(occurredAt == null ? Instant.now() : occurredAt),
                severity == null ? "LOW" : severity,
                indicator,
                campaignId,
                description
        );
        return id;
    }

    public List<Map<String, Object>> recentForEmployee(UUID tenantId, String targetEmployeeId, int windowHours) {
        Instant since = Instant.now().minus(windowHours, ChronoUnit.HOURS);
        return jdbc.queryForList("""
                SELECT id::text AS id, channel, target_employee_id AS "targetEmployeeId",
                       occurred_at AS "occurredAt", severity, indicator, campaign_id AS "campaignId",
                       description
                FROM cross_channel_events
                WHERE tenant_id = ?
                  AND target_employee_id = ?
                  AND occurred_at >= ?
                ORDER BY occurred_at DESC
                LIMIT 100
                """, tenantId, targetEmployeeId, Timestamp.from(since));
    }
}
