package com.sentinelvoice;

import com.sentinelvoice.service.SessionEvictionScheduler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
public class SentinelVoiceApplication {

    public SentinelVoiceApplication(SessionEvictionScheduler sessionEvictionScheduler) {
        // Constructor injection keeps the scheduler reachable as a collaborator (ArchitectureTest).
    }

    public static void main(String[] args) {
        // pgjdbc sends the JVM zone on connect; Windows may report obsolete "Asia/Calcutta",
        // which Debian postgres:16 rejects. Force UTC before any JDBC/Flyway connection.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(SentinelVoiceApplication.class, args);
    }
}
