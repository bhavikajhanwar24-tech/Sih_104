package com.sentinelvoice.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sentinelvoice.llm-gateway")
public record LlmGatewayProperties(
        String baseUrl,
        String serviceToken,
        int connectTimeoutMs,
        int readTimeoutMs
) {
    public LlmGatewayProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://127.0.0.1:8000";
        }
        if (serviceToken == null) {
            serviceToken = "";
        }
        if (connectTimeoutMs <= 0) {
            connectTimeoutMs = 2000;
        }
        if (readTimeoutMs <= 0) {
            readTimeoutMs = 65_000;
        }
    }
}
