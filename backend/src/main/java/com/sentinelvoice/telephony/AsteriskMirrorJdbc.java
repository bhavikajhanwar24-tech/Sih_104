package com.sentinelvoice.telephony;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Holder for the optional Asterisk REALTIME mirror {@link JdbcTemplate}.
 * Kept as a dedicated type so it does not displace Spring Boot's primary
 * {@code dataSource} / {@code jdbcTemplate} auto-configuration.
 */
public final class AsteriskMirrorJdbc implements AutoCloseable {

    private final HikariDataSource dataSource;
    private final JdbcTemplate jdbc;

    public AsteriskMirrorJdbc(HikariDataSource dataSource, JdbcTemplate jdbc) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
    }

    /** @deprecated Prefer {@link #AsteriskMirrorJdbc(HikariDataSource, JdbcTemplate)}. */
    @Deprecated
    public AsteriskMirrorJdbc(JdbcTemplate jdbc) {
        this.dataSource = null;
        this.jdbc = jdbc;
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
