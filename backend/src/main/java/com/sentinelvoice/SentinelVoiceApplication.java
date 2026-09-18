package com.sentinelvoice;

import com.sentinelvoice.service.SessionEvictionScheduler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SentinelVoiceApplication {

    public SentinelVoiceApplication(SessionEvictionScheduler sessionEvictionScheduler) {
        // Constructor injection keeps the scheduler reachable as a collaborator (ArchitectureTest).
    }

    public static void main(String[] args) {
        SpringApplication.run(SentinelVoiceApplication.class, args);
    }
}
