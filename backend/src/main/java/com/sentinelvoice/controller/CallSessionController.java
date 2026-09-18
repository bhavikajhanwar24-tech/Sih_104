package com.sentinelvoice.controller;

import com.sentinelvoice.context.RelationshipGraphService;
import com.sentinelvoice.context.TransactionPolicyService;
import com.sentinelvoice.context.model.RelationshipAssessment;
import com.sentinelvoice.context.model.TransactionAssessment;
import com.sentinelvoice.fusion.FusionContext;
import com.sentinelvoice.fusion.FusionEngineService;
import com.sentinelvoice.fusion.FusionResult;
import com.sentinelvoice.identity.DirectoryService;
import com.sentinelvoice.identity.IdentityResolutionService;
import com.sentinelvoice.identity.model.DirectoryRecord;
import com.sentinelvoice.identity.model.IdentityAssessment;
import com.sentinelvoice.intervention.InterventionDecision;
import com.sentinelvoice.intervention.InterventionLadderService;
import com.sentinelvoice.intervention.InterventionStateMachine;
import com.sentinelvoice.model.CallSession;
import com.sentinelvoice.model.ChannelProfile;
import com.sentinelvoice.model.FeatureFrame;
import com.sentinelvoice.model.InterventionLevel;
import com.sentinelvoice.model.LinguisticAssessment;
import com.sentinelvoice.model.LinguisticFamily;
import com.sentinelvoice.model.RelationshipQuery;
import com.sentinelvoice.model.SessionStartRequest;
import com.sentinelvoice.model.TelemetryEntry;
import com.sentinelvoice.model.TelemetryFrame;
import com.sentinelvoice.service.CallSessionManager;
import com.sentinelvoice.service.NaturalLanguageFraudService;
import com.sentinelvoice.telemetry.TelemetryBroadcaster;
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
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/session")
public class CallSessionController {

    private final CallSessionManager callSessionManager;
    private final FusionEngineService fusionEngineService;
    private final InterventionLadderService interventionLadderService;
    private final NaturalLanguageFraudService naturalLanguageFraudService;
    private final RelationshipGraphService relationshipGraphService;
    private final TransactionPolicyService transactionPolicyService;
    private final IdentityResolutionService identityResolutionService;
    private final DirectoryService directoryService;
    private final TelemetryBroadcaster telemetryBroadcaster;

    public CallSessionController(
            CallSessionManager callSessionManager,
            FusionEngineService fusionEngineService,
            InterventionLadderService interventionLadderService,
            NaturalLanguageFraudService naturalLanguageFraudService,
            RelationshipGraphService relationshipGraphService,
            TransactionPolicyService transactionPolicyService,
            IdentityResolutionService identityResolutionService,
            DirectoryService directoryService,
            TelemetryBroadcaster telemetryBroadcaster
    ) {
        this.callSessionManager = callSessionManager;
        this.fusionEngineService = fusionEngineService;
        this.interventionLadderService = interventionLadderService;
        this.naturalLanguageFraudService = naturalLanguageFraudService;
        this.relationshipGraphService = relationshipGraphService;
        this.transactionPolicyService = transactionPolicyService;
        this.identityResolutionService = identityResolutionService;
        this.directoryService = directoryService;
        this.telemetryBroadcaster = telemetryBroadcaster;
    }

    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> startSession(@Valid @RequestBody SessionStartRequest request) {
        CallSession session = callSessionManager.createSession(request);
        return ResponseEntity.ok(descriptor(session, "started"));
    }

    /**
     * List active Decision Plane sessions (newest first). Analyst uses this to attach
     * to a SIP/AudioSocket session opened by the gateway bridge.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> listSessions() {
        List<Map<String, Object>> items = callSessionManager.listSessions().stream()
                .map(session -> descriptor(session, "ok"))
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessions", items);
        body.put("count", items.size());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<Map<String, Object>> getSession(@PathVariable String sessionId) {
        return callSessionManager.getSession(sessionId)
                .map(session -> ResponseEntity.ok(descriptor(session, "ok")))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * REST fallback for the last published TelemetryFrame — debug aid when STOMP misbehaves.
     */
    @GetMapping("/{sessionId}/telemetry/latest")
    public ResponseEntity<TelemetryFrame> latestTelemetry(@PathVariable String sessionId) {
        if (callSessionManager.getSession(sessionId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return telemetryBroadcaster.latest(sessionId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{sessionId}/close")
    public ResponseEntity<Map<String, Object>> closeSession(@PathVariable String sessionId) {
        if (callSessionManager.getSession(sessionId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        callSessionManager.closeSession(sessionId);
        telemetryBroadcaster.clear(sessionId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "closed");
        body.put("sessionId", sessionId);
        return ResponseEntity.ok(body);
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
        LinguisticFamily linguisticFamily = new LinguisticFamily(
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
        );
        LinguisticAssessment linguistic = naturalLanguageFraudService.assess(linguisticFamily);
        RelationshipAssessment relationship = relationshipGraphService.assess(
                new RelationshipQuery(session.getCallerId(), session.getCalleeId(), null)
        );

        FeatureFrame frame = new FeatureFrame(
                "sentinelvoice.FeatureFrame/1",
                sessionId,
                (int) session.allocateSeq(),
                0L,
                500L,
                session.getChannelProfile() != null ? session.getChannelProfile() : ChannelProfile.WEBRTC_WIDEBAND,
                true,
                Math.max(session.getCumulativeSpeechMs(), 5000L),
                new FeatureFrame.VoiceFamily(true, voiceAuthenticity, "sim", Double.valueOf(1)),
                new FeatureFrame.ChannelFamily(
                        true, Double.valueOf(50), Boolean.TRUE, channelForensics, Double.valueOf(1) / 2, Double.valueOf(0)),
                new FeatureFrame.ProsodyFamily(
                        true,
                        Double.valueOf(120),
                        Double.valueOf(10),
                        Double.valueOf(1) / 2,
                        Double.valueOf(5),
                        Double.valueOf(20),
                        Double.valueOf(10),
                        Double.valueOf(0),
                        Double.valueOf(4),
                        prosody),
                new FeatureFrame.SpeakerFamily(false, null, null, null, null),
                new FeatureFrame.WatermarkFamily(false, null, null, null, null),
                linguisticFamily,
                new FeatureFrame.LatencyMs(50, 200)
        );

        IdentityAssessment identity = identityResolutionService.resolve(session, frame);
        DirectoryRecord claimed = null;
        if (identity.directoryRecordForClaim() != null
                && identity.directoryRecordForClaim().get("employeeId") instanceof String empId) {
            claimed = directoryService.findByEmployeeId(empId).orElse(null);
        }
        TransactionAssessment transaction = transactionPolicyService.assess(frame, claimed);
        double txnScore = Math.max(transactionDeviation, transaction.score());
        FusionContext fusionContext = FusionContext.withIdentity(
                frame,
                txnScore,
                true,
                relationship.score(),
                true,
                identity
        );
        FusionResult fusion = fusionEngineService.evaluate(sessionId, fusionContext);
        List<String> corroborating = fusion.corroboration().familiesAboveThreshold().stream()
                .map(f -> f.configKey())
                .toList();
        InterventionDecision decision = interventionLadderService.evaluate(
                sessionId,
                new InterventionStateMachine.EvaluationInput(
                        fusion.smoothed(),
                        fusion.corroboration().satisfied(),
                        corroborating,
                        fusion.emergencyReason() != null,
                        false,
                        Instant.now().toEpochMilli()
                )
        );
        InterventionLevel level = decision.level();

        Map<String, Double> factorBreakdown = new LinkedHashMap<>();
        fusion.families().forEach((family, score) ->
                factorBreakdown.put(family.configKey(), score.available() ? score.score() : 0));

        TelemetryEntry entry = new TelemetryEntry(
                frame.seq(),
                Instant.now().toEpochMilli(),
                fusion.instantaneous(),
                fusion.smoothed(),
                level,
                factorBreakdown
        );
        callSessionManager.recordTelemetry(sessionId, entry);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("riskScore", fusion.smoothed());
        body.put("instantaneousRisk", fusion.instantaneous());
        body.put("interventionLevel", level.name());
        body.put("interventionChanged", decision.changed());
        body.put("dwellRemainingMs", decision.dwellRemainingMs());
        body.put("rationale", decision.rationale());
        body.put("factors", factorBreakdown);
        body.put("state", fusion.state().name());
        body.put("corroboration", fusion.corroboration().satisfied());
        body.put("linguistic", linguistic);
        body.put("relationship", relationship);
        body.put("transaction", transaction);
        body.put("identity", identity);
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
