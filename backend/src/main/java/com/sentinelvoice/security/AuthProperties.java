package com.sentinelvoice.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sentinelvoice.auth")
public record AuthProperties(
        String jwtSecret,
        long accessTokenTtlSeconds,
        long refreshTokenTtlSeconds,
        boolean cookieSecure,
        String cookieDomain,
        String cookieSameSite,
        String corsOrigins
) {
    public AuthProperties {
        if (accessTokenTtlSeconds <= 0) {
            accessTokenTtlSeconds = 900;
        }
        if (refreshTokenTtlSeconds <= 0) {
            refreshTokenTtlSeconds = 1_209_600;
        }
        if (corsOrigins == null || corsOrigins.isBlank()) {
            corsOrigins = "http://127.0.0.1:5173,http://localhost:5173";
        }
        if (jwtSecret == null) {
            jwtSecret = "";
        }
    }
}
