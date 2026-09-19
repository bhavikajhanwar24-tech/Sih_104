package com.sentinelvoice.policy;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * F6 compile tunables (infrastructure — not tenant-editable).
 */
@ConfigurationProperties(prefix = "sentinelvoice.policy-compile")
public record PolicyCompileProperties(
        int maxDocumentsPerCompile
) {
    public PolicyCompileProperties {
        if (maxDocumentsPerCompile <= 0) {
            maxDocumentsPerCompile = 1;
        }
    }
}
