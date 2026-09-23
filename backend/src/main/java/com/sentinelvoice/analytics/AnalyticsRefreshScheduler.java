package com.sentinelvoice.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * F16 — refresh analytics materialised views periodically.
 */
@Component
public class AnalyticsRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsRefreshScheduler.class);

    private final JdbcTemplate jdbc;

    public AnalyticsRefreshScheduler(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelayString = "${sentinelvoice.analytics.refresh-interval-ms:900000}")
    public void refreshMaterialisedViews() {
        try {
            jdbc.execute("SELECT fn_refresh_analytics_mvs()");
            log.debug("analytics_mv_refresh ok");
        } catch (Exception e) {
            log.warn("analytics_mv_refresh_failed err={}", e.toString());
        }
    }
}
