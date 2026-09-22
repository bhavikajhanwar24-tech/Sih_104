package com.sentinelvoice.telephony;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Optional mirror writer for Asterisk PJSIP REALTIME tables.
 *
 * <p>When the Decision Plane uses Supabase but Asterisk cannot speak TLS to the
 * pooler, set {@code ASTERISK_SYNC_URL} to local Docker Postgres. The mirror
 * must <em>not</em> be registered as a Spring {@code DataSource} or
 * {@code JdbcTemplate} bean — those types trip
 * {@code @ConditionalOnMissingBean} and would replace the primary app pool,
 * routing every repository to the mirror (which has no {@code trunks} /
 * {@code sip_endpoints}).
 *
 * <p>Pool is fail-fast (1.5s) with {@code initializationFailTimeout=-1} so a
 * stopped Docker Postgres never stalls Directory / Live Calls for Hikari's
 * default 30s connection timeout.
 */
@Configuration
public class AsteriskSyncDataSourceConfig {

    @Bean(destroyMethod = "close")
    @ConditionalOnExpression("!'${ASTERISK_SYNC_URL:}'.trim().isEmpty()")
    public AsteriskMirrorJdbc asteriskMirrorJdbc(
            @Value("${ASTERISK_SYNC_URL}") String url,
            @Value("${ASTERISK_SYNC_USER:sv_bootstrap}") String user,
            @Value("${ASTERISK_SYNC_PASSWORD:changeme_bootstrap}") String password
    ) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("asterisk-mirror");
        config.setJdbcUrl(withFastFailParams(url));
        config.setUsername(user);
        config.setPassword(password);
        config.setDriverClassName("org.postgresql.Driver");
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(1_500);
        config.setValidationTimeout(1_000);
        config.setIdleTimeout(30_000);
        config.setMaxLifetime(120_000);
        // Don't block Spring Boot startup when local Docker Postgres is down.
        config.setInitializationFailTimeout(-1);
        HikariDataSource ds = new HikariDataSource(config);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.setQueryTimeout(3);
        return new AsteriskMirrorJdbc(ds, jdbc);
    }

    /** Append connect/socket timeouts if the URL does not already set them. */
    static String withFastFailParams(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        String lower = url.toLowerCase();
        StringBuilder sb = new StringBuilder(url);
        char join = url.contains("?") ? '&' : '?';
        if (!lower.contains("connecttimeout=")) {
            sb.append(join).append("connectTimeout=2");
            join = '&';
        }
        if (!lower.contains("sockettimeout=")) {
            sb.append(join).append("socketTimeout=3");
        }
        return sb.toString();
    }
}
