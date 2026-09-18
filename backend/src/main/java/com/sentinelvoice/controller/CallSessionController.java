package com.sentinelvoice.controller;

import com.sentinelvoice.model.CallSession;
import com.sentinelvoice.model.InterventionLevel;
import com.sentinelvoice.model.LinguisticAssessment;
import com.sentinelvoice.model.LinguisticFamily;
import com.sentinelvoice.model.RelationshipAssessment;
import com.sentinelvoice.model.RelationshipQuery;
import com.sentinelvoice.model.SessionStartRequest;
import com.sentinelvoice.model.TelemetryEntry;
import com.sentinelvoice.service.CallSessionManager;
import com.sentinelvoice.service.FusedRiskEngineService;
import com.sentinelvoice.service.InterventionLadderService;
import com.sentinelvoice.service.NaturalLanguageFraudService;
import com.sentinelvoice.service.RelationshipGraphService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/session")
public class CallSessionController {

    private final CallSessionManager callSessionManager;
    private final FusedRiskEngineService fusedRiskEngineService;
    private final InterventionLadderService interventionLadderService;
    private final NaturalLanguageFraudService naturalLanguageFraudService;
    private final RelationshipGraphService relationshipGraphService;

    public CallSessionController(
            CallSessionManager callSessionManager,
            FusedRiskEngineService fusedRiskEngineService,
            InterventionLadderService interventionLadderService,
            NaturalLanguageFraudService naturalLanguageFraudService,
            RelationshipGraphService relationshipGraphService
    ) {
        this.callSessionManager = callSessionManager;
        this.fusedRiskEngineService = fusedRiskEngineService;
        this.interventionLadderService = interventionLadderService;
        this.naturalLanguageFraudService = naturalLanguageFraudService;
        this.relationshipGraphService = relationshipGraphService;
    }

    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> startSession(@Valid @RequestBody SessionStartRequest request) {
        CallSession session = callSessionManager.createSession(request);
        return ResponseEntity.ok(descriptor(session, "started"));
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<Map<String, Object>> getSession(@PathVariable String sessionId) {
        return callSessionManager.getSession(sessionId)
                .map(session -> ResponseEntity.ok(descriptor(session, "ok")))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{sessionId}/simulate")
    public ResponseEntity<Map<String, Object>> simulate(
            @PathVariable String sessionId,
            @RequestParam double voiceAuthenticity,
            @RequestParam double channelForensics,
            @RequestParam double prosody,
            @RequestParam double nlpSignal,
            @RequestParam double transactionDeviation
    ) {
        CallSession session = callSessionManager.requireSession(sessionId);
        LinguisticAssessment linguistic = naturalLanguageFraudService.assess(
                new LinguisticFamily(
                        true,
                        0L,
                        "en",
                        nlpSignal,
                        nlpSignal,
                        nlpSignal,
                        nlpSignal,
                        false,
                        null,
                        null,
                        null,
                        ""
                )
        );
        RelationshipAssessment relationship = relationshipGraphService.assess(
                new RelationshipQuery(session.getCallerId(), session.getCalleeId(), null)
        );
        var assessment = fusedRiskEngineService.evaluate(
                sessionId,
                voiceAuthenticity,
                channelForensics,
                prosody,
                linguistic.composite(),
                transactionDeviation,
                relationship.score()
        );
        InterventionLevel level = interventionLadderService.resolve(assessment.totalRisk());
        TelemetryEntry entry = new TelemetryEntry(
                session.allocateSeq(),
                Instant.now().toEpochMilli(),
                assessment.totalRisk(),
                assessment.totalRisk(),
                level,
                assessment.factorBreakdown()
        );
        callSessionManager.recordTelemetry(sessionId, entry);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("riskScore", assessment.totalRisk());
        body.put("interventionLevel", level.name());
        body.put("factors", assessment.factorBreakdown());
        body.put("explanation", assessment.explanation());
        body.put("linguistic", linguistic);
        body.put("relationship", relationship);
        return ResponseEntity.ok(body);
    }

    private static Map<String, Object> descriptor(CallSession session, String status) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("sessionId", session.getSessionId());
        body.put("callerId", session.getCallerId());
        body.put("calleeId", session.getCalleeId());
        body.put("channelProfile", session.getChannelProfile().name());
        body.put("state", session.getState().name());
        body.put("smoothedRisk", session.getSmoothedRisk());
        body.put("interventionLevel", session.getCurrentLevel().name());
        body.put("createdAt", session.getCreatedAt().toString());
        body.put("lastFrameAt", session.getLastFrameAt().toString());
        body.put("cumulativeSpeechMs", session.getCumulativeSpeechMs());
        body.put("metadata", session.getMetadata());
        if (session.getScenarioId() != null) {
            body.put("scenarioId", session.getScenarioId());
        }
        return body;
    }
}
