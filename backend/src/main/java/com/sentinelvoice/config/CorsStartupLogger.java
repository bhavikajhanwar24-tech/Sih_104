package com.sentinelvoice.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class CorsStartupLogger implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CorsStartupLogger.class);

    private final Environment environment;

    public CorsStartupLogger(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> origins = allowedOrigins(environment);
        log.info("cors_allowed_origins count={} origins={}", origins.size(), origins);
    }

    static List<String> allowedOrigins(Environment environment) {
        String raw = environment.getProperty(
                "SENTINELVOICE_CORS_ORIGINS",
                environment.getProperty(
                        "sentinelvoice.auth.cors-origins",
                        "http://127.0.0.1:5173,http://localhost:5173"
                )
        );
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}
