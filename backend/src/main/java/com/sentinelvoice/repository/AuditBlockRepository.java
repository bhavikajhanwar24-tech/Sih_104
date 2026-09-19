package com.sentinelvoice.repository;

import com.sentinelvoice.model.AuditBlock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuditBlockRepository extends JpaRepository<AuditBlock, UUID> {

    List<AuditBlock> findByTenantIdOrderBySeqAsc(UUID tenantId);

    Page<AuditBlock> findByTenantIdOrderBySeqAsc(UUID tenantId, Pageable pageable);

    Optional<AuditBlock> findTopByTenantIdOrderBySeqDesc(UUID tenantId);

    long countByTenantId(UUID tenantId);

    long countByTenantIdAndEventType(UUID tenantId, String eventType);

    List<AuditBlock> findByTenantIdAndSeqGreaterThanOrderBySeqAsc(
            UUID tenantId,
            long seq,
            Pageable pageable
    );

    List<AuditBlock> findByTenantIdAndEventTypeAndSeqGreaterThanOrderBySeqAsc(
            UUID tenantId,
            String eventType,
            long seq,
            Pageable pageable
    );

    @Query(value = """
            SELECT COUNT(*) FROM audit_blocks
            WHERE tenant_id = :tenantId
              AND event_type = :eventType
              AND EXTRACT(EPOCH FROM created_at) * 1000 >= :fromMs
              AND EXTRACT(EPOCH FROM created_at) * 1000 < :toMs
            """, nativeQuery = true)
    long countByTenantIdAndEventTypeAndCreatedRange(
            @Param("tenantId") UUID tenantId,
            @Param("eventType") String eventType,
            @Param("fromMs") long fromMs,
            @Param("toMs") long toMs
    );
}
