package com.sentinelvoice.tenant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TenantRepository extends JpaRepository<TenantEntity, UUID> {
    Optional<TenantEntity> findBySlugIgnoreCase(String slug);

    boolean existsBySlugIgnoreCase(String slug);
}
