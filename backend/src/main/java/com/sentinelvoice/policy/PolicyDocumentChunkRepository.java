package com.sentinelvoice.policy;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface PolicyDocumentChunkRepository extends JpaRepository<PolicyDocumentChunkEntity, UUID> {

    List<PolicyDocumentChunkEntity> findByTenantIdAndDocumentIdOrderByOrdinalAsc(UUID tenantId, UUID documentId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from PolicyDocumentChunkEntity c where c.tenantId = :tenantId and c.documentId = :documentId")
    void deleteByTenantIdAndDocumentId(@Param("tenantId") UUID tenantId, @Param("documentId") UUID documentId);

    long countByTenantIdAndDocumentId(UUID tenantId, UUID documentId);
}
