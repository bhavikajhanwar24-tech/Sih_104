package com.sentinelvoice.repository;

import com.sentinelvoice.model.AuditBlock;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditBlockRepository extends JpaRepository<AuditBlock, Long> {
}
