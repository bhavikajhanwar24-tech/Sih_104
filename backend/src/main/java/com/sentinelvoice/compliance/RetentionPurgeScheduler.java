package com.sentinelvoice.compliance;

import com.sentinelvoice.config.SentinelProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Retention tick. Audit blocks are immutable (F1) — no DELETE. Purge of aged telemetry
 * payloads will be redesigned when soft-delete / archival lands.
 */
@Component
public class RetentionPurgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetentionPurgeScheduler.class);

    private final ComplianceMetricsService metricsService;
    private final int telemetryTtlDays;

    public RetentionPurgeScheduler(
            ComplianceMetricsService metricsService,
            SentinelProperties properties
    ) {
        this.metricsService = metricsService;
        this.telemetryTtlDays = properties.compliance().telemetryTtlDays();
    }

    @Scheduled(fixedDelayString = "${sentinelvoice.compliance.purge-interval-ms:86400000}")
    public void purgeExpiredTelemetry() {
        Instant next = metricsService.markPurgeCompleted();
        log.info(
                "retention_purge skipped_delete=true reason=audit_blocks_immutable telemetryTtlDays={} nextPurge={}",
                telemetryTtlDays,
                next
        );
    }
}
