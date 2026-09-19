package com.sentinelvoice.directory.repo;

import com.sentinelvoice.directory.entity.EmployeePhoneEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EmployeePhoneRepository extends JpaRepository<EmployeePhoneEntity, UUID> {
    List<EmployeePhoneEntity> findByTenantIdAndEmployeeIdOrderByPrimaryDescCreatedAtAsc(UUID tenantId, UUID employeeId);

    Optional<EmployeePhoneEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<EmployeePhoneEntity> findByTenantIdAndE164(UUID tenantId, String e164);

    Optional<EmployeePhoneEntity> findFirstByTenantIdAndSipExtension(UUID tenantId, String sipExtension);

    boolean existsByTenantIdAndE164(UUID tenantId, String e164);

    void deleteByTenantIdAndEmployeeId(UUID tenantId, UUID employeeId);
}
