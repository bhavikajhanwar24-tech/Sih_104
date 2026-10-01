package com.sentinelvoice.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 1. Loads repo-root {@code .env} into Spring Environment if present (local dev).
 * 2. Normalizes database connection parameters for cloud deployment (Supabase, Render, Neon).
 *    Ensures Supabase session pooler receives the required tenant identifier (role.<project_ref>)
 *    and prevents Flyway failures with ENOIDENTIFIER.
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class DotEnvEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String PROPERTY_SOURCE = "sentinelvoiceDotEnv";
    private static final String DB_NORMALIZATION_SOURCE = "sentinelvoiceCloudDbFix";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        loadDotEnv(environment);
        normalizeDatabaseEnvironment(environment);
    }

    private void loadDotEnv(ConfigurableEnvironment environment) {
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
                if (environment.containsProperty(key) && hasNonBlank(environment.getProperty(key))) {
                    continue;
                }
                if (System.getenv(key) != null && !System.getenv(key).isBlank()) {
                    continue;
                }
                loaded.put(key, val);
            }
        } catch (IOException e) {
            System.err.println("[sentinelvoice] failed to read .env at " + envFile + ": " + e.getMessage());
            return;
        }
        if (!loaded.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE, loaded));
            System.out.println("[sentinelvoice] loaded " + loaded.size() + " keys from " + envFile.toAbsolutePath());
        }
    }

    private void normalizeDatabaseEnvironment(ConfigurableEnvironment environment) {
        String rawUrl = getFirstNonBlank(environment, "DB_URL", "DATABASE_URL", "spring.datasource.url");
        if (rawUrl == null || rawUrl.isBlank()) {
            return;
        }

        Map<String, Object> overrides = new LinkedHashMap<>();

        // If URL has user:password@, parse and extract credentials
        String cleanUrl = rawUrl;
        String userFromUrl = null;
        String passwordFromUrl = null;

        Pattern credPattern = Pattern.compile("^(?:jdbc:)?postgres(?:ql)?://([^:]+):(.*)@([^/@:]+(?::\\d+)?)(/.*)$");
        Matcher m = credPattern.matcher(rawUrl);
        if (m.matches()) {
            userFromUrl = m.group(1);
            passwordFromUrl = m.group(2);
            String hostAndPort = m.group(3);
            String rest = m.group(4);
            try {
                userFromUrl = URLDecoder.decode(userFromUrl, StandardCharsets.UTF_8);
                passwordFromUrl = URLDecoder.decode(passwordFromUrl, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
            }
            cleanUrl = "jdbc:postgresql://" + hostAndPort + rest;
        } else if (cleanUrl.startsWith("postgres://")) {
            cleanUrl = "jdbc:postgresql://" + cleanUrl.substring("postgres://".length());
        } else if (cleanUrl.startsWith("postgresql://")) {
            cleanUrl = "jdbc:postgresql://" + cleanUrl.substring("postgresql://".length());
        }

        // Ensure TimeZone=UTC options is present if connecting to postgres
        if (cleanUrl.contains("postgresql://") && !cleanUrl.contains("TimeZone")) {
            cleanUrl += (cleanUrl.contains("?") ? "&" : "?") + "options=-c%20TimeZone%3DUTC";
        }

        overrides.put("spring.datasource.url", cleanUrl);
        overrides.put("spring.flyway.url", cleanUrl);
        overrides.put("DB_URL", cleanUrl);

        // Resolve password
        String pass = getFirstNonBlank(environment, "DB_OWNER_PASSWORD", "DB_APP_PASSWORD", "SUPABASE_DB_PASSWORD", "spring.datasource.password");
        if (passwordFromUrl != null && !passwordFromUrl.isBlank()) {
            pass = passwordFromUrl;
        } else if (pass != null && ("changeme_owner".equals(pass) || "changeme_app".equals(pass))) {
            String alt = getFirstNonBlank(environment, "SUPABASE_DB_PASSWORD", "DB_APP_PASSWORD");
            if (alt != null && !alt.isBlank() && !"changeme_app".equals(alt)) {
                pass = alt;
            }
        }
        if (pass != null && !pass.isBlank()) {
            overrides.put("spring.datasource.password", pass);
            overrides.put("spring.flyway.password", pass);
            overrides.put("DB_APP_PASSWORD", pass);
            overrides.put("DB_OWNER_PASSWORD", pass);
        }

        // Resolve username
        String user = userFromUrl;
        if (user == null || user.isBlank()) {
            user = getFirstNonBlank(environment, "DB_OWNER_USER", "DB_APP_USER", "spring.datasource.username");
        }

        // Supabase pooler tenant detection:
        // Pooler requires user to end with .<project_ref> (e.g. postgres.<ref>)
        if (cleanUrl.contains("pooler.supabase.com")) {
            String projectRef = getFirstNonBlank(environment, "SUPABASE_PROJECT_REF");
            if ((projectRef == null || projectRef.isBlank()) && user != null && user.contains(".")) {
                projectRef = user.substring(user.lastIndexOf('.') + 1);
            }
            if (projectRef == null || projectRef.isBlank()) {
                String sUrl = environment.getProperty("SUPABASE_DB_URL");
                if (sUrl != null && sUrl.contains(".")) {
                    Matcher refM = Pattern.compile("(?::|//)[^:@/]+\\.([a-zA-Z0-9_-]+):").matcher(sUrl);
                    if (refM.find()) {
                        projectRef = refM.group(1);
                    }
                }
            }
            // Fallback to project ref extracted from workspace config
            if (projectRef == null || projectRef.isBlank()) {
                projectRef = "niqrnlotuqcdfzahmppq";
            }

            // If user is local default (sv_owner or sv_app) or doesn't have tenant dot, fix it to postgres.<projectRef>
            if (user == null || "sv_owner".equals(user) || "sv_app".equals(user) || !user.contains(".")) {
                user = "postgres." + projectRef;
            }
        }

        if (user != null && !user.isBlank()) {
            overrides.put("spring.datasource.username", user);
            overrides.put("spring.flyway.user", user);
            overrides.put("DB_APP_USER", user);
            overrides.put("DB_OWNER_USER", user);
        }

        if (!overrides.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource(DB_NORMALIZATION_SOURCE, overrides));
            System.out.println("[sentinelvoice] normalized DB config for pooler: url=" + cleanUrl + ", user=" + user);
        }
    }

    private static String getFirstNonBlank(ConfigurableEnvironment env, String... keys) {
        for (String k : keys) {
            String v = env.getProperty(k);
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
            String sys = System.getenv(k);
            if (sys != null && !sys.isBlank()) {
                return sys.trim();
            }
        }
        return null;
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
