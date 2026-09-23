package com.sentinelvoice.compliance;

import com.sentinelvoice.security.TenantContext;
import com.sentinelvoice.tenant.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * F15 — per-tenant retention purge. Deletes aged explainability rows; never deletes audit_blocks.
 */
@Component
public class RetentionPurgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetentionPurgeScheduler.class);

    private final ComplianceService complianceService;
    private final JdbcTemplate jdbc;

    public RetentionPurgeScheduler(ComplianceService complianceService, JdbcTemplate jdbc) {
        this.complianceService = complianceService;
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelayString = "${sentinelvoice.compliance.purge-interval-ms:86400000}")
    public void purgeExpiredTelemetry() {
        List<UUID> tenants = jdbc.query(
                "SELECT id FROM fn_list_active_tenant_ids()",
                (rs, i) -> rs.getObject("id", UUID.class)
        );
        for (UUID tenantId : tenants) {
            try {
                Map<String, Object> stats = TenantContext.runAs(tenantId, () -> complianceService.purgeTenant(tenantId));
                log.info(
                        "retention_purge tenantId={} ticks={} reasons={} extractions={} dossiers={}",
                        tenantId,
                        stats.get("sessionTicks"),
                        stats.get("sessionReasons"),
                        stats.get("sessionExtractions"),
                        stats.get("forensicDossiers")
                );
            } catch (Exception e) {
                log.warn("retention_purge_failed tenantId={} err={}", tenantId, e.toString());
            }
        }
    }
}
