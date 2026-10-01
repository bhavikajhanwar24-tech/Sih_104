package com.sentinelvoice.security;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({AuthProperties.class, MlServiceProperties.class})
public class AuthPropertiesValidator {

    private static final Logger log = LoggerFactory.getLogger(AuthPropertiesValidator.class);

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
            log.warn("JWT_SECRET / sentinelvoice.auth.jwt-secret missing or shorter than 32 bytes — using development fallback");
        } else {
            log.info("JWT_SECRET is configured (length={})", secret.length());
        }
        if (!mlServiceProperties.isConfigured() || mlServiceProperties.serviceToken().length() < 16) {
            log.warn("ML_SERVICE_TOKEN / sentinelvoice.ml-service.service-token missing or shorter than 16 chars — using development fallback");
        } else {
            log.info("ML_SERVICE_TOKEN is configured");
        }
    }
}
