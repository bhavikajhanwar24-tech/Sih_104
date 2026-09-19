package com.sentinelvoice.directory.repo;

import com.sentinelvoice.directory.entity.EmployeeEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EmployeeRepository extends JpaRepository<EmployeeEntity, UUID> {
    Optional<EmployeeEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<EmployeeEntity> findByTenantIdAndEmployeeCodeIgnoreCase(UUID tenantId, String employeeCode);

    List<EmployeeEntity> findByTenantId(UUID tenantId);

    long countByTenantId(UUID tenantId);

    @Query("""
            SELECT e FROM EmployeeEntity e
            WHERE e.tenantId = :tenantId
              AND (:departmentId IS NULL OR e.departmentId = :departmentId)
              AND (:status IS NULL OR e.status = :status)
              AND (:roleKey IS NULL OR lower(e.roleKey) = :roleKey)
              AND (
                   :q IS NULL OR :q = ''
                   OR lower(e.fullName) LIKE concat('%', :q, '%')
                   OR lower(e.employeeCode) LIKE concat('%', :q, '%')
                   OR lower(coalesce(e.email, '')) LIKE concat('%', :q, '%')
                   OR lower(coalesce(e.roleKey, '')) LIKE concat('%', :q, '%')
              )
            """)
    Page<EmployeeEntity> search(
            @Param("tenantId") UUID tenantId,
            @Param("q") String q,
            @Param("departmentId") UUID departmentId,
            @Param("status") String status,
            @Param("roleKey") String roleKey,
            Pageable pageable
    );
}
