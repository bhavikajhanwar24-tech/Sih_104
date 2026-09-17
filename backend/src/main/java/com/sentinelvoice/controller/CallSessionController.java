package com.sentinelvoice.controller;

import com.sentinelvoice.model.CallSession;
import com.sentinelvoice.model.InterventionLevel;
import com.sentinelvoice.service.CallSessionManager;
import com.sentinelvoice.service.FusedRiskEngineService;
import com.sentinelvoice.service.InterventionLadderService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/session")
public class CallSessionController {

    private final CallSessionManager callSessionManager;
    private final FusedRiskEngineService fusedRiskEngineService;
    private final InterventionLadderService interventionLadderService;

    public CallSessionController(
            CallSessionManager callSessionManager,
            FusedRiskEngineService fusedRiskEngineService,
            InterventionLadderService interventionLadderService) {
        this.callSessionManager = callSessionManager;
        this.fusedRiskEngineService = fusedRiskEngineService;
        this.interventionLadderService = interventionLadderService;
    }

    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> startSession(
            @RequestParam String sessionId,
            @RequestParam String callerId,
            @RequestParam String callerName,
            @RequestParam(defaultValue = "Unverified Caller") String claimedRole
    ) {
        CallSession session = callSessionManager.createSession(sessionId, callerId, callerName, claimedRole);
        return ResponseEntity.ok(Map.of(
                "status", "started",
                "sessionId", session.getSessionId(),
                "callerId", session.getCallerId(),
                "callerName", session.getCallerName(),
                "claimedRole", session.getClaimedRole(),
                "interventionLevel", session.getCurrentInterventionLevel().name()
        ));
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<Map<String, Object>> getSession(@PathVariable String sessionId) {
        CallSession session = callSessionManager.getSession(sessionId);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(Map.of(
                "sessionId", session.getSessionId(),
                "callerId", session.getCallerId(),
                "callerName", session.getCallerName(),
                "claimedRole", session.getClaimedRole(),
                "riskScore", session.getRollingRiskScore(),
                "interventionLevel", session.getCurrentInterventionLevel().name(),
                "metadata", session.getMetadata()
        ));
    }

    @PostMapping("/{sessionId}/simulate")
    public ResponseEntity<Map<String, Object>> simulate(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "0.82") double voiceAuthenticity,
            @RequestParam(defaultValue = "0.62") double channelForensics,
            @RequestParam(defaultValue = "0.70") double prosody,
            @RequestParam(defaultValue = "0.90") double nlpSignal,
            @RequestParam(defaultValue = "0.75") double transactionDeviation,
            @RequestParam(defaultValue = "0.68") double relationshipRisk
    ) {
        CallSession session = callSessionManager.getSession(sessionId);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }

        var assessment = fusedRiskEngineService.evaluate(
                sessionId,
                voiceAuthenticity,
                channelForensics,
                prosody,
                nlpSignal,
                transactionDeviation,
                relationshipRisk
        );
        InterventionLevel level = interventionLadderService.resolve(assessment.totalRisk());
        callSessionManager.updateRisk(sessionId, assessment.totalRisk(), level);

        return ResponseEntity.ok(Map.of(
                "sessionId", sessionId,
                "riskScore", assessment.totalRisk(),
                "interventionLevel", level.name(),
                "factors", assessment.factorBreakdown(),
                "explanation", assessment.explanation()
        ));
    }
}
