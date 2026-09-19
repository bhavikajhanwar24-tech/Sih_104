package com.sentinelvoice.directory.repo;

import com.sentinelvoice.directory.entity.BeneficiaryAccountEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BeneficiaryAccountRepository extends JpaRepository<BeneficiaryAccountEntity, UUID> {
    Optional<BeneficiaryAccountEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    Page<BeneficiaryAccountEntity> findByTenantId(UUID tenantId, Pageable pageable);

    Optional<BeneficiaryAccountEntity> findByTenantIdAndAccountRefHash(UUID tenantId, String accountRefHash);
}
