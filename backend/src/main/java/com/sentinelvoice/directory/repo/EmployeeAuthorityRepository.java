package com.sentinelvoice.directory.repo;

import com.sentinelvoice.directory.entity.EmployeeAuthorityEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EmployeeAuthorityRepository extends JpaRepository<EmployeeAuthorityEntity, UUID> {
    List<EmployeeAuthorityEntity> findByTenantIdAndEmployeeId(UUID tenantId, UUID employeeId);

    Optional<EmployeeAuthorityEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    void deleteByTenantIdAndEmployeeId(UUID tenantId, UUID employeeId);
}
