package com.sentinelvoice.tenant;

import java.util.UUID;

/**
 * Fixed bootstrap tenant until F2/F3 multi-tenancy is live (V002__bootstrap_tenant.sql).
 */
public final class BootstrapTenant {

    public static final UUID ID = UUID.fromString("00000000-0000-4000-8000-0000000000b1");
    public static final String SLUG = "bootstrap";

    /** Genesis prev_hash for the first block in a tenant chain (64 hex zeros). */
    public static final String GENESIS_PREV_HASH = "0".repeat(64);

    private BootstrapTenant() {
    }
}
