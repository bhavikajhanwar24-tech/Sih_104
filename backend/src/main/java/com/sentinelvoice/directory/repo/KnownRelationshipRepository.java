package com.sentinelvoice.directory.repo;

import com.sentinelvoice.directory.entity.KnownRelationshipEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KnownRelationshipRepository extends JpaRepository<KnownRelationshipEntity, UUID> {
    Optional<KnownRelationshipEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    List<KnownRelationshipEntity> findByTenantIdAndFromEmployeeId(UUID tenantId, UUID fromEmployeeId);

    @Query("""
            SELECT r FROM KnownRelationshipEntity r
            WHERE r.tenantId = :tenantId
              AND r.fromEmployeeId = :fromId
              AND (
                    (:toEmployeeId IS NOT NULL AND r.toEmployeeId = :toEmployeeId)
                 OR (:toExternalId IS NOT NULL AND r.toExternalId = :toExternalId)
              )
            """)
    Optional<KnownRelationshipEntity> findPair(
            @Param("tenantId") UUID tenantId,
            @Param("fromId") UUID fromId,
            @Param("toEmployeeId") UUID toEmployeeId,
            @Param("toExternalId") UUID toExternalId
    );
}
