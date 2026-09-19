package com.sentinelvoice.policy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PolicyDocumentRepository extends JpaRepository<PolicyDocumentEntity, UUID> {

    List<PolicyDocumentEntity> findByTenantIdAndStatusNotOrderByUploadedAtDesc(UUID tenantId, String status);

    List<PolicyDocumentEntity> findByTenantIdAndStatusOrderByUploadedAtDesc(UUID tenantId, String status);

    List<PolicyDocumentEntity> findByTenantIdOrderByUploadedAtDesc(UUID tenantId);

    Optional<PolicyDocumentEntity> findByTenantIdAndId(UUID tenantId, UUID id);

    Optional<PolicyDocumentEntity> findByTenantIdAndSha256(UUID tenantId, String sha256);
}
