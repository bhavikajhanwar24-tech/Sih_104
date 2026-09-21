package com.sentinelvoice.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads repo-root {@code .env} into the Spring {@link Environment} when keys are
 * not already set (process env / IDE run config win). Fixes host-run Decision
 * Plane missing {@code SIP_EXTERNAL_IP} / {@code ASTERISK_SYNC_*} when started
 * without {@code scripts/sv.ps1 Import-DotEnv}.
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class DotEnvEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String PROPERTY_SOURCE = "sentinelvoiceDotEnv";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path envFile = resolveEnvFile();
        if (envFile == null || !Files.isRegularFile(envFile)) {
            return;
        }
        Map<String, Object> loaded = new LinkedHashMap<>();
        try {
            List<String> lines = Files.readAllLines(envFile, StandardCharsets.UTF_8);
            for (String raw : lines) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq < 1) {
                    continue;
                }
                String key = line.substring(0, eq).trim();
                String val = line.substring(eq + 1).trim();
                if ((val.startsWith("\"") && val.endsWith("\"")) || (val.startsWith("'") && val.endsWith("'"))) {
                    val = val.substring(1, val.length() - 1);
                }
                if (key.isEmpty()) {
                    continue;
                }
                // Do not override real process environment / prior property sources.
                if (environment.containsProperty(key) && hasNonBlank(environment.getProperty(key))) {
                    continue;
                }
                if (System.getenv(key) != null && !System.getenv(key).isBlank()) {
                    continue;
                }
                loaded.put(key, val);
            }
        } catch (IOException e) {
            // Loud but non-fatal — health endpoint will still report missing SIP_EXTERNAL_IP.
            System.err.println("[sentinelvoice] failed to read .env at " + envFile + ": " + e.getMessage());
            return;
        }
        if (!loaded.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE, loaded));
            System.out.println("[sentinelvoice] loaded " + loaded.size() + " keys from " + envFile.toAbsolutePath());
        }
    }

    private static boolean hasNonBlank(String v) {
        return v != null && !v.isBlank();
    }

    private static Path resolveEnvFile() {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        Path[] candidates = {
                cwd.resolve(".env"),
                cwd.getParent() != null ? cwd.getParent().resolve(".env") : null,
                cwd.resolve("..").resolve(".env").normalize()
        };
        for (Path p : candidates) {
            if (p != null && Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }
}
