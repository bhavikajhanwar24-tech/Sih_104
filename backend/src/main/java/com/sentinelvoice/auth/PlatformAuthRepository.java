package com.sentinelvoice.auth;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Platform lookups via SECURITY DEFINER functions (bypass RLS before TenantContext exists).
 */
@Repository
public class PlatformAuthRepository {

    @PersistenceContext
    private EntityManager entityManager;

    public boolean slugExists(String slug) {
        Boolean exists = (Boolean) entityManager
                .createNativeQuery("SELECT fn_slug_exists(:slug)")
                .setParameter("slug", slug)
                .getSingleResult();
        return Boolean.TRUE.equals(exists);
    }

    @SuppressWarnings("unchecked")
    public Optional<LoginLookup> findUserForLogin(String slug, String email) {
        Query q = entityManager.createNativeQuery("""
                SELECT tenant_id::text, tenant_slug, tenant_status, user_id::text, email,
                       password_hash, display_name, role, status, mfa_enabled, mfa_secret, token_version
                FROM fn_find_user_for_login(:slug, :email)
                """);
        q.setParameter("slug", slug);
        q.setParameter("email", email);
        List<Object[]> rows = q.getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] r = rows.get(0);
        return Optional.of(new LoginLookup(
                UUID.fromString((String) r[0]),
                (String) r[1],
                (String) r[2],
                UUID.fromString((String) r[3]),
                (String) r[4],
                (String) r[5],
                (String) r[6],
                (String) r[7],
                (String) r[8],
                Boolean.TRUE.equals(r[9]),
                (String) r[10],
                ((Number) r[11]).intValue()
        ));
    }

    public void registerTenant(
            UUID tenantId,
            String name,
            String slug,
            String industry,
            String region,
            UUID userId,
            String adminEmail,
            String passwordHash,
            String adminDisplayName
    ) {
        entityManager.createNativeQuery("""
                SELECT tenant_id FROM fn_register_tenant(
                    CAST(:tenantId AS uuid), :name, :slug, :industry, :region,
                    CAST(:userId AS uuid), :adminEmail, :passwordHash, :adminDisplayName
                )
                """)
                .setParameter("tenantId", tenantId.toString())
                .setParameter("name", name)
                .setParameter("slug", slug)
                .setParameter("industry", industry)
                .setParameter("region", region)
                .setParameter("userId", userId.toString())
                .setParameter("adminEmail", adminEmail)
                .setParameter("passwordHash", passwordHash)
                .setParameter("adminDisplayName", adminDisplayName)
                .getSingleResult();
    }

    public record LoginLookup(
            UUID tenantId,
            String tenantSlug,
            String tenantStatus,
            UUID userId,
            String email,
            String passwordHash,
            String displayName,
            String role,
            String status,
            boolean mfaEnabled,
            String mfaSecret,
            int tokenVersion
    ) {
    }
}
