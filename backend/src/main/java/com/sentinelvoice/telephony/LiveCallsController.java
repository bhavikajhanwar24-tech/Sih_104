package com.sentinelvoice.telephony;

import com.sentinelvoice.actuation.TransactionLockService;
import com.sentinelvoice.explain.SessionExplainRecorder;
import com.sentinelvoice.intervention.InterventionDecision;
import com.sentinelvoice.intervention.InterventionLadderService;
import com.sentinelvoice.model.CallSession;
import com.sentinelvoice.model.InterventionLevel;
import com.sentinelvoice.model.TelemetryEntry;
import com.sentinelvoice.response.execute.PlanRunner;
import com.sentinelvoice.response.execute.SessionActionRepository;
import com.sentinelvoice.security.TenantContext;
import com.sentinelvoice.service.CallSessionManager;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * F10 Live Calls / operator workspace — tenant-scoped {@code call_sessions} with
 * directory names, L3 action flags, bridge, and lab force-level.
 */
@RestController
@RequestMapping("/api/v2/calls")
public class LiveCallsController {

    private final CallSessionRepository callSessionRepository;
    private final CallSessionManager callSessionManager;
    private final SessionActionRepository sessionActionRepository;
    private final PlanRunner planRunner;
    private final InterventionLadderService interventionLadderService;
    private final TransactionLockService transactionLockService;
    private final SessionExplainRecorder sessionExplainRecorder;

    public LiveCallsController(
            CallSessionRepository callSessionRepository,
            CallSessionManager callSessionManager,
            SessionActionRepository sessionActionRepository,
            PlanRunner planRunner,
            InterventionLadderService interventionLadderService,
            TransactionLockService transactionLockService,
            SessionExplainRecorder sessionExplainRecorder
    ) {
        this.callSessionRepository = callSessionRepository;
        this.callSessionManager = callSessionManager;
        this.sessionActionRepository = sessionActionRepository;
        this.planRunner = planRunner;
        this.interventionLadderService = interventionLadderService;
        this.transactionLockService = transactionLockService;
        this.sessionExplainRecorder = sessionExplainRecorder;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','ANALYST','SUPERVISOR','AUDITOR')")
    public Map<String, Object> list(
            @RequestParam(defaultValue = "40") int limit,
            @RequestParam(defaultValue = "false") boolean activeOnly
    ) {
        UUID tenantId = TenantContext.require().tenantId();
        List<TelephonyModels.CallSessionListItem> rows =
                callSessionRepository.listRecent(tenantId, limit, activeOnly);

        List<String> sessionKeys = rows.stream()
                .map(r -> r.svSessionUuid() == null ? null : r.svSessionUuid().toString())
                .filter(s -> s != null)
                .toList();
        Map<String, Set<String>> actionFlags = sessionActionRepository.liveActionFlags(tenantId, sessionKeys);

        List<Map<String, Object>> items = new java.util.ArrayList<>(rows.stream().map(row -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("schemaVersion", "2");
            m.put("id", row.id().toString());
            m.put("tenantId", row.tenantId().toString());
            m.put("active", row.active());
            m.put("startedAt", row.startedAt() == null ? null : row.startedAt().toString());
            m.put("endedAt", row.endedAt() == null ? null : row.endedAt().toString());
            m.put("direction", row.direction());
            m.put("callerNumber", row.callerNumber());
            m.put("calleeNumber", row.calleeNumber());
            m.put("callerEmployeeId", row.callerEmployeeId() == null ? null : row.callerEmployeeId().toString());
            m.put("calleeEmployeeId", row.calleeEmployeeId() == null ? null : row.calleeEmployeeId().toString());
            m.put("callerName", displayName(row.callerName(), row.callerTitle(), row.callerNumber()));
            m.put("calleeName", displayName(row.calleeName(), row.calleeTitle(), row.calleeNumber()));
            m.put("callerTitle", row.callerTitle());
            m.put("calleeTitle", row.calleeTitle());
            m.put("callerDepartment", row.callerDepartment());
            m.put("calleeDepartment", row.calleeDepartment());
            m.put("peakScore", row.peakScore());
            m.put("peakLevel", row.peakLevel());
            m.put("finalOutcome", row.finalOutcome());
            m.put("sipCallId", row.sipCallId());
            m.put("svSessionUuid", row.svSessionUuid() == null ? null : row.svSessionUuid().toString());

            long durationSec = 0L;
            if (row.startedAt() != null) {
                Instant end = row.endedAt() == null ? Instant.now() : row.endedAt();
                durationSec = Math.max(0L, java.time.Duration.between(row.startedAt(), end).getSeconds());
            }
            m.put("durationSec", durationSec);

            String liveLevel = null;
            Double liveScore = null;
            String llmState = "idle";
            List<Double> scoreHistory = List.of();
            if (row.svSessionUuid() != null) {
                Optional<CallSession> mem = callSessionManager.getSession(row.svSessionUuid().toString());
                if (mem.isPresent()) {
                    CallSession s = mem.get();
                    liveLevel = s.getCurrentLevel() == null ? null : s.getCurrentLevel().name();
                    liveScore = s.getSmoothedRisk();
                    if (Boolean.TRUE.equals(s.getLastLinguisticPending())) {
                        llmState = "pending";
                    } else if (s.getLastLlmThinking() != null && !s.getLastLlmThinking().isBlank()) {
                        llmState = "ready";
                    } else if (s.getLastLinguisticSource() != null && !s.getLastLinguisticSource().isBlank()) {
                        llmState = "live";
                    }
                    m.put("linguisticStatus", llmState.equals("idle") ? "unavailable" : llmState);
                    m.put("linguisticSource", s.getLastLinguisticSource());
                    m.put("linguisticAgeMs", s.getLastLinguisticAgeMs());
                    m.put("linguisticConfidence", s.getLastLinguisticConfidence());
                    m.put("matchedKeywords", s.getLastMatchedKeywords());
                    m.put("llmThinking", s.getLastLlmThinking());
                    m.put("asrTranscript", s.getLastAsrTranscript());
                    m.put("brokenRuleIds", s.getLastBrokenRuleIds());
                    m.put("brokenRuleTitles", s.getLastBrokenRuleTitles());
                    scoreHistory = s.getTelemetryHistory().snapshot().stream()
                            .map(TelemetryEntry::smoothedRisk)
                            .collect(Collectors.toList());
                    if (scoreHistory.size() > 24) {
                        scoreHistory = scoreHistory.subList(scoreHistory.size() - 24, scoreHistory.size());
                    }
                }
            }
            m.put("liveLevel", liveLevel);
            m.put("liveScore", liveScore);
            m.put("llmState", llmState);
            m.put("scoreHistory", scoreHistory);

            String effectiveLevel = liveLevel != null ? liveLevel : null;
            boolean isL3 = effectiveLevel != null && effectiveLevel.contains("LEVEL_3");
            Set<String> flags = row.svSessionUuid() == null
                    ? Set.of()
                    : actionFlags.getOrDefault(row.svSessionUuid().toString(), Set.of());

            boolean callbackVerified = row.svSessionUuid() != null
                    && transactionLockService.isCallbackVerified(row.svSessionUuid().toString());
            boolean approvalLocked = (flags.contains("LOCK_APPROVAL") || isL3) && !callbackVerified;
            boolean callbackRequired = (flags.contains("REQUIRE_CALLBACK_VERIFICATION") || isL3)
                    && !callbackVerified;
            boolean bridgeFired = flags.contains("BRIDGE_SUPERVISOR");

            m.put("approvalLocked", approvalLocked);
            m.put("callbackRequired", callbackRequired);
            m.put("callbackVerified", callbackVerified);
            m.put("bridgeFired", bridgeFired);
            m.put("activeActions", flags.stream().sorted().collect(Collectors.toList()));
            String pending = null;
            if (callbackRequired) {
                pending = "Confirm callback";
            } else if (approvalLocked) {
                pending = "Approval locked";
            } else if (bridgeFired) {
                pending = "Bridge supervisor";
            } else if (!flags.isEmpty()) {
                pending = flags.iterator().next();
            }
            m.put("pendingAction", pending);
            if (approvalLocked) {
                m.put("approvalLockReason",
                        "Approval locked — confirm callback verification first, then Approve unlocks.");
            }
            return m;
        }).toList());

        // Bridge /api/v1/session/start opens memory without AGI — include those too.
        java.util.Set<String> seen = items.stream()
                .map(m -> String.valueOf(m.get("svSessionUuid")))
                .collect(Collectors.toSet());
        for (CallSession mem : callSessionManager.listSessionsForTenant(tenantId)) {
            if (mem.getSessionId() == null || seen.contains(mem.getSessionId())) {
                continue;
            }
            if (activeOnly) {
                // memory sessions are always "active"
            }
            items.add(0, mapMemorySession(mem));
            seen.add(mem.getSessionId());
        }

        int activeCount = (int) items.stream().filter(m -> Boolean.TRUE.equals(m.get("active"))).count();
        if (activeCount == 0) {
            activeCount = callSessionRepository.countActive(tenantId);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", "2");
        body.put("tenantId", tenantId.toString());
        body.put("activeCount", activeCount);
        body.put("serverTime", Instant.now().toString());
        body.put("items", items);
        return body;
    }

    private Map<String, Object> mapMemorySession(CallSession s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemaVersion", "2");
        m.put("id", s.getSessionId());
        m.put("tenantId", s.getTenantId() == null ? null : s.getTenantId().toString());
        m.put("active", true);
        m.put("startedAt", s.getCreatedAt() == null ? null : s.getCreatedAt().toString());
        m.put("endedAt", null);
        m.put("callerName", s.getCallerId() == null ? "Caller" : s.getCallerId());
        m.put("calleeName", s.getCalleeId() == null ? "Callee" : s.getCalleeId());
        m.put("svSessionUuid", s.getSessionId());
        long durationSec = s.getCreatedAt() == null
                ? 0L
                : Math.max(0L, java.time.Duration.between(s.getCreatedAt(), Instant.now()).getSeconds());
        m.put("durationSec", durationSec);
        m.put("liveLevel", s.getCurrentLevel() == null ? null : s.getCurrentLevel().name());
        m.put("liveScore", s.getSmoothedRisk());
        m.put("llmThinking", s.getLastLlmThinking());
        m.put("asrTranscript", s.getLastAsrTranscript());
        m.put("matchedKeywords", s.getLastMatchedKeywords());
        m.put("brokenRuleIds", s.getLastBrokenRuleIds());
        m.put("brokenRuleTitles", s.getLastBrokenRuleTitles());
        m.put("llmState", "live");
        m.put("scoreHistory", List.of());
        m.put("activeActions", List.of());
        return m;
    }

    /**
     * Bridge supervisor into an active call. Requires {@code calls:bridge} (TENANT_ADMIN only).
     */
    @PostMapping("/{id}/bridge")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','SUPERVISOR')")
    public ResponseEntity<Map<String, Object>> bridge(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body
    ) {
        UUID tenantId = TenantContext.require().tenantId();
        TelephonyModels.CallSessionDetail row = callSessionRepository
                .findDetailByIdOrSvSession(tenantId, id)
                .orElse(null);
        if (row == null || row.svSessionUuid() == null) {
            return ResponseEntity.notFound().build();
        }
        if (row.endedAt() != null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "CALL_ENDED",
                    "message", "Cannot bridge an ended call"
            ));
        }
        String sessionId = row.svSessionUuid().toString();
        String endpoint = null;
        if (body != null && body.get("endpoint") != null) {
            String raw = String.valueOf(body.get("endpoint")).trim();
            if (!raw.isBlank()) {
                endpoint = raw;
            }
        }
        PlanRunner.ActionResult result = planRunner.forceBridge(sessionId, endpoint);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", "2");
        out.put("status", result.name());
        out.put("sessionId", sessionId);
        out.put("callSessionId", row.id().toString());
        out.put("action", "BRIDGE_SUPERVISOR");
        if (result == PlanRunner.ActionResult.FAILURE) {
            return ResponseEntity.status(502).body(out);
        }
        if (result == PlanRunner.ActionResult.UNSUPPORTED) {
            return ResponseEntity.status(501).body(out);
        }
        return ResponseEntity.ok(out);
    }

    /**
     * Operator confirmed callback checklist — unlocks transaction Approve at L3+.
     */
    @PostMapping("/{id}/confirm-callback")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','ANALYST')")
    public ResponseEntity<Map<String, Object>> confirmCallback(@PathVariable String id) {
        UUID tenantId = TenantContext.require().tenantId();
        TelephonyModels.CallSessionDetail row = callSessionRepository
                .findDetailByIdOrSvSession(tenantId, id)
                .orElse(null);
        if (row == null || row.svSessionUuid() == null) {
            return ResponseEntity.notFound().build();
        }
        if (row.endedAt() != null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "CALL_ENDED",
                    "message", "Cannot confirm callback on an ended call"
            ));
        }
        String sessionId = row.svSessionUuid().toString();
        try {
            callSessionManager.requireSession(sessionId);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "SESSION_NOT_IN_MEMORY",
                    "message", "Call session is not active in Decision Plane memory"
            ));
        }
        String actorId = TenantContext.require().userId() == null
                ? "operator"
                : TenantContext.require().userId().toString();
        transactionLockService.markCallbackVerified(sessionId, actorId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", "2");
        out.put("sessionId", sessionId);
        out.put("callSessionId", row.id().toString());
        out.put("callbackVerified", true);
        out.put("approvalLocked", false);
        return ResponseEntity.ok(out);
    }

    /**
     * Lab / operator force to a target intervention level (drives PlanRunner L3 actions).
     * Softphone ConfBridge has no AudioSocket — use this to exercise the operator workspace.
     */
    @PostMapping("/{id}/force-level")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','ANALYST')")
    public ResponseEntity<Map<String, Object>> forceLevel(
            @PathVariable String id,
            @Valid @RequestBody ForceLevelRequest request
    ) {
        UUID tenantId = TenantContext.require().tenantId();
        TelephonyModels.CallSessionDetail row = callSessionRepository
                .findDetailByIdOrSvSession(tenantId, id)
                .orElse(null);
        if (row == null || row.svSessionUuid() == null) {
            return ResponseEntity.notFound().build();
        }
        if (row.endedAt() != null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "CALL_ENDED",
                    "message", "Cannot force level on an ended call"
            ));
        }
        String sessionId = row.svSessionUuid().toString();
        CallSession session;
        try {
            session = callSessionManager.requireSession(sessionId);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "SESSION_NOT_IN_MEMORY",
                    "message", "Call session is not active in Decision Plane memory — place/answer the call first"
            ));
        }

        // Re-entering a step-up level clears any prior callback unlock.
        if (request.targetLevel().ordinal() >= InterventionLevel.LEVEL_3_STEP_UP_MFA.ordinal()) {
            transactionLockService.clear(sessionId);
        }

        InterventionLevel previous = session.getCurrentLevel();
        long nowMs = Instant.now().toEpochMilli();
        String analystId = TenantContext.require().userId() == null
                ? "operator"
                : TenantContext.require().userId().toString();

        InterventionDecision decision = interventionLadderService.override(
                sessionId,
                request.targetLevel(),
                analystId,
                request.reason().trim(),
                nowMs
        );

        callSessionManager.recordTelemetry(
                sessionId,
                new TelemetryEntry(
                        (int) session.allocateSeq(),
                        nowMs,
                        session.getSmoothedRisk(),
                        session.getSmoothedRisk(),
                        decision.level(),
                        Map.of()
                )
        );

        callSessionRepository.updatePeakLive(
                tenantId,
                row.svSessionUuid(),
                session.getSmoothedRisk(),
                decision.level().name()
        );

        try {
            sessionExplainRecorder.onOperatorLevelChange(
                    tenantId,
                    session,
                    nowMs,
                    session.getSmoothedRisk(),
                    decision.level(),
                    "OPERATOR_FORCE_LEVEL",
                    request.reason().trim()
            );
        } catch (Exception ignored) {
            // explainability must not break force-level
        }

        if (decision.changed()) {
            try {
                planRunner.onLevelChanged(sessionId, previous, decision.level());
            } catch (Exception ignored) {
                // PlanRunner never throws by contract
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", "2");
        out.put("sessionId", sessionId);
        out.put("callSessionId", row.id().toString());
        out.put("previousLevel", previous.name());
        out.put("targetLevel", decision.level().name());
        out.put("changed", decision.changed());
        out.put("actionsFired", decision.actionsToFire());
        return ResponseEntity.ok(out);
    }

    public record ForceLevelRequest(
            @NotNull InterventionLevel targetLevel,
            @NotBlank @Size(min = 10, message = "reason must be at least 10 characters")
            String reason
    ) {
    }

    private static String displayName(String fullName, String title, String fallbackNumber) {
        if (fullName != null && !fullName.isBlank()) {
            if (title != null && !title.isBlank()) {
                return fullName + " (" + title + ")";
            }
            return fullName;
        }
        return fallbackNumber == null || fallbackNumber.isBlank() ? "Unknown" : fallbackNumber;
    }
}
