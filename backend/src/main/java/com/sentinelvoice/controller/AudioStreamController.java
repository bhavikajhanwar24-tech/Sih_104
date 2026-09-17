package com.sentinelvoice.controller;

import com.sentinelvoice.model.AudioChunkDTO;
import com.sentinelvoice.service.CallSessionManager;
import com.sentinelvoice.service.FusedRiskEngineService;
import com.sentinelvoice.service.InterventionLadderService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class AudioStreamController {

    private final CallSessionManager callSessionManager;
    private final FusedRiskEngineService fusedRiskEngineService;
    private final InterventionLadderService interventionLadderService;

    public AudioStreamController(
            CallSessionManager callSessionManager,
            FusedRiskEngineService fusedRiskEngineService,
            InterventionLadderService interventionLadderService) {
        this.callSessionManager = callSessionManager;
        this.fusedRiskEngineService = fusedRiskEngineService;
        this.interventionLadderService = interventionLadderService;
    }

    @PostMapping("/stream/{sessionId}/audio")
    public ResponseEntity<Map<String, Object>> receiveAudio(
            @PathVariable String sessionId,
            @RequestBody AudioChunkDTO chunk
    ) {
        if (callSessionManager.getSession(sessionId) == null) {
            return ResponseEntity.badRequest().body(Map.of("status", "session_not_found"));
        }

        callSessionManager.appendAudio(sessionId, chunk.toBytes());

        double syntheticLikelihood = 0.68;
        double score = fusedRiskEngineService.evaluate(sessionId, syntheticLikelihood, 0.5, 0.6, 0.75, 0.7, 0.6).totalRisk();
        var level = interventionLadderService.resolve(score);
        callSessionManager.updateRisk(sessionId, score, level);

        return ResponseEntity.ok(Map.of(
                "status", "received",
                "sessionId", sessionId,
                "riskScore", score,
                "interventionLevel", level.name()
        ));
    }
}
