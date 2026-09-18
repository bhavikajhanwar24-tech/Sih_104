package com.sentinelvoice.repository;

import com.sentinelvoice.context.model.CrossChannelEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface CrossChannelEventRepository extends JpaRepository<CrossChannelEvent, String> {

    List<CrossChannelEvent> findByTargetEmployeeIdAndOccurredAtGreaterThanEqualOrderByOccurredAtAsc(
            String targetEmployeeId,
            Instant since
    );

    List<CrossChannelEvent> findByCampaignId(String campaignId);
}
