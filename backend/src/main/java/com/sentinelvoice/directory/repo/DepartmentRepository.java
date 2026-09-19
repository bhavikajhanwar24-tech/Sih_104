package com.sentinelvoice.directory.repo;

import com.sentinelvoice.directory.entity.DepartmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DepartmentRepository extends JpaRepository<DepartmentEntity, UUID> {
    List<DepartmentEntity> findByTenantIdOrderByNameAsc(UUID tenantId);

    Optional<DepartmentEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<DepartmentEntity> findByTenantIdAndNameIgnoreCase(UUID tenantId, String name);

    boolean existsByTenantIdAndParentId(UUID tenantId, UUID parentId);
}
