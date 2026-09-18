package com.sentinelvoice.repository;

import com.sentinelvoice.context.model.InteractionEdge;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface InteractionEdgeRepository extends JpaRepository<InteractionEdge, Long> {

    Optional<InteractionEdge> findByCallerEmployeeIdAndCalleeEmployeeId(
            String callerEmployeeId,
            String calleeEmployeeId
    );
}
