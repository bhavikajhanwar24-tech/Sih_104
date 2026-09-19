package com.sentinelvoice.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * File H2 persistence: append → close context → reopen same DB file → verify still valid.
 */
class AuditPersistenceRestartIT {

    @TempDir
    Path tempDir;

    private static ConfigurableApplicationContext boot(String jdbc) {
        return new SpringApplicationBuilder(com.sentinelvoice.SentinelVoiceApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("nosec")
                .properties(
                        "spring.datasource.url=" + jdbc,
                        "spring.datasource.driver-class-name=org.h2.Driver",
                        "spring.datasource.username=sa",
                        "spring.datasource.password=password",
                        "spring.jpa.hibernate.ddl-auto=update",
                        "spring.jpa.defer-datasource-initialization=true",
                        "spring.sql.init.mode=never",
                        "sv.security.enabled=false"
                )
                .run();
    }

    @Test
    void verifySurvivesJvmRestartAgainstFileH2() {
        String dbPath = tempDir.resolve("sentinelvoice").toAbsolutePath().toString().replace('\\', '/');
        String jdbc = "jdbc:h2:file:" + dbPath + ";MODE=LEGACY;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
        String sessionId = "persist-" + UUID.randomUUID();

        try (ConfigurableApplicationContext ctx1 = boot(jdbc)) {
            AuditLedgerService ledger = ctx1.getBean(AuditLedgerService.class);
            ledger.append(sessionId, AuditEventType.SESSION_OPENED, Map.of("phase", "boot"));
            ledger.append(sessionId, AuditEventType.FEATURE_FRAME_SCORED, Map.of("seq", 1));
            assertThat(ledger.verify(sessionId).valid()).isTrue();
            Long count = ctx1.getBean(JdbcTemplate.class)
                    .queryForObject("select count(*) from audit_blocks where session_id = ?", Long.class, sessionId);
            assertThat(count).isEqualTo(2L);
        }

        try (ConfigurableApplicationContext ctx2 = boot(jdbc)) {
            AuditLedgerService ledger = ctx2.getBean(AuditLedgerService.class);
            ChainVerificationResult result = ledger.verify(sessionId);
            assertThat(result.valid()).isTrue();
            assertThat(result.blockCount()).isEqualTo(2);
            assertThat(result.brokenAtIndex()).isNull();
        }
    }
}
