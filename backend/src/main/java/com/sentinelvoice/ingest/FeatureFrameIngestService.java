package com.sentinelvoice.ingest;

import com.sentinelvoice.response.execute.PlanRunner;
import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.audit.AuditWriteDispatcher;
import com.sentinelvoice.challenge.ChallengeService;
import com.sentinelvoice.challenge.model.ChallengeVerdict;
import com.sentinelvoice.config.SentinelProperties;
import com.sentinelvoice.context.CrossChannelCorrelationService;
import com.sentinelvoice.context.RelationshipGraphService;
import com.sentinelvoice.context.model.CorrelationResult;
import com.sentinelvoice.context.model.RelationshipAssessment;
import com.sentinelvoice.explain.SessionExplainRecorder;
import com.sentinelvoice.directory.DirectoryMatch;
import com.sentinelvoice.directory.DirectoryService;
import com.sentinelvoice.fusion.EvidenceFamily;
import com.sentinelvoice.fusion.FusionContext;
import com.sentinelvoice.fusion.FusionResult;
import com.sentinelvoice.fusion.ReasonCode;
import com.sentinelvoice.fusion.ReasonGenerator;
import com.sentinelvoice.fusion.config.FusionConfigDocument;
import com.sentinelvoice.fusion.engine.FusionRuntimeService;
import com.sentinelvoice.fusion.engine.FusionTickInputs;
import com.sentinelvoice.fusion.engine.RiskAssessment;
import com.sentinelvoice.governance.EmergencyModeService;
import com.sentinelvoice.identity.IdentityResolutionService;
import com.sentinelvoice.identity.model.IdentityAssessment;
import com.sentinelvoice.intervention.InterventionDecision;
import com.sentinelvoice.model.CallSession;
import com.sentinelvoice.model.ChannelProfile;
import com.sentinelvoice.model.FeatureFrame;
import com.sentinelvoice.model.InterventionLevel;
import com.sentinelvoice.model.RelationshipQuery;
import com.sentinelvoice.model.SessionStartRequest;
import com.sentinelvoice.model.TelemetryEntry;
import com.sentinelvoice.model.TelemetryFrame;
import com.sentinelvoice.policy.engine.PolicyRuntimeService;
import com.sentinelvoice.policy.engine.RuleEvaluation;
import com.sentinelvoice.scenario.ScenarioSessionContext;
import com.sentinelvoice.security.TenantContext;
import com.sentinelvoice.service.CallSessionManager;
import com.sentinelvoice.telephony.CallSessionRepository;
import com.sentinelvoice.telephony.TelephonyModels;
import com.sentinelvoice.telephony.LiveCallsBroadcaster;
import com.sentinelvoice.telemetry.TelemetryBroadcaster;
import com.sentinelvoice.telemetry.TelemetryFrameBuilder;
import com.sentinelvoice.transcript.BreakGlassTranscriptService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-frame Decision Plane pipeline (P5.4 / F8):
 * validate → update session → fuse → reasons → async audit → TelemetryFrame → STOMP.
 */
@Service
public class FeatureFrameIngestService {

    private static final Logger log = LoggerFactory.getLogger(FeatureFrameIngestService.class);
    private static final long EPOCH_MS_THRESHOLD = 1_000_000_000_000L;

    private final CallSessionManager callSessionManager;
    private final CallSessionRepository callSessionRepository;
    private final SentinelProperties properties;
    private final FusionRuntimeService fusionRuntimeService;
    private final ReasonGenerator reasonGenerator;
    private final IdentityResolutionService identityResolutionService;
    private final RelationshipGraphService relationshipGraphService;
    private final PolicyRuntimeService policyRuntimeService;
    private final CrossChannelCorrelationService crossChannelCorrelationService;
    private final DirectoryService directoryService;
    private final AuditWriteDispatcher auditWriteDispatcher;
    private final TelemetryFrameBuilder telemetryFrameBuilder;
    private final TelemetryBroadcaster telemetryBroadcaster;
    private final LiveCallsBroadcaster liveCallsBroadcaster;
    private final PlanRunner planRunner;
    private final ChallengeService challengeService;
    private final ScenarioSessionContext scenarioSessionContext;
    private final BreakGlassTranscriptService breakGlassTranscriptService;
    private final SessionExplainRecorder sessionExplainRecorder;
    private final EmergencyModeService emergencyModeService;
    private final Clock clock;
    private final Counter received;
    private final Counter dropped;
    private final Counter stale;
    private final Timer pipelineTimer;
    private final Map<String, AtomicReference<FeatureFrame>> pendingFrames = new ConcurrentHashMap<>();
    private final Set<String> draining = ConcurrentHashMap.newKeySet();
    private Executor pipelineExecutor = Executors.newFixedThreadPool(4, runnable -> {
        Thread t = new Thread(runnable, "frame-pipeline");
        t.setDaemon(true);
        return t;
    });

    public FeatureFrameIngestService(
            CallSessionManager callSessionManager,
            CallSessionRepository callSessionRepository,
            SentinelProperties properties,
            FusionRuntimeService fusionRuntimeService,
            ReasonGenerator reasonGenerator,
            IdentityResolutionService identityResolutionService,
            RelationshipGraphService relationshipGraphService,
            PolicyRuntimeService policyRuntimeService,
            CrossChannelCorrelationService crossChannelCorrelationService,
            DirectoryService directoryService,
            AuditWriteDispatcher auditWriteDispatcher,
            TelemetryFrameBuilder telemetryFrameBuilder,
            TelemetryBroadcaster telemetryBroadcaster,
            @Lazy LiveCallsBroadcaster liveCallsBroadcaster,
            @Lazy PlanRunner planRunner,
            ChallengeService challengeService,
            ScenarioSessionContext scenarioSessionContext,
            BreakGlassTranscriptService breakGlassTranscriptService,
            SessionExplainRecorder sessionExplainRecorder,
            EmergencyModeService emergencyModeService,
            MeterRegistry meterRegistry,
            Clock clock
    ) {
        this.callSessionManager = callSessionManager;
        this.callSessionRepository = callSessionRepository;
        this.properties = properties;
        this.fusionRuntimeService = fusionRuntimeService;
        this.reasonGenerator = reasonGenerator;
        this.identityResolutionService = identityResolutionService;
        this.relationshipGraphService = relationshipGraphService;
        this.policyRuntimeService = policyRuntimeService;
        this.crossChannelCorrelationService = crossChannelCorrelationService;
        this.directoryService = directoryService;
        this.auditWriteDispatcher = auditWriteDispatcher;
        this.telemetryFrameBuilder = telemetryFrameBuilder;
        this.telemetryBroadcaster = telemetryBroadcaster;
        this.liveCallsBroadcaster = liveCallsBroadcaster;
        this.planRunner = planRunner;
        this.challengeService = challengeService;
        this.scenarioSessionContext = scenarioSessionContext;
        this.breakGlassTranscriptService = breakGlassTranscriptService;
        this.sessionExplainRecorder = sessionExplainRecorder;
        this.emergencyModeService = emergencyModeService;
        this.clock = clock;
        this.received = Counter.builder("sentinel.frames.received")
                .description("FeatureFrames accepted into a CallSession")
                .register(meterRegistry);
        this.dropped = Counter.builder("sentinel.frames.dropped")
                .description("FeatureFrames dropped (unknown session, out of order, invalid)")
                .register(meterRegistry);
        this.stale = Counter.builder("sentinel.frames.stale")
                .description("FeatureFrames dropped for exceeding frameStalenessMs")
                .register(meterRegistry);
        this.pipelineTimer = Timer.builder("sentinel.pipeline.latency")
                .description("FeatureFrame ingest pipeline latency (fuse→FSM→reasons→broadcast); p95 < 15ms")
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    public void rejectInvalid(Exception cause) {
        dropped.increment();
        log.warn("feature_frame_drop reason=invalid cause={}", cause.getClass().getSimpleName());
    }

    public void ingest(FeatureFrame frame) {
        if (missingSessionId(frame)) {
            dropped.increment();
            throw new FrameValidationException("feature frame missing sessionId");
        }
        Optional<CallSession> existing = callSessionManager.getSession(frame.sessionId());
        if (existing.isEmpty()) {
            existing = reviveMemorySession(frame.sessionId());
        }
        if (existing.isEmpty()) {
            dropped.increment();
            log.warn("feature_frame_drop reason=unknown_session sessionId={} seq={}", frame.sessionId(), frame.seq());
            return;
        }
        CallSession session = existing.get();
        if (session.getTenantId() != null && emergencyModeService.isSuspended(session.getTenantId())) {
            // F14 SUSPEND_MONITORING — calls continue; Decision Plane stops analysing.
            log.debug("feature_frame_skip_suspended sessionId={} tenantId={}", frame.sessionId(), session.getTenantId());
            return;
        }
        TenantContext.runAs(session.getTenantId(), () -> {
            ingestUnderTenant(session, frame);
            return null;
        });
    }

    /**
     * After a Decision Plane restart, FeatureFrames still arrive with the AudioSocket UUID
     * but in-memory CallSession is gone — rebuild from call_sessions so Live Calls keeps
     * receiving keywords / broken rules / LLM thinking.
     */
    private Optional<CallSession> reviveMemorySession(String sessionId) {
        UUID sv;
        try {
            sv = UUID.fromString(sessionId);
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
        Optional<TelephonyModels.CallSessionView> row = callSessionRepository.findBySvSessionAnyTenant(sv);
        if (row.isEmpty() || row.get().endedAt() != null) {
            return Optional.empty();
        }
        TelephonyModels.CallSessionView call = row.get();
        try {
            return TenantContext.runAs(call.tenantId(), () -> {
                try {
                    CallSession created = callSessionManager.createSession(new SessionStartRequest(
                            "sentinelvoice.SessionStartRequest/1",
                            sessionId,
                            call.callerNumber() == null || call.callerNumber().isBlank()
                                    ? "sip-caller" : call.callerNumber(),
                            call.calleeNumber() == null || call.calleeNumber().isBlank()
                                    ? "sip-agent" : call.calleeNumber(),
                            ChannelProfile.PSTN_NARROWBAND,
                            "telephony-revive"
                    ));
                    log.info(
                            "session_revived_from_telephony sessionId={} tenantId={} callSessionId={}",
                            sessionId,
                            call.tenantId(),
                            call.id()
                    );
                    return Optional.of(created);
                } catch (IllegalArgumentException already) {
                    return callSessionManager.getSession(sessionId);
                } catch (RuntimeException ex) {
                    log.warn(
                            "session_revive_failed sessionId={} cause={}",
                            sessionId,
                            ex.toString()
                    );
                    return Optional.empty();
                }
            });
        } catch (RuntimeException ex) {
            log.warn("session_revive_failed sessionId={} cause={}", sessionId, ex.toString());
            return Optional.empty();
        }
    }

    private void ingestUnderTenant(CallSession session, FeatureFrame frame) {
        // Live Calls "More info" must survive every drop gate below: the Decision Plane pipeline
        // runs against a remote DB and lags seconds behind ml-engine, so the frame that finally
        // carries the LLM judgment is usually discarded as out_of_order/stale.
        applyLiveExplainMeta(session, frame);
        if (frame.seq() <= session.getLastFeatureSeq()) {
            dropped.increment();
            log.warn(
                    "feature_frame_drop reason=out_of_order sessionId={} seq={} lastSeq={}",
                    frame.sessionId(),
                    frame.seq(),
                    session.getLastFeatureSeq()
            );
            return;
        }
        long ageMs = frameAgeMs(session, frame);
        int stalenessMs = properties.ml().frameStalenessMs();
        if (ageMs > stalenessMs) {
            stale.increment();
            log.warn(
                    "feature_frame_drop reason=stale sessionId={} seq={} ageMs={} stalenessMs={}",
                    frame.sessionId(),
                    frame.seq(),
                    ageMs,
                    stalenessMs
            );
            return;
        }

        session.storeFeatureFrame(frame);
        received.increment();

        submitPipeline(session, frame);
    }

    private void applyLiveExplainMeta(CallSession session, FeatureFrame frame) {
        if (frame.linguistic() == null) {
            return;
        }
        session.recordLinguisticMeta(frame.linguistic());
        List<String> ruleIds = frame.linguistic().matchedRuleIds();
        if (ruleIds != null && !ruleIds.isEmpty()) {
            session.recordBrokenRules(ruleIds, List.of());
        }
        // Captions / LLM / keyword rules must reach Live Calls even when this frame is
        // later dropped as stale or out-of-order (pipeline lag behind ASR).
        try {
            liveCallsBroadcaster.publishSessionDelta(session);
        } catch (RuntimeException ex) {
            log.debug(
                    "live_calls_linguistic_delta_failed sessionId={} cause={}",
                    session.getSessionId(),
                    ex.toString()
            );
        }
    }

    /**
     * Hand the heavy pipeline to a worker and keep only the newest frame per session. The WS
     * thread must never block on it, otherwise ml-engine's send buffer fills, the socket is
     * aborted (1006) and the reconnect replays an old seq that we then drop as out_of_order.
     */
    private void submitPipeline(CallSession session, FeatureFrame frame) {
        String sessionId = session.getSessionId();
        pendingFrames.computeIfAbsent(sessionId, k -> new AtomicReference<>()).set(frame);
        if (!draining.add(sessionId)) {
            return;
        }
        pipelineExecutor.execute(() -> {
            try {
                AtomicReference<FeatureFrame> slot = pendingFrames.get(sessionId);
                FeatureFrame next;
                while (slot != null && (next = slot.getAndSet(null)) != null) {
                    runPipelineGuarded(session, next);
                }
            } finally {
                draining.remove(sessionId);
            }
        });
    }

    /** Tests run the pipeline inline so every frame is measured, not coalesced. */
    void setPipelineExecutor(Executor executor) {
        this.pipelineExecutor = executor;
    }

    private void runPipelineGuarded(CallSession session, FeatureFrame frame) {
        Timer.Sample sample = Timer.start();
        try {
            TenantContext.runAs(session.getTenantId(), () -> {
                runPipeline(session, frame);
                return null;
            });
        } catch (Exception ex) {
            log.error(
                    "pipeline_failed sessionId={} seq={} cause={}",
                    frame.sessionId(),
                    frame.seq(),
                    ex.toString(),
                    ex
            );
            try {
                TenantContext.runAs(session.getTenantId(), () -> {
                    telemetryBroadcaster.publish(
                            telemetryFrameBuilder.buildDegraded(session, frame, nowMs(), ex.getMessage())
                    );
                    return null;
                });
            } catch (Exception broadcastEx) {
                log.error(
                        "degraded_broadcast_failed sessionId={} seq={}",
                        frame.sessionId(),
                        frame.seq(),
                        broadcastEx
                );
            }
        } finally {
            sample.stop(pipelineTimer);
        }
    }

    private void runPipeline(CallSession session, FeatureFrame frame) {
        long nowMs = nowMs();
        InterventionLevel previousLevel = session.getCurrentLevel();

        IdentityAssessment identity = identityResolutionService.resolve(session, frame);
        FeatureFrame working = scenarioSessionContext.enrich(frame);
        RelationshipAssessment relationship = relationshipGraphService.assess(
                new RelationshipQuery(session.getCallerId(), session.getCalleeId(), null)
        );
        CorrelationResult crossChannel = crossChannelCorrelationService.correlateSession(
                session.getSessionId(), null
        );
        double relationshipScore = crossChannelCorrelationService.blendRelationshipScore(
                relationship.score(), crossChannel
        );

        String claimedRole = working.linguistic() != null ? working.linguistic().claimedRole() : null;
        String claimedName = working.linguistic() != null ? working.linguistic().claimedIdentity() : null;
        DirectoryMatch directoryMatch = directoryService.resolve(
                session.getTenantId(), session.getCallerId(), claimedName, claimedRole
        );

        RuleEvaluation policyEval = policyRuntimeService.evaluateLive(
                session.getTenantId(), session, working, directoryMatch, relationship
        );
        double transactionScore = RuleEvaluation.STATE_NO_POLICY.equals(policyEval.state())
                ? 0.0
                : policyEval.policyScore();
        boolean transactionAvailable = !RuleEvaluation.STATE_NO_POLICY.equals(policyEval.state());

        FusionContext fusionContext = FusionContext.withIdentity(
                working,
                transactionScore,
                transactionAvailable,
                relationshipScore,
                true,
                identity
        );

        boolean challengeEmergency = challengeService.lastFailure(session.getSessionId())
                .filter(f -> nowMs - f.atEpochMs() < 60_000L)
                .isPresent();

        double cosineMismatch = 0.0;
        if (working.speaker() != null
                && working.speaker().available()
                && working.speaker().cosineSimilarity() != null) {
            cosineMismatch = Math.max(0.0, 1.0 - working.speaker().cosineSimilarity());
        }
        double secrecy = working.linguistic() != null && working.linguistic().secrecy() != null
                ? working.linguistic().secrecy() : 0.0;
        double authority = working.linguistic() != null && working.linguistic().authorityInvocation() != null
                ? working.linguistic().authorityInvocation() : 0.0;

        FusionConfigDocument config = fusionRuntimeService.resolveConfig(session);
        Integer fusionVersion = fusionRuntimeService.resolveFusionVersion(session);
        Integer policyVersion = session.getPolicyVersion() != null
                ? session.getPolicyVersion()
                : policyEval.policyVersion();

        FusionTickInputs inputs = FusionRuntimeService.buildInputs(
                working,
                transactionScore,
                transactionAvailable,
                relationshipScore,
                true,
                policyEval,
                cosineMismatch,
                secrecy,
                authority,
                challengeEmergency,
                nowMs
        );

        FusionRuntimeService.EvaluationResult eval = fusionRuntimeService.evaluate(
                session, config, inputs, fusionVersion, policyVersion
        );
        FusionResult fusion = eval.fusionResult();
        InterventionDecision decision = eval.decision();
        RiskAssessment assessment = eval.assessment();

        if (working.linguistic() != null) {
            session.recordLinguisticMeta(working.linguistic());
        }
        decision = applyLlmUnavailablePolicy(session, config, working, decision);

        List<ReasonGenerator.GeneratedReason> reasons = reasonGenerator.generate(
                fusionContext,
                fusion.families(),
                challengeAssessments(session.getSessionId(), relationship, identity, crossChannel),
                config
        );
        // Prepend engine reasons (policy floor / emergency) so TelemetryFrame surfaces them
        if (!assessment.reasons().isEmpty()) {
            List<ReasonGenerator.GeneratedReason> merged = new ArrayList<>();
            List<RuleEvaluation.FiredRule> fired = policyEval.firedRules();
            int fireIdx = 0;
            for (RiskAssessment.Reason r : assessment.reasons()) {
                RuleEvaluation.FiredRule match = null;
                if ("POLICY_FLOOR".equals(r.code()) && fireIdx < fired.size()) {
                    match = fired.get(fireIdx++);
                } else if (!fired.isEmpty()) {
                    for (RuleEvaluation.FiredRule fr : fired) {
                        if (fr.ruleId() != null && r.text() != null && r.text().contains(fr.ruleId())) {
                            match = fr;
                            break;
                        }
                    }
                }
                Map<String, Object> sourceClause = new LinkedHashMap<>();
                String ruleId = null;
                Integer polVer = policyVersion;
                if (match != null) {
                    ruleId = match.ruleId();
                    if (match.sourceRef() != null) {
                        sourceClause.putAll(match.sourceRef());
                    }
                    if (match.title() != null && !match.title().isBlank()) {
                        sourceClause.putIfAbsent("title", match.title());
                    }
                }
                merged.add(new ReasonGenerator.GeneratedReason(
                        ReasonCode.POLICY_RULE_FIRED,
                        "Policy rule fired",
                        r.text(),
                        EvidenceFamily.TRANSACTION,
                        1.0,
                        ruleId,
                        polVer,
                        sourceClause.isEmpty() ? null : sourceClause,
                        Map.of()
                ));
            }
            merged.addAll(reasons);
            reasons = merged.stream().limit(8).toList();
        }

        List<CallSession.FiredReason> fired = new ArrayList<>(reasons.size());
        for (ReasonGenerator.GeneratedReason r : reasons) {
            if (r == null || r.code() == null) {
                continue;
            }
            String code = r.canonicalCode().name();
            String text = r.text();
            fired.add(new CallSession.FiredReason(
                    code,
                    r.severity() == null ? "INFO" : r.severity().name(),
                    r.family() == null ? "UNKNOWN" : r.family().name(),
                    nowMs,
                    text == null || text.isBlank() ? "—" : (text.length() <= 80 ? text : text.substring(0, 77) + "..."),
                    baselineForReasonCode(code),
                    text
            ));
        }
        session.recordFiredReasons(nowMs, fired);

        List<String> firedRuleIds = policyEval.firedRules().stream()
                .map(RuleEvaluation.FiredRule::ruleId)
                .filter(id -> id != null && !id.isBlank())
                .toList();
        List<String> firedTitles = policyEval.firedRules().stream()
                .map(fr -> {
                    if (fr.title() != null && !fr.title().isBlank()) {
                        return fr.title();
                    }
                    return fr.ruleId() == null ? "" : fr.ruleId();
                })
                .filter(t -> t != null && !t.isBlank())
                .toList();
        // Also treat keyword-matched ACTIVE rule ids as broken-rule signals for the UI.
        if (working.linguistic() != null && working.linguistic().matchedRuleIds() != null) {
            java.util.LinkedHashSet<String> mergedIds = new java.util.LinkedHashSet<>(firedRuleIds);
            mergedIds.addAll(working.linguistic().matchedRuleIds().stream()
                    .filter(id -> id != null && !id.isBlank())
                    .toList());
            firedRuleIds = List.copyOf(mergedIds);
        }
        session.recordBrokenRules(firedRuleIds, firedTitles);

        String llmState = working.linguistic() != null && working.linguistic().available()
                ? "AVAILABLE" : "MISSING";
        sessionExplainRecorder.onTick(
                session.getTenantId(),
                session,
                Math.max(0L, nowMs - session.getCreatedAt().toEpochMilli()),
                fusion.smoothed(),
                decision.level(),
                fusion.families(),
                firedRuleIds,
                llmState,
                reasons,
                working
        );

        if (working.linguistic() != null) {
            String snippet = working.linguistic().redactedSnippet();
            if (snippet == null || snippet.isBlank()) {
                snippet = working.linguistic().redactedDelta();
            }
            long elapsed = Math.max(0L, nowMs - session.getCreatedAt().toEpochMilli());
            breakGlassTranscriptService.rememberSnippet(session.getSessionId(), snippet, elapsed);
        }

        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("seq", frame.seq());
        auditPayload.put("instantaneous", fusion.instantaneous());
        auditPayload.put("smoothed", fusion.smoothed());
        auditPayload.put("level", decision.level().name());
        auditPayload.put("state", fusion.state().name());
        auditPayload.put("changed", decision.changed());
        auditPayload.put("policyState", policyEval.state());
        auditPayload.put("policyMinLevel", policyEval.minLevel());
        auditPayload.put("policyScore", policyEval.policyScore());
        auditPayload.put("policyVersion", policyVersion);
        auditPayload.put("fusionConfigVersion", fusionVersion);
        auditPayload.put("ruleFloorApplied", assessment.ruleFloorApplied());
        auditPayload.put("emergencyFired", assessment.emergencyFired());
        auditPayload.put("firedRuleCount", policyEval.firedRules().size());
        auditPayload.put("undeterminedRuleCount", policyEval.undeterminedRules().size());
        auditWriteDispatcher.submit(
                session.getSessionId(),
                AuditEventType.FEATURE_FRAME_SCORED,
                auditPayload
        );

        TelemetryFrame telemetry = telemetryFrameBuilder.build(
                session,
                frame,
                fusion,
                decision,
                previousLevel,
                nowMs,
                identity,
                reasons
        );

        Map<String, Double> factorBreakdown = new LinkedHashMap<>();
        fusion.families().forEach((family, score) ->
                factorBreakdown.put(family.configKey(), score.available() ? score.score() : 0.0));
        callSessionManager.recordTelemetry(
                session.getSessionId(),
                new TelemetryEntry(
                        frame.seq(),
                        nowMs,
                        fusion.instantaneous(),
                        fusion.smoothed(),
                        decision.level(),
                        factorBreakdown
                )
        );

        telemetryBroadcaster.publish(telemetry);
        if (decision.changed()) {
            try {
                planRunner.onLevelChanged(session.getSessionId(), previousLevel, decision.level());
            } catch (Exception ex) {
                log.error(
                        "actuation_invoke_failed sessionId={} level={} err={}",
                        session.getSessionId(),
                        decision.level(),
                        ex.toString(),
                        ex
                );
            }
        }
        log.info(
                "telemetry_built sessionId={} seq={} smoothed={} level={} reasons={}",
                session.getSessionId(),
                frame.seq(),
                fusion.smoothed(),
                decision.level(),
                reasons.size()
        );
    }

    private long nowMs() {
        return clock.millis();
    }

    private static boolean missingSessionId(FeatureFrame frame) {
        return frame == null || frame.sessionId() == null || frame.sessionId().isBlank();
    }

    long frameAgeMs(CallSession session, FeatureFrame frame) {
        long now = nowMs();
        long windowEnd = frame.windowEndMs();
        if (windowEnd > EPOCH_MS_THRESHOLD) {
            long age = now - windowEnd;
            if (age < 0 || age > properties.ml().frameStalenessMs()) {
                return 0L;
            }
            return age;
        }
        Long mediaOrigin = session.getMediaOriginEpochMs();
        if (mediaOrigin == null) {
            mediaOrigin = now - windowEnd;
            session.setMediaOriginEpochMs(mediaOrigin);
        }
        long age = now - (mediaOrigin + windowEnd);
        if (age < 0) {
            session.setMediaOriginEpochMs(now - windowEnd);
            return 0L;
        }
        if (age > properties.ml().frameStalenessMs()) {
            log.info(
                    "feature_frame_rebase sessionId={} seq={} ageMs={} — continuing (lab clock/backlog recovery)",
                    frame.sessionId(),
                    frame.seq(),
                    age
            );
            session.setMediaOriginEpochMs(now - windowEnd);
            return 0L;
        }
        return age;
    }

    private static String baselineForReasonCode(String code) {
        return switch (code == null ? "" : code) {
            case "SPEAKER_MISMATCH", "VOICEPRINT_FAIL" -> "match cosine ≥ 0.70";
            case "NO_BREATH" -> "8–20 breaths/min";
            case "OVERSMOOTH_PROSODY" -> "jitter 0.5–1.5%";
            case "CHANNEL_INCONSISTENT", "NO_ROOM_ACOUSTICS" -> "T60 ≳ 30 ms / no double-compression";
            case "DOUBLE_COMPRESSION" -> "score < 0.55";
            case "SYNTHETIC_VOICE", "SYNTHETIC_ARTIFACTS" -> "spoofProbability < 0.60";
            case "POLICY_RULE_FIRED", "POLICY_VIOLATION" -> "within verbalAuthorityLimit";
            case "CHALLENGE_LATENCY_FAIL" -> "response onset ≤ 1.8s (fail > 3.5s)";
            case "CHALLENGE_CONTENT_FAIL" -> "fuzzy phrase overlap ≥ 0.60";
            case "CHALLENGE_ACOUSTIC_FAIL" -> "speaker cosine ≥ 0.55 vs call baseline";
            case "LLM_UNAVAILABLE" -> "LLM features available";
            case "INSUFFICIENT_EVIDENCE" -> "all families present";
            default -> "see methodology appendix";
        };
    }

    /**
     * F11 — when Stage B LLM is unavailable and fusion policy says RAISE_ONE_LEVEL,
     * bump the intervention level by one (capped at LEVEL_5). CONTINUE_RULES_ONLY is a no-op.
     */
    private InterventionDecision applyLlmUnavailablePolicy(
            CallSession session,
            FusionConfigDocument config,
            FeatureFrame working,
            InterventionDecision decision
    ) {
        if (decision == null || working == null || working.linguistic() == null) {
            return decision;
        }
        String policy = "CONTINUE_RULES_ONLY";
        if (config != null && config.missingEvidence() != null
                && config.missingEvidence().llmUnavailable() != null) {
            policy = config.missingEvidence().llmUnavailable().trim().toUpperCase();
        }
        if (!"RAISE_ONE_LEVEL".equals(policy)) {
            return decision;
        }
        if (!working.linguistic().llmUnavailable()) {
            return decision;
        }
        InterventionLevel bumped = bumpOne(decision.level());
        if (bumped == decision.level()) {
            return decision;
        }
        log.info(
                "llm_unavailable_raise sessionId={} from={} to={}",
                session.getSessionId(),
                decision.level(),
                bumped
        );
        return new InterventionDecision(
                bumped,
                true,
                decision.dwellRemainingMs(),
                decision.actionsToFire(),
                (decision.rationale() == null ? "" : decision.rationale() + "; ")
                        + "missingEvidence.llmUnavailable=RAISE_ONE_LEVEL",
                decision.suppressedIntent()
        );
    }

    private static InterventionLevel bumpOne(InterventionLevel level) {
        if (level == null) {
            return InterventionLevel.LEVEL_2_SOFT_NUDGE;
        }
        return switch (level) {
            case LEVEL_1_SILENT -> InterventionLevel.LEVEL_2_SOFT_NUDGE;
            case LEVEL_2_SOFT_NUDGE -> InterventionLevel.LEVEL_3_STEP_UP_MFA;
            case LEVEL_3_STEP_UP_MFA -> InterventionLevel.LEVEL_4_AUTO_HOLD;
            case LEVEL_4_AUTO_HOLD, LEVEL_5_TERMINATE -> InterventionLevel.LEVEL_5_TERMINATE;
        };
    }

    private ReasonGenerator.Assessments challengeAssessments(
            String sessionId,
            RelationshipAssessment relationship,
            IdentityAssessment identity,
            CorrelationResult crossChannel
    ) {
        var failure = challengeService.lastFailure(sessionId).orElse(null);
        boolean latencyFailed = false;
        String failCode = null;
        long latencyMs = 0L;
        long budgetMs = challengeService.properties().suspiciousLatencyMs();
        double metric = 0.0;
        if (failure != null && clock.millis() - failure.atEpochMs() < 120_000L) {
            latencyMs = failure.latencyMs();
            failCode = switch (failure.verdict()) {
                case FAIL_LATENCY, TIMEOUT -> ReasonCode.CHALLENGE_LATENCY_FAIL.name();
                case FAIL_CONTENT -> ReasonCode.CHALLENGE_CONTENT_FAIL.name();
                case FAIL_ACOUSTIC -> ReasonCode.CHALLENGE_ACOUSTIC_FAIL.name();
                case PASS -> null;
            };
            latencyFailed = failure.verdict() == ChallengeVerdict.FAIL_LATENCY
                    || failure.verdict() == ChallengeVerdict.TIMEOUT;
        }
        return new ReasonGenerator.Assessments(
                relationship,
                identity.presenceConflict() != null,
                identity.presenceConflict() != null ? identity.presenceConflict().expected() : null,
                identity.presenceConflict() != null ? identity.presenceConflict().observed() : null,
                crossChannel.matchingCampaign(),
                crossChannel.eventCount(),
                latencyFailed,
                latencyMs,
                budgetMs,
                failCode,
                metric
        );
    }
}
