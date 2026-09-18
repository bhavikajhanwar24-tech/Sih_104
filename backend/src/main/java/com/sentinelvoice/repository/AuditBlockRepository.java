package com.sentinelvoice.repository;

import com.sentinelvoice.model.AuditBlock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AuditBlockRepository extends JpaRepository<AuditBlock, Long> {

    List<AuditBlock> findBySessionIdOrderByBlockIndexAsc(String sessionId);

    Page<AuditBlock> findBySessionIdOrderByBlockIndexAsc(String sessionId, Pageable pageable);

    Optional<AuditBlock> findTopBySessionIdOrderByBlockIndexDesc(String sessionId);

    long countByEventType(String eventType);

    @Query("select distinct a.sessionId from AuditBlock a order by a.sessionId asc")
    List<String> findDistinctSessionIds();

    @Query("""
            select count(a) from AuditBlock a
            where a.eventType = :eventType
              and a.tsEpochMs >= :fromMs
              and a.tsEpochMs < :toMs
            """)
    long countByEventTypeAndTsRange(
            @Param("eventType") String eventType,
            @Param("fromMs") long fromMs,
            @Param("toMs") long toMs
    );

    @Query("""
            select count(a) from AuditBlock a
            where a.eventType = :eventType
              and a.tsEpochMs < :beforeMs
            """)
    long countByEventTypeOlderThan(
            @Param("eventType") String eventType,
            @Param("beforeMs") long beforeMs
    );
}
