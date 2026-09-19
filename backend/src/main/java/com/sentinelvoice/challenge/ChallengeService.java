package com.sentinelvoice.challenge;

import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.audit.AuditLedgerService;
import com.sentinelvoice.challenge.model.ActiveChallenge;
import com.sentinelvoice.challenge.model.ChallengeEvaluation;
import com.sentinelvoice.challenge.model.ChallengeVerdict;
import com.sentinelvoice.fusion.ReasonCode;
import com.sentinelvoice.intervention.InterventionDecision;
import com.sentinelvoice.intervention.InterventionLadderService;
import com.sentinelvoice.intervention.InterventionStateMachine;
import com.sentinelvoice.model.CallSession;
import com.sentinelvoice.model.InterventionLevel;
import com.sentinelvoice.service.CallSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Server-authoritative challenge-response liveness (P8.4 / Context defect #6).
 *
 * <p><b>Why clocks never come from the client:</b> the legacy
 * {@code ChallengeResponseService.verify} measured latency as
 * {@code respondedAt - issuedAt} where both timestamps were supplied in the
 * request body. An attacker could forge a sub-1.8s delta and pass the check
 * without speaking. This service stores {@code issuedAtMonotonicNanos} /
 * {@code displayedAtMonotonicNanos} / {@code speechOnsetAtMonotonicNanos}
 * exclusively via {@link System#nanoTime()} on the Decision Plane.
 *
 * <p><b>Clock-skew assumption:</b> ml-engine and the Java Decision Plane run on
 * the same host in the demo topology. Speech-onset latency is measured as
 * {@code nanoTime_at_onset_receipt - displayedAtMonotonicNanos} in this JVM.
 * The ML process may attach its own monotonic timestamp for diagnostics; it is
 * <em>not</em> used as the authoritative stopwatch (cross-process mono clocks
 * are not comparable). Localhost RPC jitter is ≪ the 1.8s / 3.5s thresholds.
 */
@Service
public class ChallengeService {

    private static final Logger log = LoggerFactory.getLogger(ChallengeService.class);

    private final ConcurrentMap<String, ActiveChallenge> bySession = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ActiveChallenge> byNonce = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Set<String>> usedPhrasesBySession = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ChallengeFailure> lastFailureBySession = new ConcurrentHashMap<>();

    private final ChallengePhraseGenerator phraseGenerator;
    private final ChallengeEvaluator evaluator;
    private final ChallengeProperties properties;
    private final AuditLedgerService auditLedgerService;
    private final CallSessionManager callSessionManager;
    private final InterventionLadderService interventionLadderService;
    private final RestTemplate restTemplate;
    private final Clock clock;
    private final String mlBaseUrl;

    public ChallengeService(
            ChallengePhraseGenerator phraseGenerator,
            ChallengeEvaluator evaluator,
            ChallengeProperties properties,
            AuditLedgerService auditLedgerService,
            CallSessionManager callSessionManager,
            InterventionLadderService interventionLadderService,
            RestTemplate restTemplate,
            Clock clock,
            com.sentinelvoice.config.SentinelProperties sentinelProperties
    ) {
        this.phraseGenerator = phraseGenerator;
        this.evaluator = evaluator;
        this.properties = properties;
        this.auditLedgerService = auditLedgerService;
        this.callSessionManager = callSessionManager;
        this.interventionLadderService = interventionLadderService;
        this.restTemplate = restTemplate;
        this.clock = clock;
        this.mlBaseUrl = trimSlash(sentinelProperties.ml().baseUrl());
    }

    public Map<String, Object> issue(String sessionId, String languageRaw) {
        callSessionManager.requireSession(sessionId);
        expireIfNeeded(sessionId);

        ActiveChallenge existing = bySession.get(sessionId);
        if (existing != null
                && existing.status() != ActiveChallenge.Status.EVALUATED
                && existing.status() != ActiveChallenge.Status.TIMEOUT) {
            throw new IllegalStateException("active_challenge_exists");
        }

        ActiveChallenge.Language language = phraseGenerator.parseLanguage(languageRaw);
        Set<String> used = usedPhrasesBySession.computeIfAbsent(
                sessionId, id -> ActiveChallenge.newPhraseHistory()
        );
        String phrase = phraseGenerator.generate(language, used);
        String nonce = HexFormat.of().formatHex(uuidBytes());
        long nowMono = System.nanoTime();
        long expiresAt = clock.millis() + properties.expiryMs();

        ActiveChallenge challenge = new ActiveChallenge(
                sessionId, nonce, phrase, language, nowMono, expiresAt, used
        );
        bySession.put(sessionId, challenge);
        byNonce.put(nonce, challenge);

        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("nonce", nonce);
        audit.put("phrase", phrase);
        audit.put("language", language.name());
        audit.put("expiresAtEpochMs", expiresAt);
        audit.put("humanLatencyMs", properties.humanLatencyMs());
        audit.put("suspiciousLatencyMs", properties.suspiciousLatencyMs());
        auditLedgerService.append(
                callSessionManager.requireSession(sessionId).getTenantId(),
                sessionId,
                AuditEventType.CHALLENGE_ISSUED,
                "SYSTEM",
                null,
                audit
        );

        notifyMlArm(sessionId, nonce, phrase);

        log.info("challenge_issued sessionId={} nonce={} lang={}", sessionId, nonce, language);
        return issuedPayload(challenge);
    }

    /**
     * Client confirms the phrase is on screen. <b>This starts the latency clock.</b>
     * Any {@code issuedAt} field a client might send is ignored (and not accepted on the API).
     */
    public Map<String, Object> markDisplayed(String nonce) {
        ActiveChallenge challenge = requireNonce(nonce);
        expireIfNeeded(challenge.sessionId());
        if (challenge.status() == ActiveChallenge.Status.TIMEOUT
                || challenge.status() == ActiveChallenge.Status.EVALUATED) {
            throw new IllegalStateException("challenge_not_active");
        }
        if (challenge.status() == ActiveChallenge.Status.ISSUED
                || challenge.displayedAtMonotonicNanos() == 0L) {
            challenge.setDisplayedAtMonotonicNanos(System.nanoTime());
            challenge.setStatus(ActiveChallenge.Status.DISPLAYED);
            notifyMlDisplayed(challenge.sessionId(), nonce);
        }
        return Map.of(
                "nonce", nonce,
                "status", challenge.status().name(),
                "displayed", true
        );
    }

    /**
     * ml-engine reports first VAD speech onset after display.
     * Authoritative stopwatch uses this JVM's {@link System#nanoTime()} at receipt.
     */
    public Map<String, Object> onSpeechOnset(String sessionId, String nonce, Long mlMonotonicNanos) {
        ActiveChallenge challenge = nonce != null && !nonce.isBlank()
                ? requireNonce(nonce)
                : requireSession(sessionId);
        if (!challenge.sessionId().equals(sessionId) && sessionId != null && !sessionId.isBlank()) {
            // Prefer nonce binding; sessionId is corroboration only.
        }
        expireIfNeeded(challenge.sessionId());
        if (challenge.status() == ActiveChallenge.Status.TIMEOUT
                || challenge.status() == ActiveChallenge.Status.EVALUATED) {
            return Map.of("ignored", true, "reason", challenge.status().name());
        }
        if (challenge.displayedAtMonotonicNanos() == 0L) {
            // Display ack lost — start clock now so we still measure something honest.
            challenge.setDisplayedAtMonotonicNanos(System.nanoTime());
            challenge.setStatus(ActiveChallenge.Status.DISPLAYED);
        }
        if (challenge.speechOnsetAtMonotonicNanos() == 0L) {
            long onset = System.nanoTime();
            challenge.setSpeechOnsetAtMonotonicNanos(onset);
            long latencyMs = Math.max(0L, (onset - challenge.displayedAtMonotonicNanos()) / 1_000_000L);
            challenge.setLatencyMs(latencyMs);
            challenge.setStatus(ActiveChallenge.Status.SPEECH_ONSET);
            log.info(
                    "challenge_speech_onset sessionId={} nonce={} latencyMs={} mlMono={}",
                    challenge.sessionId(),
                    challenge.nonce(),
                    latencyMs,
                    mlMonotonicNanos
            );
        }
        return Map.of(
                "nonce", challenge.nonce(),
                "latencyMs", challenge.latencyMs(),
                "status", challenge.status().name()
        );
    }

    /**
     * ml-engine (or test harness) submits ASR transcript + acoustic cosine for the response window.
     */
    public Map<String, Object> submitEvidence(String nonce, String transcript, Double acousticCosine) {
        ActiveChallenge challenge = requireNonce(nonce);
        expireIfNeeded(challenge.sessionId());
        if (challenge.status() == ActiveChallenge.Status.TIMEOUT) {
            return resultPayload(challenge, timeoutEvaluation(challenge));
        }
        if (challenge.status() == ActiveChallenge.Status.EVALUATED && challenge.verdict() != null) {
            return resultPayload(challenge, evaluationFromChallenge(challenge));
        }

        if (challenge.latencyMs() == null && challenge.displayedAtMonotonicNanos() > 0) {
            // No onset event — treat evidence arrival as stop (tests / degraded path).
            long now = System.nanoTime();
            challenge.setSpeechOnsetAtMonotonicNanos(now);
            challenge.setLatencyMs(Math.max(0L, (now - challenge.displayedAtMonotonicNanos()) / 1_000_000L));
        }

        challenge.setTranscript(transcript);
        ChallengeEvaluation evaluation = evaluator.evaluate(
                challenge.phrase(),
                challenge.latencyMs(),
                transcript,
                acousticCosine
        );
        return finalise(challenge, evaluation);
    }

    public Optional<Map<String, Object>> current(String sessionId) {
        expireIfNeeded(sessionId);
        ActiveChallenge challenge = bySession.get(sessionId);
        if (challenge == null) {
            return Optional.empty();
        }
        Map<String, Object> body = new LinkedHashMap<>(issuedPayload(challenge));
        body.put("status", challenge.status().name());
        body.put("latencyMs", challenge.latencyMs());
        body.put("verdict", challenge.verdict() == null ? null : challenge.verdict().name());
        body.put("contentOverlap", challenge.contentOverlap());
        body.put("acousticCosine", challenge.acousticCosine());
        body.put("transcript", challenge.transcript());
        body.put("remainingMs", Math.max(0L, challenge.expiresAtEpochMs() - clock.millis()));
        return Optional.of(body);
    }

    public Optional<ChallengeFailure> lastFailure(String sessionId) {
        return Optional.ofNullable(lastFailureBySession.get(sessionId));
    }

    public ChallengeProperties properties() {
        return properties;
    }

    /** Test seam: force latency using server mono deltas without client clocks. */
    void markDisplayedAt(String nonce, long monoNanos) {
        ActiveChallenge challenge = requireNonce(nonce);
        challenge.setDisplayedAtMonotonicNanos(monoNanos);
        challenge.setStatus(ActiveChallenge.Status.DISPLAYED);
    }

    void speechOnsetAt(String nonce, long monoNanos) {
        ActiveChallenge challenge = requireNonce(nonce);
        challenge.setSpeechOnsetAtMonotonicNanos(monoNanos);
        long latencyMs = Math.max(0L, (monoNanos - challenge.displayedAtMonotonicNanos()) / 1_000_000L);
        challenge.setLatencyMs(latencyMs);
        challenge.setStatus(ActiveChallenge.Status.SPEECH_ONSET);
    }

    private Map<String, Object> finalise(ActiveChallenge challenge, ChallengeEvaluation evaluation) {
        if (challenge.status() == ActiveChallenge.Status.EVALUATED
                || (challenge.status() == ActiveChallenge.Status.TIMEOUT && challenge.verdict() != null)) {
            return resultPayload(challenge, evaluationFromChallenge(challenge));
        }

        challenge.setContentOverlap(evaluation.contentOverlap());
        challenge.setAcousticCosine(evaluation.acousticCosine());
        challenge.setVerdict(evaluation.verdict());
        challenge.setStatus(
                evaluation.verdict() == ChallengeVerdict.TIMEOUT
                        ? ActiveChallenge.Status.TIMEOUT
                        : ActiveChallenge.Status.EVALUATED
        );
        if (challenge.latencyMs() == null || evaluation.latencyMs() > 0) {
            challenge.setLatencyMs(evaluation.latencyMs());
        }

        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("nonce", challenge.nonce());
        audit.put("verdict", evaluation.verdict().name());
        audit.put("latencyMs", evaluation.latencyMs());
        audit.put("contentOverlap", evaluation.contentOverlap());
        audit.put("acousticCosine", evaluation.acousticCosine());
        audit.put("latencyPass", evaluation.latencyPass());
        audit.put("contentPass", evaluation.contentPass());
        audit.put("acousticPass", evaluation.acousticPass());
        audit.put("expectedPhrase", evaluation.expectedPhrase());
        audit.put("transcript", evaluation.transcript());
        audit.put("humanLatencyMs", evaluation.humanLatencyMs());
        audit.put("suspiciousLatencyMs", evaluation.suspiciousLatencyMs());
        CallSession session = callSessionManager.requireSession(challenge.sessionId());
        auditLedgerService.append(
                session.getTenantId(),
                challenge.sessionId(),
                AuditEventType.CHALLENGE_RESULT,
                "SYSTEM",
                null,
                audit
        );

        if (evaluation.verdict().failed()) {
            lastFailureBySession.put(
                    challenge.sessionId(),
                    new ChallengeFailure(evaluation.verdict(), evaluation.latencyMs(), clock.millis())
            );
            triggerEmergency(challenge.sessionId(), evaluation);
        }

        log.info(
                "challenge_result sessionId={} verdict={} latencyMs={} overlap={} cosine={}",
                challenge.sessionId(),
                evaluation.verdict(),
                evaluation.latencyMs(),
                evaluation.contentOverlap(),
                evaluation.acousticCosine()
        );
        return resultPayload(challenge, evaluation);
    }

    private void triggerEmergency(String sessionId, ChallengeEvaluation evaluation) {
        try {
            String reason = switch (evaluation.verdict()) {
                case FAIL_LATENCY -> ReasonCode.CHALLENGE_LATENCY_FAIL.name();
                case FAIL_CONTENT -> ReasonCode.CHALLENGE_CONTENT_FAIL.name();
                case FAIL_ACOUSTIC -> ReasonCode.CHALLENGE_ACOUSTIC_FAIL.name();
                case TIMEOUT -> ReasonCode.CHALLENGE_LATENCY_FAIL.name();
                case PASS -> null;
            };
            InterventionDecision decision = interventionLadderService.evaluate(
                    sessionId,
                    new InterventionStateMachine.EvaluationInput(
                            0.95,
                            true,
                            List.of("voice"),
                            true,
                            false,
                            clock.millis()
                    )
            );
            callSessionManager.recordTelemetry(
                    sessionId,
                    new com.sentinelvoice.model.TelemetryEntry(
                            callSessionManager.requireSession(sessionId).allocateSeq(),
                            clock.millis(),
                            0.95,
                            0.95,
                            decision.level() == null ? InterventionLevel.LEVEL_4_AUTO_HOLD : decision.level(),
                            Map.of("voice", 0.95, "challenge", 1.0)
                    )
            );
            log.warn(
                    "challenge_fail_emergency sessionId={} reason={} level={}",
                    sessionId,
                    reason,
                    decision.level()
            );
        } catch (Exception ex) {
            log.error("challenge_emergency_failed sessionId={} err={}", sessionId, ex.toString());
        }
    }

    private void expireIfNeeded(String sessionId) {
        ActiveChallenge challenge = bySession.get(sessionId);
        if (challenge == null) {
            return;
        }
        if (challenge.status() == ActiveChallenge.Status.EVALUATED
                || challenge.status() == ActiveChallenge.Status.TIMEOUT) {
            return;
        }
        if (clock.millis() < challenge.expiresAtEpochMs()) {
            return;
        }
        ChallengeEvaluation evaluation = timeoutEvaluation(challenge);
        finalise(challenge, evaluation);
    }

    private ChallengeEvaluation timeoutEvaluation(ActiveChallenge challenge) {
        long latency = challenge.latencyMs() == null ? properties.expiryMs() : challenge.latencyMs();
        return new ChallengeEvaluation(
                ChallengeVerdict.TIMEOUT,
                latency,
                challenge.contentOverlap() == null ? 0.0 : challenge.contentOverlap(),
                challenge.acousticCosine() == null ? 0.0 : challenge.acousticCosine(),
                false,
                false,
                false,
                challenge.phrase(),
                challenge.transcript() == null ? "" : challenge.transcript(),
                properties.humanLatencyMs(),
                properties.suspiciousLatencyMs()
        );
    }

    private ChallengeEvaluation evaluationFromChallenge(ActiveChallenge challenge) {
        return new ChallengeEvaluation(
                challenge.verdict(),
                challenge.latencyMs() == null ? 0L : challenge.latencyMs(),
                challenge.contentOverlap() == null ? 0.0 : challenge.contentOverlap(),
                challenge.acousticCosine() == null ? 0.0 : challenge.acousticCosine(),
                challenge.latencyMs() != null && challenge.latencyMs() <= properties.suspiciousLatencyMs(),
                challenge.contentOverlap() != null && challenge.contentOverlap() >= properties.contentOverlapMin(),
                challenge.acousticCosine() != null && challenge.acousticCosine() >= properties.acousticCosineMin(),
                challenge.phrase(),
                challenge.transcript() == null ? "" : challenge.transcript(),
                properties.humanLatencyMs(),
                properties.suspiciousLatencyMs()
        );
    }

    private Map<String, Object> issuedPayload(ActiveChallenge challenge) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema", "sentinelvoice.ChallengeIssued/1");
        body.put("sessionId", challenge.sessionId());
        body.put("nonce", challenge.nonce());
        body.put("phrase", challenge.phrase());
        body.put("expiresAtEpochMs", challenge.expiresAtEpochMs());
        body.put("language", challenge.language().name());
        body.put("humanLatencyMs", properties.humanLatencyMs());
        body.put("suspiciousLatencyMs", properties.suspiciousLatencyMs());
        body.put("expiryMs", properties.expiryMs());
        return body;
    }

    private Map<String, Object> resultPayload(ActiveChallenge challenge, ChallengeEvaluation evaluation) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema", "sentinelvoice.ChallengeResult/1");
        body.put("sessionId", challenge.sessionId());
        body.put("nonce", challenge.nonce());
        body.put("verdict", evaluation.verdict().name());
        body.put("latencyMs", evaluation.latencyMs());
        body.put("contentOverlap", evaluation.contentOverlap());
        body.put("acousticCosine", evaluation.acousticCosine());
        body.put("latencyPass", evaluation.latencyPass());
        body.put("contentPass", evaluation.contentPass());
        body.put("acousticPass", evaluation.acousticPass());
        body.put("humanLatencyMs", evaluation.humanLatencyMs());
        body.put("suspiciousLatencyMs", evaluation.suspiciousLatencyMs());
        body.put("expectedPhrase", evaluation.expectedPhrase());
        body.put("transcript", evaluation.transcript());
        return body;
    }

    private ActiveChallenge requireNonce(String nonce) {
        ActiveChallenge challenge = byNonce.get(nonce);
        if (challenge == null) {
            throw new IllegalArgumentException("unknown_nonce");
        }
        return challenge;
    }

    private ActiveChallenge requireSession(String sessionId) {
        ActiveChallenge challenge = bySession.get(sessionId);
        if (challenge == null) {
            throw new IllegalArgumentException("no_active_challenge");
        }
        return challenge;
    }

    private void notifyMlArm(String sessionId, String nonce, String phrase) {
        try {
            restTemplate.postForEntity(
                    mlBaseUrl + "/session/" + sessionId + "/challenge/arm",
                    Map.of("nonce", nonce, "phrase", phrase, "captureMs", properties.speechCaptureMs()),
                    Map.class
            );
        } catch (Exception ex) {
            log.debug("challenge_ml_arm_skipped err={}", ex.toString());
        }
    }

    private void notifyMlDisplayed(String sessionId, String nonce) {
        try {
            restTemplate.postForEntity(
                    mlBaseUrl + "/session/" + sessionId + "/challenge/displayed",
                    Map.of("nonce", nonce),
                    Map.class
            );
        } catch (Exception ex) {
            log.debug("challenge_ml_displayed_skipped err={}", ex.toString());
        }
    }

    private static byte[] uuidBytes() {
        UUID u = UUID.randomUUID();
        byte[] b = new byte[16];
        long msb = u.getMostSignificantBits();
        long lsb = u.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            b[i] = (byte) (msb >>> (8 * (7 - i)));
            b[8 + i] = (byte) (lsb >>> (8 * (7 - i)));
        }
        return b;
    }

    private static String trimSlash(String url) {
        if (url == null || url.isBlank()) {
            return "http://127.0.0.1:8000";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public record ChallengeFailure(ChallengeVerdict verdict, long latencyMs, long atEpochMs) {
    }
}
