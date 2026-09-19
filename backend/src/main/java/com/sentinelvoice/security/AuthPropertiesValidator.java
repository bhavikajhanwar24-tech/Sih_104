package com.sentinelvoice.security;

import com.sentinelvoice.security.AuthProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class AuthPropertiesValidator {

    private final AuthProperties properties;

    public AuthPropertiesValidator(AuthProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void requireJwtSecret() {
        String secret = properties.jwtSecret();
        if (secret == null || secret.getBytes().length < 32) {
            throw new IllegalStateException(
                    "JWT_SECRET / sentinelvoice.auth.jwt-secret missing or shorter than 32 bytes"
            );
        }
    }
}
