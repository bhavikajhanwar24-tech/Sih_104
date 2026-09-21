package com.sentinelvoice.fusion.engine;

import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.audit.AuditWriteDispatcher;
import com.sentinelvoice.fusion.EvidenceFamily;
import com.sentinelvoice.fusion.FamilyScore;
import com.sentinelvoice.fusion.FusionResult;
import com.sentinelvoice.fusion.config.ActiveFusionConfigCache;
import com.sentinelvoice.fusion.config.FusionConfigDocument;
import com.sentinelvoice.intervention.InterventionDecision;
import com.sentinelvoice.model.CallSession;
import com.sentinelvoice.model.ChannelProfile;
import com.sentinelvoice.model.FeatureFrame;
import com.sentinelvoice.model.InterventionLevel;
import com.sentinelvoice.model.LinguisticFamily;
import com.sentinelvoice.model.TelemetryEntry;
import com.sentinelvoice.policy.engine.RuleEvaluation;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds per-session {@link FusionTickState} and applies the pure {@link FusionEngine}.
 */
@Service
public class FusionRuntimeService {

    private final ConcurrentHashMap<String, FusionTickState> states = new ConcurrentHashMap<>();
    private final ActiveFusionConfigCache fusionConfigCache;
    private final AuditWriteDispatcher auditWriteDispatcher;

    public FusionRuntimeService(
            ActiveFusionConfigCache fusionConfigCache,
            AuditWriteDispatcher auditWriteDispatcher
    ) {
        this.fusionConfigCache = fusionConfigCache;
        this.auditWriteDispatcher = auditWriteDispatcher;
    }

    public record EvaluationResult(
            FusionTickState state,
            RiskAssessment assessment,
            FusionResult fusionResult,
            InterventionDecision decision
    ) {
    }

    public EvaluationResult evaluate(
            CallSession session,
            FusionConfigDocument config,
            FusionTickInputs inputs,
            Integer fusionConfigVersion,
            Integer policyVersion
    ) {
        String sessionId = session.getSessionId();
        FusionTickState prior = states.computeIfAbsent(
                sessionId, id -> FusionTickState.initial(inputs.nowMs())
        );
        int previousLevel = prior.level();
        FusionEngine.TickResult tick = FusionEngine.tick(
                config, prior, inputs, fusionConfigVersion, policyVersion
        );
        states.put(sessionId, tick.state());

        RiskAssessment assessment = tick.assessment();
        FusionResult fusionResult = FusionResult.from(assessment);
        boolean changed = assessment.level() != previousLevel;
        long dwellRemaining = 0L;
        if (!changed) {
            long dwell = switch (assessment.level()) {
                case 2 -> config.level("L2").minDwellMs();
                case 3 -> config.level("L3").minDwellMs();
                case 4 -> config.level("L4").minDwellMs();
                default -> config.level("L1").minDwellMs();
            };
            dwellRemaining = Math.max(0L, dwell - (inputs.nowMs() - tick.state().levelEnteredAtMs()));
        }
        InterventionLevel level = toInterventionLevel(assessment.level());
        List<String> actions = changed ? actionsFor(level) : List.of();
        String rationale = assessment.emergencyFired()
                ? "Emergency / policy floor → " + level.name()
                : "Fusion tick level=" + level.name() + " score=" + assessment.score();
        InterventionDecision decision = new InterventionDecision(
                level, changed, dwellRemaining, actions, rationale, null
        );
        if (changed) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("from", toInterventionLevel(previousLevel).name());
            payload.put("to", level.name());
            payload.put("smoothedScore", assessment.score());
            payload.put("corroboratingFamilies", assessment.corroboratingFamilies());
            payload.put("trigger", assessment.emergencyFired() ? "EMERGENCY" : "AUTOMATIC");
            payload.put("fusionConfigVersion", fusionConfigVersion);
            payload.put("policyVersion", policyVersion);
            auditWriteDispatcher.submit(sessionId, AuditEventType.RISK_LEVEL_CHANGED, payload);
        }
        return new EvaluationResult(tick.state(), assessment, fusionResult, decision);
    }

    public InterventionDecision override(
            String sessionId,
            InterventionLevel targetLevel,
            String analystId,
            String reason,
            long nowMs,
            long overridePinDurationMs
    ) {
        if (analystId == null || analystId.isBlank()) {
            throw new IllegalArgumentException("analystId is required for an intervention override");
        }
        if (reason == null || reason.trim().length() < 10) {
            throw new IllegalArgumentException("override reason must be at least 10 characters");
        }
        if (targetLevel == null) {
            throw new IllegalArgumentException("targetLevel is required");
        }
        FusionTickState prior = states.computeIfAbsent(sessionId, id -> FusionTickState.initial(nowMs));
        int from = prior.level();
        int to = targetLevel.ordinal() + 1;
        FusionTickState next = new FusionTickState(
                prior.smoothed(),
                to,
                nowMs,
                nowMs + Math.max(1L, overridePinDurationMs),
                to
        );
        states.put(sessionId, next);

        Map<String, Object> overridePayload = new LinkedHashMap<>();
        overridePayload.put("from", toInterventionLevel(from).name());
        overridePayload.put("to", targetLevel.name());
        overridePayload.put("analystId", analystId.trim());
        overridePayload.put("reason", reason.trim());
        overridePayload.put("pinnedUntilMs", next.overrideExpiresAtMs());
        auditWriteDispatcher.submit(sessionId, AuditEventType.ANALYST_OVERRIDE, overridePayload);

        if (from != to) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("from", toInterventionLevel(from).name());
            payload.put("to", targetLevel.name());
            payload.put("smoothedScore", prior.smoothed());
            payload.put("corroboratingFamilies", List.of());
            payload.put("trigger", "MANUAL");
            auditWriteDispatcher.submit(sessionId, AuditEventType.RISK_LEVEL_CHANGED, payload);
            return new InterventionDecision(
                    targetLevel,
                    true,
                    0L,
                    actionsFor(targetLevel),
                    "Analyst " + analystId.trim() + " overrode "
                            + toInterventionLevel(from).name() + " → " + targetLevel.name()
                            + ": " + reason.trim(),
                    null
            );
        }
        return InterventionDecision.unchanged(
                targetLevel,
                0L,
                "Analyst " + analystId.trim() + " re-pinned " + targetLevel.name()
        );
    }

    public InterventionLevel currentLevel(String sessionId) {
        FusionTickState state = states.get(sessionId);
        return state == null ? InterventionLevel.LEVEL_1_SILENT : toInterventionLevel(state.level());
    }

    public void clearSession(String sessionId) {
        states.remove(sessionId);
    }

    public FusionConfigDocument resolveConfig(CallSession session) {
        if (session.getFusionConfigSnapshot() != null) {
            return session.getFusionConfigSnapshot();
        }
        return fusionConfigCache.requireDocument(session.getTenantId());
    }

    public Integer resolveFusionVersion(CallSession session) {
        if (session.getFusionConfigVersion() != null) {
            return session.getFusionConfigVersion();
        }
        return fusionConfigCache.get(session.getTenantId())
                .map(ActiveFusionConfigCache.CachedFusionConfig::version)
                .orElse(null);
    }

    public static FusionTickInputs buildInputs(
            FeatureFrame frame,
            double transactionScore,
            boolean transactionAvailable,
            double relationshipScore,
            boolean relationshipAvailable,
            RuleEvaluation policyEval,
            double cosineMismatch,
            double secrecy,
            double authority,
            boolean challengeEmergency,
            long nowMs
    ) {
        boolean narrowband = frame.channelProfile() == ChannelProfile.PSTN_NARROWBAND;
        LinguisticFamily ling = frame.linguistic();
        long lingAge = ling != null && ling.ageMs() != null ? ling.ageMs() : 0L;
        double lingScore = linguisticComposite(ling);

        List<FusionTickInputs.PolicyFiredRule> fired = new ArrayList<>();
        int policyMin = 0;
        if (policyEval != null) {
            policyMin = policyEval.minLevel();
            for (RuleEvaluation.FiredRule r : policyEval.firedRules()) {
                String clause = "";
                if (r.sourceRef() != null) {
                    Object cref = r.sourceRef().get("clauseRef");
                    if (cref == null) {
                        cref = r.sourceRef().get("quote");
                    }
                    if (cref != null) {
                        clause = String.valueOf(cref);
                    }
                }
                fired.add(new FusionTickInputs.PolicyFiredRule(
                        r.ruleId(), r.title(), clause, r.minLevel()
                ));
            }
        }

        FeatureFrame.VoiceFamily voice = frame.voice();
        FeatureFrame.ChannelFamily channel = frame.channel();
        FeatureFrame.ProsodyFamily prosody = frame.prosody();

        return new FusionTickInputs(
                narrowband,
                frame.cumulativeSpeechMs(),
                frame.speechPresent(),
                nowMs,
                voice != null && voice.available() && voice.spoofProbability() != null
                        ? FusionTickInputs.FamilyRaw.available(voice.spoofProbability())
                        : FusionTickInputs.FamilyRaw.unavailable(),
                channel != null && channel.available() && channel.doubleCompressionScore() != null
                        ? FusionTickInputs.FamilyRaw.available(channel.doubleCompressionScore())
                        : FusionTickInputs.FamilyRaw.unavailable(),
                prosody != null && prosody.available() && prosody.unnaturalnessScore() != null
                        ? FusionTickInputs.FamilyRaw.available(prosody.unnaturalnessScore())
                        : FusionTickInputs.FamilyRaw.unavailable(),
                ling != null && ling.available()
                        ? FusionTickInputs.FamilyRaw.available(lingScore)
                        : FusionTickInputs.FamilyRaw.unavailable(),
                transactionAvailable
                        ? FusionTickInputs.FamilyRaw.available(transactionScore)
                        : FusionTickInputs.FamilyRaw.unavailable(),
                relationshipAvailable
                        ? FusionTickInputs.FamilyRaw.available(relationshipScore)
                        : FusionTickInputs.FamilyRaw.unavailable(),
                lingAge,
                cosineMismatch,
                secrecy,
                authority,
                policyMin,
                fired,
                challengeEmergency
        );
    }

    public List<Map<String, Object>> whatIfReplay(
            FusionConfigDocument config,
            Integer fusionConfigVersion,
            List<TelemetryEntry> telemetry
    ) {
        if (telemetry == null || telemetry.isEmpty()) {
            throw new IllegalArgumentException(
                    "No session telemetry available to replay. Start a call and capture frames first."
            );
        }
        FusionTickState state = FusionTickState.initial(telemetry.get(0).tsMs());
        List<Map<String, Object>> ticks = new ArrayList<>();
        for (TelemetryEntry entry : telemetry) {
            Map<String, Double> scores = entry.familyScores();
            FusionTickInputs inputs = new FusionTickInputs(
                    false,
                    5_000L,
                    true,
                    entry.tsMs(),
                    familyFromMap(scores, "voice"),
                    familyFromMap(scores, "channel"),
                    familyFromMap(scores, "prosody"),
                    familyFromMap(scores, "linguistic"),
                    familyFromMap(scores, "transaction"),
                    familyFromMap(scores, "relationship"),
                    0L,
                    0.0,
                    0.0,
                    0.0,
                    0,
                    List.of(),
                    false
            );
            FusionEngine.TickResult tick = FusionEngine.tick(
                    config, state, inputs, fusionConfigVersion, null
            );
            state = tick.state();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("seq", entry.seq());
            row.put("tsMs", entry.tsMs());
            row.put("instantaneous", tick.assessment().instantaneous());
            row.put("score", tick.assessment().score());
            row.put("level", tick.assessment().level());
            row.put("originalLevel", entry.level() == null ? null : entry.level().name());
            row.put("originalSmoothed", entry.smoothedRisk());
            ticks.add(row);
        }
        return ticks;
    }

    private static FusionTickInputs.FamilyRaw familyFromMap(Map<String, Double> scores, String key) {
        if (scores == null || !scores.containsKey(key)) {
            return FusionTickInputs.FamilyRaw.unavailable();
        }
        return FusionTickInputs.FamilyRaw.available(scores.get(key));
    }

    private static double linguisticComposite(LinguisticFamily linguistic) {
        if (linguistic == null) {
            return 0.0;
        }
        double max = 0.0;
        if (linguistic.urgency() != null) {
            max = Math.max(max, linguistic.urgency());
        }
        if (linguistic.secrecy() != null) {
            max = Math.max(max, linguistic.secrecy());
        }
        if (linguistic.authorityInvocation() != null) {
            max = Math.max(max, linguistic.authorityInvocation());
        }
        if (linguistic.emotionalCoercion() != null) {
            max = Math.max(max, linguistic.emotionalCoercion());
        }
        return Math.max(0.0, Math.min(1.0, max));
    }

    public static InterventionLevel toInterventionLevel(int level) {
        return switch (level) {
            case 2 -> InterventionLevel.LEVEL_2_SOFT_NUDGE;
            case 3 -> InterventionLevel.LEVEL_3_STEP_UP_MFA;
            case 4 -> InterventionLevel.LEVEL_4_AUTO_HOLD;
            case 5 -> InterventionLevel.LEVEL_5_TERMINATE;
            default -> InterventionLevel.LEVEL_1_SILENT;
        };
    }

    public static List<String> actionsFor(InterventionLevel level) {
        // Labels only — actual execution is PlanRunner + ACTIVE/session response plan (F9).
        return switch (level) {
            case LEVEL_1_SILENT -> List.of("LOG_ONLY");
            case LEVEL_2_SOFT_NUDGE -> List.of("OPERATOR_ADVISORY", "WHISPER_WARNING");
            case LEVEL_3_STEP_UP_MFA -> List.of("LOCK_APPROVAL", "REQUIRE_CALLBACK_VERIFICATION", "SEND_OOB_MFA");
            case LEVEL_4_AUTO_HOLD -> List.of("NOTIFY_SUPERVISOR", "HOLD_CALL", "BRIDGE_SUPERVISOR");
            case LEVEL_5_TERMINATE -> List.of("NOTIFY_SUPERVISOR", "HOLD_CALL", "TERMINATE_CALL", "FREEZE_BENEFICIARY");
        };
    }

    /** Adapter used by TelemetryFrameBuilder path — keep FamilyScore map shape. */
    public static Map<EvidenceFamily, FamilyScore> toFamilyScoreMap(RiskAssessment assessment) {
        Map<EvidenceFamily, FamilyScore> map = new EnumMap<>(EvidenceFamily.class);
        for (RiskAssessment.FamilyContribution f : assessment.families()) {
            EvidenceFamily family = EvidenceFamily.fromConfigKey(f.family());
            map.put(family, new FamilyScore(
                    family, f.score(), f.weight(), f.confidence(), f.contribution(), f.available()
            ));
        }
        return Map.copyOf(map);
    }
}
