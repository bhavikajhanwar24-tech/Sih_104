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

/**
 * Logs allowed browser origins at boot so a CORS 403 is diagnosable from the terminal
 * (classic trap: Vite drifted to :5174 while the allowlist only had :5173).
 */
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
        log.info(
                "cors_hint if you see HTTP 403 Invalid CORS request, open the UI on an allowed origin "
                        + "(default http://127.0.0.1:5173/) or set SENTINELVOICE_CORS_ORIGINS"
        );
    }

    static List<String> allowedOrigins(Environment environment) {
        String raw = environment.getProperty("SENTINELVOICE_CORS_ORIGINS", "");
        if (raw != null && !raw.isBlank()) {
            return Arrays.stream(raw.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
        }
        return SecurityConfig.DEFAULT_ALLOWED_ORIGINS;
    }
}
