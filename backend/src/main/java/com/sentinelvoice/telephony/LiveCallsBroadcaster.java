package com.sentinelvoice.telephony;

import com.sentinelvoice.actuation.TransactionLockService;
import com.sentinelvoice.model.CallSession;
import com.sentinelvoice.model.TelemetryEntry;
import com.sentinelvoice.model.TelemetryFrame;
import com.sentinelvoice.response.execute.SessionActionRepository;
import com.sentinelvoice.service.CallSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * F13 — publishes live-call list deltas to {@code /topic/tenant/{tid}/calls}.
 */
@Component
public class LiveCallsBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(LiveCallsBroadcaster.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final CallSessionManager callSessionManager;
    private final CallSessionRepository callSessionRepository;
    private final SessionActionRepository sessionActionRepository;
    private final TransactionLockService transactionLockService;

    public LiveCallsBroadcaster(
            @Lazy SimpMessagingTemplate messagingTemplate,
            @Lazy CallSessionManager callSessionManager,
            CallSessionRepository callSessionRepository,
            SessionActionRepository sessionActionRepository,
            TransactionLockService transactionLockService
    ) {
        this.messagingTemplate = messagingTemplate;
        this.callSessionManager = callSessionManager;
        this.callSessionRepository = callSessionRepository;
        this.sessionActionRepository = sessionActionRepository;
        this.transactionLockService = transactionLockService;
    }

    public static String topic(UUID tenantId) {
        return "/topic/tenant/" + tenantId + "/calls";
    }

    /** After a TelemetryFrame is published, push a compact call-row delta. */
    public void onTelemetry(TelemetryFrame frame) {
        if (frame == null || frame.sessionId() == null || frame.sessionId().isBlank()) {
            return;
        }
        Optional<CallSession> mem = callSessionManager.getSession(frame.sessionId());
        if (mem.isEmpty()) {
            return;
        }
        publishSessionDelta(mem.get(), frame);
    }

    /**
     * Push captions / LLM / rules / level even when the FeatureFrame was dropped as
     * stale or out-of-order (common when Decision Plane lags ASR).
     */
    public void publishSessionDelta(CallSession session) {
        publishSessionDelta(session, null);
    }

    public void publishSessionDelta(CallSession session, TelemetryFrame frame) {
        if (session == null || session.getSessionId() == null || session.getSessionId().isBlank()) {
            return;
        }
        UUID tenantId = session.getTenantId();
        if (tenantId == null) {
            return;
        }
        TelephonyModels.CallSessionListItem row = null;
        try {
            row = callSessionRepository
                    .findListItemBySvSession(tenantId, UUID.fromString(session.getSessionId()))
                    .orElse(null);
        } catch (IllegalArgumentException ignored) {
            // memory-only / non-UUID session ids
        }
        Map<String, Object> delta = buildDelta(tenantId, session, row, frame);
        messagingTemplate.convertAndSend(topic(tenantId), delta);
        log.debug("live_calls_delta sessionId={} level={}", session.getSessionId(), delta.get("liveLevel"));
    }

    private Map<String, Object> buildDelta(
            UUID tenantId,
            CallSession session,
            TelephonyModels.CallSessionListItem row,
            TelemetryFrame frame
    ) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemaVersion", "2");
        m.put("type", "call.upsert");
        m.put("tenantId", tenantId.toString());
        m.put("svSessionUuid", session.getSessionId());
        m.put("id", row == null ? session.getSessionId() : row.id().toString());
        m.put("active", row == null || row.endedAt() == null);
        m.put("serverTime", Instant.now().toString());

        Instant started = row != null && row.startedAt() != null ? row.startedAt() : session.getCreatedAt();
        long durationSec = started == null ? 0L : Math.max(0L, Duration.between(started, Instant.now()).getSeconds());
        m.put("startedAt", started == null ? null : started.toString());
        m.put("durationSec", durationSec);

        if (row != null) {
            m.put("callerName", nz(row.callerName(), row.callerNumber()));
            m.put("calleeName", nz(row.calleeName(), row.calleeNumber()));
            m.put("callerTitle", row.callerTitle());
            m.put("calleeTitle", row.calleeTitle());
            m.put("callerDepartment", row.callerDepartment());
            m.put("calleeDepartment", row.calleeDepartment());
            m.put("callerNumber", row.callerNumber());
            m.put("calleeNumber", row.calleeNumber());
            m.put("callerEmployeeId", row.callerEmployeeId() == null ? null : row.callerEmployeeId().toString());
            m.put("calleeEmployeeId", row.calleeEmployeeId() == null ? null : row.calleeEmployeeId().toString());
        } else {
            m.put("callerName", session.getCallerId());
            m.put("calleeName", session.getCalleeId());
        }

        String liveLevel = session.getCurrentLevel() == null ? null : session.getCurrentLevel().name();
        double liveScore = session.getSmoothedRisk();
        if (frame != null && frame.risk() != null) {
            liveScore = frame.risk().smoothed();
        }
        if (frame != null && frame.intervention() != null && frame.intervention().level() != null) {
            liveLevel = frame.intervention().level();
        }
        m.put("liveLevel", liveLevel);
        m.put("liveScore", liveScore);
        m.put("scoreHistory", scoreHistory(session));

        String llmState = "idle";
        if (Boolean.TRUE.equals(session.getLastLinguisticPending())) {
            llmState = "pending";
        } else if (session.getLastLlmThinking() != null && !session.getLastLlmThinking().isBlank()) {
            llmState = "ready";
        } else if (session.getLastLinguisticSource() != null) {
            llmState = "live";
        }
        m.put("llmState", llmState);
        m.put("llmThinking", session.getLastLlmThinking());
        m.put("asrTranscript", session.getLastAsrTranscript());
        m.put("matchedKeywords", session.getLastMatchedKeywords());
        m.put("brokenRuleIds", session.getLastBrokenRuleIds());
        m.put("brokenRuleTitles", session.getLastBrokenRuleTitles());

        Set<String> flags = sessionActionRepository.liveActionFlags(
                tenantId, List.of(session.getSessionId())
        ).getOrDefault(session.getSessionId(), Set.of());
        boolean isL3 = liveLevel != null && liveLevel.contains("LEVEL_3");
        boolean callbackVerified = transactionLockService.isCallbackVerified(session.getSessionId());
        boolean approvalLocked = (flags.contains("LOCK_APPROVAL") || isL3) && !callbackVerified;
        boolean callbackRequired = (flags.contains("REQUIRE_CALLBACK_VERIFICATION") || isL3) && !callbackVerified;
        m.put("activeActions", flags.stream().sorted().toList());
        m.put("approvalLocked", approvalLocked);
        m.put("callbackRequired", callbackRequired);
        m.put("callbackVerified", callbackVerified);
        m.put("pendingAction", pendingActionLabel(flags, approvalLocked, callbackRequired));
        return m;
    }

    private static List<Double> scoreHistory(CallSession session) {
        List<Double> out = new ArrayList<>();
        for (TelemetryEntry e : session.getTelemetryHistory().snapshot()) {
            out.add(e.smoothedRisk());
            if (out.size() >= 40) {
                break;
            }
        }
        if (out.size() > 24) {
            return out.subList(out.size() - 24, out.size());
        }
        return out;
    }

    private static String pendingActionLabel(Set<String> flags, boolean locked, boolean callback) {
        if (callback) {
            return "Confirm callback";
        }
        if (locked) {
            return "Approval locked";
        }
        if (flags.contains("BRIDGE_SUPERVISOR")) {
            return "Bridge supervisor";
        }
        if (!flags.isEmpty()) {
            return flags.iterator().next();
        }
        return null;
    }

    private static String nz(String name, String fallback) {
        if (name != null && !name.isBlank()) {
            return name;
        }
        return fallback == null ? "—" : fallback;
    }
}
