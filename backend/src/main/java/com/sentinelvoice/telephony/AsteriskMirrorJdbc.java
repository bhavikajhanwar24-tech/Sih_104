package com.sentinelvoice.telephony;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Holder for the optional Asterisk REALTIME mirror {@link JdbcTemplate}.
 * Kept as a dedicated type so it does not displace Spring Boot's primary
 * {@code dataSource} / {@code jdbcTemplate} auto-configuration.
 */
public final class AsteriskMirrorJdbc {

    private final JdbcTemplate jdbc;

    public AsteriskMirrorJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }
}
