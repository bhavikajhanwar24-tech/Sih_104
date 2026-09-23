package com.sentinelvoice.integrations;

import java.util.Set;

/**
 * F17 API key scopes.
 */
public final class ApiKeyScopes {

    public static final String TRANSACTIONS_WRITE = "transactions:write";
    public static final String EVENTS_WRITE = "events:write";
    public static final String SESSIONS_READ = "sessions:read";
    public static final String RISK_READ = "risk:read";
    public static final String DIRECTORY_SYNC = "directory:sync";
    public static final String WEBHOOKS_MANAGE = "webhooks:manage";

    public static final Set<String> ALL = Set.of(
            TRANSACTIONS_WRITE,
            EVENTS_WRITE,
            SESSIONS_READ,
            RISK_READ,
            DIRECTORY_SYNC,
            WEBHOOKS_MANAGE
    );

    private ApiKeyScopes() {
    }

    public static boolean isValid(String scope) {
        return scope != null && ALL.contains(scope.trim());
    }
}
