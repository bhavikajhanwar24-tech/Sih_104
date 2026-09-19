package com.sentinelvoice.tenant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TenantSettingsRepository extends JpaRepository<TenantSettingsEntity, UUID> {
}
