package com.sentinelvoice.directory.repo;

import com.sentinelvoice.directory.entity.ExternalPartyEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ExternalPartyRepository extends JpaRepository<ExternalPartyEntity, UUID> {
    Optional<ExternalPartyEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    @Query("""
            SELECT e FROM ExternalPartyEntity e
            WHERE e.tenantId = :tenantId
              AND (:type IS NULL OR e.type = :type)
              AND (:q IS NULL OR :q = '' OR lower(e.name) LIKE lower(concat('%', :q, '%')))
            """)
    Page<ExternalPartyEntity> search(
            @Param("tenantId") UUID tenantId,
            @Param("q") String q,
            @Param("type") String type,
            Pageable pageable
    );
}
