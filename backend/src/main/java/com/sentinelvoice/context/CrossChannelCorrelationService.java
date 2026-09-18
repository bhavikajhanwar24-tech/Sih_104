package com.sentinelvoice.context;

import com.sentinelvoice.config.SentinelProperties;
import com.sentinelvoice.context.model.CorrelationResult;
import com.sentinelvoice.context.model.CrossChannelEvent;
import com.sentinelvoice.identity.DirectoryService;
import com.sentinelvoice.identity.model.DirectoryRecord;
import com.sentinelvoice.model.CallSession;
import com.sentinelvoice.repository.CrossChannelEventRepository;
import com.sentinelvoice.service.CallSessionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Correlates SIEM-style email/SMS/auth/web precursors to the active call's <b>callee</b>
 * within a configurable lookback (default 48 h). Context §7.3.
 *
 * <p><b>Fusion choice:</b> the correlation score is an additive sub-component of the
 * RELATIONSHIP family — {@code S_rel = clamp01(S_graph + α·S_cross)} — rather than a
 * seventh evidence family. That keeps the six frozen fusion weights intact while still
 * letting a matching BEC→SMS campaign raise the relationship axis measurably.
 */
@Service
public class CrossChannelCorrelationService {

    /** Scenario 2 (CFO wire) callee — Sunita Rao, Branch Teller. */
    public static final String DEMO_CALLEE_EMPLOYEE_ID = "EMP-50040";
    public static final String DEMO_CAMPAIGN_ID = "BEC-CFO-2026-09";

    private final CrossChannelEventRepository repository;
    private final CallSessionManager callSessionManager;
    private final DirectoryService directoryService;
    private final SentinelProperties.CrossChannelScoring scoring;
    private final SentinelProperties.RelationshipScoring relationshipWeights;
    private final Clock clock;

    public CrossChannelCorrelationService(
            CrossChannelEventRepository repository,
            CallSessionManager callSessionManager,
            DirectoryService directoryService,
            SentinelProperties properties
    ) {
        this(repository, callSessionManager, directoryService, properties, Clock.systemUTC());
    }

    CrossChannelCorrelationService(
            CrossChannelEventRepository repository,
            CallSessionManager callSessionManager,
            DirectoryService directoryService,
            SentinelProperties properties,
            Clock clock
    ) {
        this.repository = repository;
        this.callSessionManager = callSessionManager;
        this.directoryService = directoryService;
        this.scoring = properties.context().crossChannel();
        this.relationshipWeights = properties.context().relationship();
        this.clock = clock;
    }

    @Transactional
    public Map<String, Object> ingest(CrossChannelEvent event) {
        if (event.getOccurredAt() == null) {
            event.setOccurredAt(Instant.now(clock));
        }
        CrossChannelEvent saved = repository.save(event);
        return toView(saved);
    }

    /**
     * Correlate precursors for a live session. Empty list when none match — never fabricates.
     */
    public CorrelationResult correlateSession(String sessionId, Integer windowHoursOverride) {
        CallSession session = callSessionManager.requireSession(sessionId);
        int windowHours = windowHoursOverride != null && windowHoursOverride > 0
                ? windowHoursOverride
                : scoring.windowHours();
        String employeeId = resolveCalleeEmployeeId(session);
        if (employeeId == null) {
            return CorrelationResult.empty(windowHours);
        }
        return correlateEmployee(employeeId, windowHours);
    }

    public CorrelationResult correlateEmployee(String targetEmployeeId, int windowHours) {
        Instant now = Instant.now(clock);
        Instant since = now.minusSeconds(windowHours * 3600L);
        List<CrossChannelEvent> found = repository
                .findByTargetEmployeeIdAndOccurredAtGreaterThanEqualOrderByOccurredAtAsc(
                        targetEmployeeId, since
                );
        if (found.isEmpty()) {
            return new CorrelationResult(targetEmployeeId, windowHours, 0.0, false, List.of());
        }

        boolean matchingCampaign = hasMatchingCampaign(found);
        boolean indicatorMatch = hasSharedIndicator(found);
        boolean multiChannel = hasEmailAndSms(found);

        double peak = 0.0;
        for (CrossChannelEvent e : found) {
            double sev = severityWeight(e.getSeverity());
            double recency = recencyWeight(e.getOccurredAt(), since, now);
            peak = Math.max(peak, sev * recency);
        }

        double score = peak;
        if (multiChannel) {
            score += scoring.multiChannelBoost();
        }
        if (indicatorMatch) {
            score += scoring.indicatorMatchBoost();
        }
        if (matchingCampaign) {
            score += scoring.matchingCampaignBoost();
        }
        score = clamp01(score);

        List<Map<String, Object>> views = new ArrayList<>(found.size());
        for (CrossChannelEvent e : found) {
            views.add(toView(e));
        }
        return new CorrelationResult(targetEmployeeId, windowHours, score, matchingCampaign, views);
    }

    /**
     * Blend graph relationship score with cross-channel correlation (additive, never fabricates).
     */
    public double blendRelationshipScore(double graphScore, CorrelationResult correlation) {
        if (correlation == null || !correlation.hasPrecursors()) {
            return clamp01(graphScore);
        }
        return clamp01(graphScore + relationshipWeights.weightCrossChannel() * correlation.correlationScore());
    }

    String resolveCalleeEmployeeId(CallSession session) {
        if (session == null) {
            return null;
        }
        // Scenario 2 seed path — CFO wire targets Sunita Rao.
        if ("cfo-wire-inr".equals(session.getScenarioId())) {
            return DEMO_CALLEE_EMPLOYEE_ID;
        }
        String callee = session.getCalleeId();
        if (callee == null || callee.isBlank()) {
            return null;
        }
        if (callee.toUpperCase(Locale.ROOT).startsWith("EMP-")) {
            return callee;
        }
        Optional<DirectoryRecord> byCli = directoryService.findByCli(callee);
        return byCli.map(DirectoryRecord::getEmployeeId).orElse(null);
    }

    private static boolean hasMatchingCampaign(List<CrossChannelEvent> events) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (CrossChannelEvent e : events) {
            if (e.getCampaignId() == null || e.getCampaignId().isBlank()) {
                continue;
            }
            counts.merge(e.getCampaignId(), 1, Integer::sum);
        }
        // Same campaign ID on ≥2 precursors (classic BEC email → smishing SMS).
        return counts.values().stream().anyMatch(c -> c >= 2);
    }

    private static boolean hasSharedIndicator(List<CrossChannelEvent> events) {
        Set<String> tokens = new HashSet<>();
        for (CrossChannelEvent e : events) {
            String norm = normaliseIndicator(e.getIndicator());
            if (norm.isEmpty()) {
                continue;
            }
            // Pull a display-name-ish token (letters only, length ≥ 4).
            for (String part : norm.split("[^a-z0-9]+")) {
                if (part.length() >= 4) {
                    if (!tokens.add(part) && !part.matches("\\d+")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean hasEmailAndSms(List<CrossChannelEvent> events) {
        boolean email = false;
        boolean sms = false;
        for (CrossChannelEvent e : events) {
            if (e.getChannel() == CrossChannelEvent.Channel.EMAIL) {
                email = true;
            }
            if (e.getChannel() == CrossChannelEvent.Channel.SMS) {
                sms = true;
            }
        }
        return email && sms;
    }

    private static double severityWeight(CrossChannelEvent.Severity severity) {
        if (severity == null) {
            return 0.4;
        }
        return switch (severity) {
            case CRITICAL -> 1.0;
            case HIGH -> 0.85;
            case MEDIUM -> 0.55;
            case LOW -> 0.30;
        };
    }

    private static double recencyWeight(Instant occurredAt, Instant since, Instant now) {
        if (occurredAt == null) {
            return 0.5;
        }
        long span = Math.max(1L, now.getEpochSecond() - since.getEpochSecond());
        long age = Math.max(0L, now.getEpochSecond() - occurredAt.getEpochSecond());
        double t = 1.0 - (age / (double) span);
        return 0.4 + 0.6 * clamp01(t);
    }

    private static String normaliseIndicator(String indicator) {
        if (indicator == null) {
            return "";
        }
        return indicator.toLowerCase(Locale.ROOT).trim();
    }

    private static Map<String, Object> toView(CrossChannelEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("channel", e.getChannel() != null ? e.getChannel().name() : null);
        m.put("targetEmployeeId", e.getTargetEmployeeId());
        m.put("occurredAtEpochMs", e.getOccurredAt() != null ? e.getOccurredAt().toEpochMilli() : null);
        m.put("severity", e.getSeverity() != null ? e.getSeverity().name() : null);
        m.put("indicator", e.getIndicator());
        m.put("campaignId", e.getCampaignId());
        m.put("description", e.getDescription());
        return m;
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, v));
    }
}
