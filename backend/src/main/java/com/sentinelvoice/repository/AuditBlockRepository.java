package com.sentinelvoice.repository;

import com.sentinelvoice.model.AuditBlock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AuditBlockRepository extends JpaRepository<AuditBlock, Long> {

    List<AuditBlock> findBySessionIdOrderByBlockIndexAsc(String sessionId);

    Page<AuditBlock> findBySessionIdOrderByBlockIndexAsc(String sessionId, Pageable pageable);

    Optional<AuditBlock> findTopBySessionIdOrderByBlockIndexDesc(String sessionId);
}
