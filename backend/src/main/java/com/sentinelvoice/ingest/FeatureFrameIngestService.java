package com.sentinelvoice.ingest;

import com.sentinelvoice.actuation.ActuationService;
import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.audit.AuditWriteDispatcher;
import com.sentinelvoice.challenge.ChallengeService;
import com.sentinelvoice.challenge.model.ChallengeVerdict;
import com.sentinelvoice.config.SentinelProperties;
import com.sentinelvoice.context.CrossChannelCorrelationService;
import com.sentinelvoice.context.RelationshipGraphService;
import com.sentinelvoice.context.TransactionPolicyService;
import com.sentinelvoice.context.model.CorrelationResult;
import com.sentinelvoice.context.model.RelationshipAssessment;
import com.sentinelvoice.context.model.TransactionAssessment;
import com.sentinelvoice.fusion.EvidenceFamily;
import com.sentinelvoice.fusion.FusionContext;
import com.sentinelvoice.fusion.FusionEngineService;
import com.sentinelvoice.fusion.FusionResult;
import com.sentinelvoice.fusion.ReasonCode;
import com.sentinelvoice.fusion.ReasonGenerator;
import com.sentinelvoice.identity.DirectoryService;
import com.sentinelvoice.identity.IdentityResolutionService;
import com.sentinelvoice.identity.model.DirectoryRecord;
import com.sentinelvoice.identity.model.IdentityAssessment;
import com.sentinelvoice.intervention.InterventionDecision;
import com.sentinelvoice.intervention.InterventionLadderService;
import com.sentinelvoice.intervention.InterventionStateMachine;
import com.sentinelvoice.model.CallSession;
import com.sentinelvoice.model.FeatureFrame;
import com.sentinelvoice.model.InterventionLevel;
import com.sentinelvoice.model.RelationshipQuery;
import com.sentinelvoice.model.TelemetryEntry;
import com.sentinelvoice.model.TelemetryFrame;
import com.sentinelvoice.service.CallSessionManager;
import com.sentinelvoice.telemetry.TelemetryBroadcaster;
import com.sentinelvoice.telemetry.TelemetryFrameBuilder;
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

/**
 * Per-frame Decision Plane pipeline (P5.4):
 * validate → update session → fuse → FSM → reasons → async audit → TelemetryFrame → STOMP.
 *
 * <p>Audit is queued ({@link AuditWriteDispatcher}) so ledger I/O never blocks the broadcast.
 */
@Service
public class FeatureFrameIngestService {

    private static final Logger log = LoggerFactory.getLogger(FeatureFrameIngestService.class);
    private static final long EPOCH_MS_THRESHOLD = 1_000_000_000_000L;

    private final CallSessionManager callSessionManager;
    private final SentinelProperties properties;
    private final FusionEngineService fusionEngineService;
    private final InterventionLadderService interventionLadderService;
    private final ReasonGenerator reasonGenerator;
    private final IdentityResolutionService identityResolutionService;
    private final RelationshipGraphService relationshipGraphService;
    private final TransactionPolicyService transactionPolicyService;
    private final CrossChannelCorrelationService crossChannelCorrelationService;
    private final DirectoryService directoryService;
    private final AuditWriteDispatcher auditWriteDispatcher;
    private final TelemetryFrameBuilder telemetryFrameBuilder;
    private final TelemetryBroadcaster telemetryBroadcaster;
    private final ActuationService actuationService;
    private final ChallengeService challengeService;
    private final Clock clock;
    private final Counter received;
    private final Counter dropped;
    private final Counter stale;
    private final Timer pipelineTimer;

    public FeatureFrameIngestService(
            CallSessionManager callSessionManager,
            SentinelProperties properties,
            FusionEngineService fusionEngineService,
            InterventionLadderService interventionLadderService,
            ReasonGenerator reasonGenerator,
            IdentityResolutionService identityResolutionService,
            RelationshipGraphService relationshipGraphService,
            TransactionPolicyService transactionPolicyService,
            CrossChannelCorrelationService crossChannelCorrelationService,
            DirectoryService directoryService,
            AuditWriteDispatcher auditWriteDispatcher,
            TelemetryFrameBuilder telemetryFrameBuilder,
            TelemetryBroadcaster telemetryBroadcaster,
            @Lazy ActuationService actuationService,
            ChallengeService challengeService,
            MeterRegistry meterRegistry,
            Clock clock
    ) {
        this.callSessionManager = callSessionManager;
        this.properties = properties;
        this.fusionEngineService = fusionEngineService;
        this.interventionLadderService = interventionLadderService;
        this.reasonGenerator = reasonGenerator;
        this.identityResolutionService = identityResolutionService;
        this.relationshipGraphService = relationshipGraphService;
        this.transactionPolicyService = transactionPolicyService;
        this.crossChannelCorrelationService = crossChannelCorrelationService;
        this.directoryService = directoryService;
        this.auditWriteDispatcher = auditWriteDispatcher;
        this.telemetryFrameBuilder = telemetryFrameBuilder;
        this.telemetryBroadcaster = telemetryBroadcaster;
        this.actuationService = actuationService;
        this.challengeService = challengeService;
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
            dropped.increment();
            log.warn("feature_frame_drop reason=unknown_session sessionId={} seq={}", frame.sessionId(), frame.seq());
            return;
        }
        CallSession session = existing.get();
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

        Timer.Sample sample = Timer.start();
        try {
            runPipeline(session, frame);
        } catch (Exception ex) {
            log.error(
                    "pipeline_failed sessionId={} seq={} cause={}",
                    frame.sessionId(),
                    frame.seq(),
                    ex.toString(),
                    ex
            );
            try {
                telemetryBroadcaster.publish(
                        telemetryFrameBuilder.buildDegraded(session, frame, nowMs(), ex.getMessage())
                );
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
        RelationshipAssessment relationship = relationshipGraphService.assess(
                new RelationshipQuery(session.getCallerId(), session.getCalleeId(), null)
        );
        CorrelationResult crossChannel = crossChannelCorrelationService.correlateSession(
                session.getSessionId(), null
        );
        double relationshipScore = crossChannelCorrelationService.blendRelationshipScore(
                relationship.score(), crossChannel
        );
        DirectoryRecord claimed = null;
        if (identity.directoryRecordForClaim() != null
                && identity.directoryRecordForClaim().get("employeeId") instanceof String empId) {
            claimed = directoryService.findByEmployeeId(empId).orElse(null);
        }
        TransactionAssessment transaction = transactionPolicyService.assess(frame, claimed);

        FusionContext fusionContext = FusionContext.withIdentity(
                frame,
                transaction.score(),
                true,
                relationshipScore,
                true,
                identity
        );
        FusionResult fusion = fusionEngineService.evaluate(session.getSessionId(), fusionContext);

        List<String> corroborating = fusion.corroboration().familiesAboveThreshold().stream()
                .map(EvidenceFamily::configKey)
                .toList();
        boolean challengeEmergency = challengeService.lastFailure(session.getSessionId())
                .filter(f -> nowMs - f.atEpochMs() < 60_000L)
                .isPresent();
        InterventionDecision decision = interventionLadderService.evaluate(
                session.getSessionId(),
                new InterventionStateMachine.EvaluationInput(
                        fusion.smoothed(),
                        fusion.corroboration().satisfied(),
                        corroborating,
                        fusion.emergencyReason() != null || challengeEmergency,
                        false,
                        nowMs
                )
        );

        List<ReasonGenerator.GeneratedReason> reasons = reasonGenerator.generate(
                fusionContext,
                fusion.families(),
                challengeAssessments(session.getSessionId(), relationship, identity, crossChannel)
        );

        // Cumulative evidence for forensic dossier (every firing, not just latest topReasons).
        List<CallSession.FiredReason> fired = new ArrayList<>(reasons.size());
        for (ReasonGenerator.GeneratedReason r : reasons) {
            if (r == null || r.code() == null) {
                continue;
            }
            String code = r.code().name();
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

        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("seq", frame.seq());
        auditPayload.put("instantaneous", fusion.instantaneous());
        auditPayload.put("smoothed", fusion.smoothed());
        auditPayload.put("level", decision.level().name());
        auditPayload.put("state", fusion.state().name());
        auditPayload.put("changed", decision.changed());
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
                actuationService.onLevelChanged(session.getSessionId(), previousLevel, decision.level());
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
            // Clock leaps / sleep on the lab laptop: rebase instead of killing the gauge.
            if (age < 0 || age > properties.ml().frameStalenessMs()) {
                return 0L;
            }
            return age;
        }
        // Call-relative windows track media time, not session-create time.
        // Analyst UI often starts the mic several seconds after Start session.
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
        // ASR / CPU backlog or Windows clock leap: rebase origin so the feed keeps flowing.
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
            case "VOICEPRINT_FAIL" -> "match cosine ≥ 0.70";
            case "NO_BREATH" -> "8–20 breaths/min";
            case "OVERSMOOTH_PROSODY" -> "jitter 0.5–1.5%";
            case "NO_ROOM_ACOUSTICS" -> "T60 ≳ 30 ms";
            case "DOUBLE_COMPRESSION" -> "score < 0.55";
            case "SYNTHETIC_ARTIFACTS" -> "spoofProbability < 0.60";
            case "POLICY_VIOLATION" -> "within verbalAuthorityLimit";
            case "CHALLENGE_LATENCY_FAIL" -> "response onset ≤ 1.8s (fail > 3.5s)";
            case "CHALLENGE_CONTENT_FAIL" -> "fuzzy phrase overlap ≥ 0.60";
            case "CHALLENGE_ACOUSTIC_FAIL" -> "speaker cosine ≥ 0.55 vs call baseline";
            default -> "see methodology appendix";
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
