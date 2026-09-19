package com.sentinelvoice.auth;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Central RBAC roles and derived permissions (F2).
 */
public enum Role {
    TENANT_ADMIN,
    POLICY_APPROVER,
    ANALYST,
    AUDITOR;

    public static Role from(String raw) {
        return Role.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    }

    public Set<String> permissions() {
        return switch (this) {
            case TENANT_ADMIN -> Set.of(
                    "dashboard:read",
                    "calls:read", "calls:act",
                    "directory:read", "directory:write",
                    "policies:read", "policies:write", "policies:submit",
                    "response:read", "response:write", "response:submit",
                    "audit:read",
                    "settings:read", "settings:write",
                    "users:read", "users:write",
                    "mfa:manage"
            );
            case POLICY_APPROVER -> Set.of(
                    "dashboard:read",
                    "policies:read", "policies:approve",
                    "response:read", "response:approve",
                    "audit:read",
                    "settings:read"
            );
            case ANALYST -> Set.of(
                    "dashboard:read",
                    "calls:read", "calls:act",
                    "audit:read"
            );
            case AUDITOR -> Set.of(
                    "dashboard:read",
                    "audit:read",
                    "settings:read",
                    "users:read",
                    "policies:read",
                    "response:read",
                    "directory:read"
            );
        };
    }

    public Set<Role> assignableByAdmin() {
        return EnumSet.allOf(Role.class);
    }
}
