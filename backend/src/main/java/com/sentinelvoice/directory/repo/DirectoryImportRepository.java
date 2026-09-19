package com.sentinelvoice.directory.repo;

import com.sentinelvoice.directory.entity.DirectoryImportEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DirectoryImportRepository extends JpaRepository<DirectoryImportEntity, UUID> {
    Optional<DirectoryImportEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    Page<DirectoryImportEntity> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);
}
