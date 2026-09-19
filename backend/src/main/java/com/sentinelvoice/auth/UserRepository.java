package com.sentinelvoice.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {

    Optional<UserEntity> findByTenantIdAndEmailIgnoreCase(UUID tenantId, String email);

    Optional<UserEntity> findByTenantIdAndId(UUID tenantId, UUID id);

    List<UserEntity> findByTenantIdOrderByCreatedAtAsc(UUID tenantId);

    long countByTenantIdAndRoleAndStatus(UUID tenantId, String role, String status);

    @Query("""
            select count(u) from UserEntity u
            where u.tenantId = :tenantId and u.role = 'TENANT_ADMIN' and u.status = 'ACTIVE'
            """)
    long countActiveAdmins(@Param("tenantId") UUID tenantId);
}
