package com.sentinelvoice.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sentinelvoice.ml-service")
public record MlServiceProperties(String serviceToken) {
    public MlServiceProperties {
        if (serviceToken == null) {
            serviceToken = "";
        }
    }

    public boolean isConfigured() {
        return serviceToken != null && !serviceToken.isBlank();
    }

    public boolean matches(String provided) {
        if (!isConfigured() || provided == null) {
            return false;
        }
        return MessageDigestUtil.constantTimeEquals(serviceToken, provided);
    }
}
