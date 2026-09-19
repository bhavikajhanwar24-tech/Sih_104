package com.sentinelvoice.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface LoginLockRepository extends JpaRepository<LoginLockEntity, UUID> {
    Optional<LoginLockEntity> findByTenantIdAndEmailLowerAndIpAddress(
            UUID tenantId, String emailLower, String ipAddress
    );
}
