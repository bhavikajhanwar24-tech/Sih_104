package com.sentinelvoice.security;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({AuthProperties.class, MlServiceProperties.class})
public class AuthPropertiesValidator {

    private final AuthProperties properties;
    private final MlServiceProperties mlServiceProperties;

    public AuthPropertiesValidator(AuthProperties properties, MlServiceProperties mlServiceProperties) {
        this.properties = properties;
        this.mlServiceProperties = mlServiceProperties;
    }

    @PostConstruct
    void requireSecrets() {
        String secret = properties.jwtSecret();
        if (secret == null || secret.getBytes().length < 32) {
            throw new IllegalStateException(
                    "JWT_SECRET / sentinelvoice.auth.jwt-secret missing or shorter than 32 bytes"
            );
        }
        if (!mlServiceProperties.isConfigured() || mlServiceProperties.serviceToken().length() < 16) {
            throw new IllegalStateException(
                    "ML_SERVICE_TOKEN / sentinelvoice.ml-service.service-token missing or shorter than 16 chars"
            );
        }
    }
}
