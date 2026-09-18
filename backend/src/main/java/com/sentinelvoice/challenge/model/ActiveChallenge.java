package com.sentinelvoice.challenge.model;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-authoritative liveness challenge state.
 *
 * <p><b>Defect #6 fix:</b> the previous {@code ChallengeResponseService} trusted
 * {@code issuedAt} / {@code respondedAt} sent by the client when measuring latency.
 * That let an attacker forge a sub-threshold response time and made the control
 * meaningless. All timing fields below are written only by the Decision Plane using
 * {@link System#nanoTime()}; the client receives phrase + nonce and never sends clocks.
 */
public final class ActiveChallenge {

    public enum Status {
        ISSUED,
        DISPLAYED,
        SPEECH_ONSET,
        EVALUATED,
        TIMEOUT
    }

    public enum Language {
        EN,
        HI,
        TA
    }

    private final String sessionId;
    private final String nonce;
    private final String phrase;
    private final Language language;
    private final long issuedAtMonotonicNanos;
    private final long expiresAtEpochMs;
    private final Set<String> sessionUsedPhrases;

    private volatile Status status;
    private volatile long displayedAtMonotonicNanos;
    private volatile long speechOnsetAtMonotonicNanos;
    private volatile Long latencyMs;
    private volatile String transcript;
    private volatile Double contentOverlap;
    private volatile Double acousticCosine;
    private volatile ChallengeVerdict verdict;

    public ActiveChallenge(
            String sessionId,
            String nonce,
            String phrase,
            Language language,
            long issuedAtMonotonicNanos,
            long expiresAtEpochMs,
            Set<String> sessionUsedPhrases
    ) {
        this.sessionId = sessionId;
        this.nonce = nonce;
        this.phrase = phrase;
        this.language = language;
        this.issuedAtMonotonicNanos = issuedAtMonotonicNanos;
        this.expiresAtEpochMs = expiresAtEpochMs;
        this.sessionUsedPhrases = sessionUsedPhrases;
        this.status = Status.ISSUED;
    }

    public String sessionId() {
        return sessionId;
    }

    public String nonce() {
        return nonce;
    }

    public String phrase() {
        return phrase;
    }

    public Language language() {
        return language;
    }

    public long issuedAtMonotonicNanos() {
        return issuedAtMonotonicNanos;
    }

    public long expiresAtEpochMs() {
        return expiresAtEpochMs;
    }

    public Set<String> sessionUsedPhrases() {
        return sessionUsedPhrases;
    }

    public Status status() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public long displayedAtMonotonicNanos() {
        return displayedAtMonotonicNanos;
    }

    public void setDisplayedAtMonotonicNanos(long displayedAtMonotonicNanos) {
        this.displayedAtMonotonicNanos = displayedAtMonotonicNanos;
    }

    public long speechOnsetAtMonotonicNanos() {
        return speechOnsetAtMonotonicNanos;
    }

    public void setSpeechOnsetAtMonotonicNanos(long speechOnsetAtMonotonicNanos) {
        this.speechOnsetAtMonotonicNanos = speechOnsetAtMonotonicNanos;
    }

    public Long latencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public String transcript() {
        return transcript;
    }

    public void setTranscript(String transcript) {
        this.transcript = transcript;
    }

    public Double contentOverlap() {
        return contentOverlap;
    }

    public void setContentOverlap(Double contentOverlap) {
        this.contentOverlap = contentOverlap;
    }

    public Double acousticCosine() {
        return acousticCosine;
    }

    public void setAcousticCosine(Double acousticCosine) {
        this.acousticCosine = acousticCosine;
    }

    public ChallengeVerdict verdict() {
        return verdict;
    }

    public void setVerdict(ChallengeVerdict verdict) {
        this.verdict = verdict;
    }

    /** Per-session phrase history (never repeat within a call). */
    public static Set<String> newPhraseHistory() {
        return ConcurrentHashMap.newKeySet();
    }
}
