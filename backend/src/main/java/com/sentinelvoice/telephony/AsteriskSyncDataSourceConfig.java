package com.sentinelvoice.telephony;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Optional mirror writer for Asterisk PJSIP REALTIME tables.
 *
 * <p>When the Decision Plane uses Supabase but Asterisk cannot speak TLS to the
 * pooler, set {@code ASTERISK_SYNC_URL} to local Docker Postgres. The mirror
 * must <em>not</em> be registered as a Spring {@link DataSource} or
 * {@link JdbcTemplate} bean — those types trip
 * {@code @ConditionalOnMissingBean} and would replace the primary app pool,
 * routing every repository to the mirror (which has no {@code trunks} /
 * {@code sip_endpoints}).
 */
@Configuration
public class AsteriskSyncDataSourceConfig {

    @Bean
    @ConditionalOnExpression("!'${ASTERISK_SYNC_URL:}'.trim().isEmpty()")
    public AsteriskMirrorJdbc asteriskMirrorJdbc(
            @Value("${ASTERISK_SYNC_URL}") String url,
            @Value("${ASTERISK_SYNC_USER:sv_bootstrap}") String user,
            @Value("${ASTERISK_SYNC_PASSWORD:changeme_bootstrap}") String password
    ) {
        DataSource ds = DataSourceBuilder.create()
                .url(url)
                .username(user)
                .password(password)
                .driverClassName("org.postgresql.Driver")
                .build();
        return new AsteriskMirrorJdbc(new JdbcTemplate(ds));
    }
}
