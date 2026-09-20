package com.sentinelvoice.context;

import com.sentinelvoice.context.model.CorrelationResult;
import com.sentinelvoice.context.model.CrossChannelEvent;
import com.sentinelvoice.policy.engine.CrossChannelFactService;
import com.sentinelvoice.policy.engine.PolicyEngineProperties;
import com.sentinelvoice.security.TenantContext;
import com.sentinelvoice.service.CallSessionManager;
import com.sentinelvoice.model.CallSession;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Cross-channel correlation backed by {@code cross_channel_events} fact producers (F7).
 * v1 YAML CrossChannelScoring deleted — boosts are expressed as policy rules over facts.
 */
@Service
public class CrossChannelCorrelationService {

    public static final String DEMO_CALLEE_EMPLOYEE_ID = "EMP-50040";
    public static final String DEMO_CAMPAIGN_ID = "BEC-CFO-2026-09";

    private final CrossChannelFactService factService;
    private final PolicyEngineProperties props;
    private final CallSessionManager callSessionManager;

    public CrossChannelCorrelationService(
            CrossChannelFactService factService,
            PolicyEngineProperties props,
            CallSessionManager callSessionManager
    ) {
        this.factService = factService;
        this.props = props;
        this.callSessionManager = callSessionManager;
    }

    public Map<String, Object> ingest(CrossChannelEvent event) {
        UUID tenantId = TenantContext.require().tenantId();
        UUID id = factService.insert(
                tenantId,
                event.getChannel() == null ? "OTHER" : event.getChannel().name(),
                event.getTargetEmployeeId(),
                event.getOccurredAt(),
                event.getSeverity() == null ? "LOW" : event.getSeverity().name(),
                event.getIndicator(),
                event.getCampaignId(),
                event.getDescription()
        );
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id.toString());
        body.put("accepted", true);
        return body;
    }

    public CorrelationResult correlateSession(String sessionId, Integer windowHoursOverride) {
        int window = windowHoursOverride == null ? props.crossChannelWindowHours() : windowHoursOverride;
        Optional<CallSession> session = callSessionManager.getSession(sessionId);
        if (session.isEmpty()) {
            return CorrelationResult.empty(window);
        }
        String target = session.get().getCalleeId();
        if (target == null || target.isBlank()) {
            target = session.get().getCallerId();
        }
        return correlateEmployee(target, window);
    }

    public CorrelationResult correlateEmployee(String targetEmployeeId, int windowHours) {
        UUID tenantId = TenantContext.get() == null ? null : TenantContext.require().tenantId();
        if (tenantId == null || targetEmployeeId == null || targetEmployeeId.isBlank()) {
            return CorrelationResult.empty(windowHours);
        }
        List<Map<String, Object>> events = factService.recentForEmployee(tenantId, targetEmployeeId, windowHours);
        boolean matchingCampaign = events.stream()
                .anyMatch(e -> e.get("campaignId") != null && !String.valueOf(e.get("campaignId")).isBlank());
        double score = Math.min(1.0, events.size() * 0.1 + (matchingCampaign ? 0.2 : 0.0));
        List<Map<String, Object>> view = new ArrayList<>(events);
        return new CorrelationResult(targetEmployeeId, windowHours, score, matchingCampaign, view);
    }

    /**
     * Soft blend kept for telemetry/reasons; primary risk path is fact-driven rules (F7).
     */
    public double blendRelationshipScore(double graphScore, CorrelationResult crossChannel) {
        if (crossChannel == null || !crossChannel.hasPrecursors()) {
            return graphScore;
        }
        return Math.min(1.0, graphScore + 0.4 * crossChannel.correlationScore());
    }

    public void seedDemoCampaignIfEmpty() {
        // no-op — demo seeding is not part of F7
    }
}
