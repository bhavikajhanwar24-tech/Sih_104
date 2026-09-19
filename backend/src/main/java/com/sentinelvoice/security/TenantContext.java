package com.sentinelvoice.security;

import com.sentinelvoice.auth.Role;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Request-scoped identity from JWT (F2). F3 binds {@code tenantId} into PostgreSQL RLS via
 * {@code set_config('app.tenant_id', …, true)}. Background work must use {@link #runAs}.
 */
public record TenantContext(
        UUID tenantId,
        UUID userId,
        Role role,
        String email,
        int tokenVersion
) {
    private static final ThreadLocal<TenantContext> HOLDER = new ThreadLocal<>();
    /** When true, connection may proceed without app.tenant_id (SECURITY DEFINER / platform only). */
    private static final ThreadLocal<Boolean> PLATFORM = ThreadLocal.withInitial(() -> false);

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

    public static boolean isPlatformOperation() {
        return Boolean.TRUE.equals(PLATFORM.get());
    }

    public static <T> T runAs(UUID tenantId, Supplier<T> action) {
        return runAs(new TenantContext(tenantId, null, null, null, 0), action);
    }

    public static void runAs(UUID tenantId, Runnable action) {
        runAs(tenantId, () -> {
            action.run();
            return null;
        });
    }

    public static <T> T runAs(TenantContext ctx, Supplier<T> action) {
        TenantContext previous = HOLDER.get();
        HOLDER.set(ctx);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                HOLDER.remove();
            } else {
                HOLDER.set(previous);
            }
        }
    }

    /**
     * Platform path for SECURITY DEFINER SQL only — RLS stays fail-closed (unset tenant).
     */
    public static <T> T runAsPlatform(Supplier<T> action) {
        boolean prev = Boolean.TRUE.equals(PLATFORM.get());
        PLATFORM.set(true);
        try {
            return action.get();
        } finally {
            if (prev) {
                PLATFORM.set(true);
            } else {
                PLATFORM.remove();
            }
        }
    }
}
