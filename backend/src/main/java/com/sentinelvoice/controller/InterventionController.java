package com.sentinelvoice.controller;

import com.sentinelvoice.actuation.ActuationService;
import com.sentinelvoice.intervention.InterventionDecision;
import com.sentinelvoice.intervention.InterventionLadderService;
import com.sentinelvoice.model.CallSession;
import com.sentinelvoice.model.InterventionLevel;
import com.sentinelvoice.model.TelemetryEntry;
import com.sentinelvoice.model.TelemetryFrame;
import com.sentinelvoice.service.CallSessionManager;
import com.sentinelvoice.telemetry.TelemetryBroadcaster;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Analyst intervention override API (Context §9.5). Reasons ≥ 10 chars; audited permanently.
 */
@RestController
@RequestMapping("/api/v1/intervention")
public class InterventionController {

    private final CallSessionManager callSessionManager;
    private final InterventionLadderService interventionLadderService;
    private final TelemetryBroadcaster telemetryBroadcaster;
    private final ActuationService actuationService;

    public InterventionController(
            CallSessionManager callSessionManager,
            InterventionLadderService interventionLadderService,
            TelemetryBroadcaster telemetryBroadcaster,
            ActuationService actuationService
    ) {
        this.callSessionManager = callSessionManager;
        this.interventionLadderService = interventionLadderService;
        this.telemetryBroadcaster = telemetryBroadcaster;
        this.actuationService = actuationService;
    }

    @PostMapping("/{sessionId}/override")
    public ResponseEntity<Map<String, Object>> override(
            @PathVariable String sessionId,
            @Valid @RequestBody OverrideRequest request
    ) {
        CallSession session = callSessionManager.requireSession(sessionId);
        InterventionLevel previous = session.getCurrentLevel();
        long nowMs = Instant.now().toEpochMilli();

        InterventionDecision decision = interventionLadderService.override(
                sessionId,
                request.targetLevel(),
                request.analystId(),
                request.reason(),
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

        publishOverrideTelemetry(sessionId, session, previous, decision, nowMs);

        if (decision.changed()) {
            try {
                actuationService.onLevelChanged(sessionId, previous, decision.level());
            } catch (Exception ignored) {
                // ActuationService never throws by contract; belt-and-braces.
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema", "sentinelvoice.InterventionOverride/1");
        body.put("sessionId", sessionId);
        body.put("analystId", request.analystId().trim());
        body.put("reason", request.reason().trim());
        body.put("targetLevel", decision.level().name());
        body.put("previousLevel", previous.name());
        body.put("tsEpochMs", nowMs);
        body.put("changed", decision.changed());
        body.put("actionsFired", decision.actionsToFire());
        body.put("rationale", decision.rationale());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/{sessionId}/release")
    public ResponseEntity<Map<String, Object>> releaseHold(
            @PathVariable String sessionId,
            @Valid @RequestBody ReleaseRequest request
    ) {
        // Supervisor "Release" → audited down-step toward L2 (soft nudge) with mandatory reason.
        OverrideRequest override = new OverrideRequest(
                request.analystId(),
                request.reason(),
                InterventionLevel.LEVEL_2_SOFT_NUDGE
        );
        return this.override(sessionId, override);
    }

    private void publishOverrideTelemetry(
            String sessionId,
            CallSession session,
            InterventionLevel previous,
            InterventionDecision decision,
            long nowMs
    ) {
        TelemetryFrame latest = telemetryBroadcaster.latest(sessionId).orElse(null);
        long callElapsedMs = Math.max(0L, nowMs - session.getCreatedAt().toEpochMilli());
        TelemetryFrame.Intervention intervention = new TelemetryFrame.Intervention(
                decision.level().name(),
                previous.name(),
                nowMs,
                0L,
                new TelemetryFrame.ManualOverride(
                        "analyst",
                        decision.rationale() == null ? "override" : decision.rationale(),
                        decision.level().name()
                ),
                decision.actionsToFire() == null ? List.of() : decision.actionsToFire()
        );

        if (latest != null) {
            telemetryBroadcaster.publish(new TelemetryFrame(
                    TelemetryFrame.SCHEMA,
                    sessionId,
                    latest.seq() + 1,
                    nowMs,
                    callElapsedMs,
                    latest.risk(),
                    latest.families(),
                    latest.corroboration(),
                    intervention,
                    latest.identity(),
                    latest.topReasons(),
                    latest.transcriptDelta(),
                    latest.auditHash()
            ));
            return;
        }

        TelemetryFrame.FamilyScore unavailable = new TelemetryFrame.FamilyScore(0, 0, 0, false);
        telemetryBroadcaster.publish(new TelemetryFrame(
                TelemetryFrame.SCHEMA,
                sessionId,
                0,
                nowMs,
                callElapsedMs,
                new TelemetryFrame.Risk(session.getSmoothedRisk(), session.getSmoothedRisk(), "STABLE", "SCORED"),
                new TelemetryFrame.Families(
                        unavailable, unavailable, unavailable, unavailable, unavailable, unavailable
                ),
                new TelemetryFrame.Corroboration(List.of(), 2, false),
                intervention,
                new TelemetryFrame.Identity(
                        session.getCallerId(),
                        "UNKNOWN",
                        null,
                        null,
                        null,
                        null,
                        false,
                        new TelemetryFrame.VoicePassport(false, 0.0, "INCONCLUSIVE"),
                        null
                ),
                List.of(),
                new TelemetryFrame.TranscriptDelta(callElapsedMs, "", List.of()),
                "override"
        ));
    }

    public record OverrideRequest(
            @NotBlank String analystId,
            @NotBlank @Size(min = 10, message = "override reason must be at least 10 characters")
            String reason,
            @NotNull InterventionLevel targetLevel
    ) {
    }

    public record ReleaseRequest(
            @NotBlank String analystId,
            @NotBlank @Size(min = 10, message = "override reason must be at least 10 characters")
            String reason
    ) {
    }
}
