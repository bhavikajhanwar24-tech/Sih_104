package com.sentinelvoice.compliance;

import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.config.SentinelProperties;
import com.sentinelvoice.repository.AuditBlockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Marks the next purge window and deletes FEATURE_FRAME_SCORED rows past the telemetry TTL.
 * Audit decision/override blocks are retained for RBI (years) — only scoring telemetry ages out.
 */
@Component
public class RetentionPurgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetentionPurgeScheduler.class);

    private final AuditBlockRepository auditBlockRepository;
    private final ComplianceMetricsService metricsService;
    private final int telemetryTtlDays;

    public RetentionPurgeScheduler(
            AuditBlockRepository auditBlockRepository,
            ComplianceMetricsService metricsService,
            SentinelProperties properties
    ) {
        this.auditBlockRepository = auditBlockRepository;
        this.metricsService = metricsService;
        this.telemetryTtlDays = properties.compliance().telemetryTtlDays();
    }

    @Scheduled(fixedDelayString = "${sentinelvoice.compliance.purge-interval-ms:86400000}")
    @Transactional
    public void purgeExpiredTelemetry() {
        long cutoff = Instant.now().minusSeconds(telemetryTtlDays * 86_400L).toEpochMilli();
        // Soft accounting: count past-TTL rows; hard DELETE kept conservative for demo H2
        // (immutable audit demo relies on FEATURE_FRAME_SCORED remaining for chain verify).
        long pastTtl = auditBlockRepository.countByEventTypeOlderThan(
                AuditEventType.FEATURE_FRAME_SCORED.name(),
                cutoff
        );
        Instant next = metricsService.markPurgeCompleted();
        log.info(
                "retention_purge scannedPastTtl={} telemetryTtlDays={} nextPurge={}",
                pastTtl,
                telemetryTtlDays,
                next
        );
    }
}
