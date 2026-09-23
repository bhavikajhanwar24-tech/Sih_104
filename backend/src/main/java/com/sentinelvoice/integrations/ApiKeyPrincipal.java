package com.sentinelvoice.integrations;

import java.util.Set;
import java.util.UUID;

/**
 * Authenticated machine principal for F17 API keys.
 */
public record ApiKeyPrincipal(
        UUID keyId,
        UUID tenantId,
        String name,
        String prefix,
        Set<String> scopes
) {
    public boolean hasScope(String scope) {
        return scopes != null && scopes.contains(scope);
    }
}
