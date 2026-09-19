package com.sentinelvoice.security;

import com.sentinelvoice.auth.Role;

import java.util.UUID;

/**
 * Request-scoped identity populated from the access JWT (F2). F3 binds this into DB RLS.
 */
public record TenantContext(
        UUID tenantId,
        UUID userId,
        Role role,
        String email,
        int tokenVersion
) {
    private static final ThreadLocal<TenantContext> HOLDER = new ThreadLocal<>();

    public static void set(TenantContext ctx) {
        HOLDER.set(ctx);
    }

    public static TenantContext get() {
        return HOLDER.get();
    }

    public static TenantContext require() {
        TenantContext ctx = HOLDER.get();
        if (ctx == null) {
            throw new IllegalStateException("TenantContext is not set for this request");
        }
        return ctx;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
