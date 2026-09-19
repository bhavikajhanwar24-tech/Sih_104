package com.sentinelvoice.actuation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Softphone silence without hanging up — ConfBridge mute via {@code docker exec}.
 *
 * <p>ARI mute/variable APIs return 409 on dialplan legs (not in Stasis). Host AMI
 * (:5038) is unreliable on Docker Desktop. Lab calls use ConfBridge, so
 * {@code confbridge mute <conf> all} silences both softphones while the call stays up.
 */
final class AsteriskAmiClient {

    private static final Logger log = LoggerFactory.getLogger(AsteriskAmiClient.class);
    private static final String CONTAINER = "sentinelvoice-asterisk";

    AsteriskAmiClient(String host, int port, String username, String password, int timeoutMs) {
        // Credentials unused — hold uses Asterisk CLI inside the container.
    }

    /**
     * Mute or unmute every participant in the session conference.
     *
     * @param conferenceName {@code sv} + session UUID hex (no dashes), matching dialplan CONF
     */
    void muteConference(String conferenceName, boolean mute) throws Exception {
        if (conferenceName == null || conferenceName.isBlank()) {
            throw new IllegalArgumentException("conference name required");
        }
        String action = mute ? "mute" : "unmute";
        String cli = "confbridge " + action + " " + conferenceName.trim() + " all";
        ProcessBuilder pb = new ProcessBuilder(
                "docker", "exec", CONTAINER,
                "asterisk", "-rx", cli
        );
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String output;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(line);
            }
            output = sb.toString().trim();
        }
        boolean finished = proc.waitFor(15, TimeUnit.SECONDS);
        if (!finished) {
            proc.destroyForcibly();
            throw new IllegalStateException("confbridge " + action + " timed out conf=" + conferenceName);
        }
        if (proc.exitValue() != 0) {
            throw new IllegalStateException(
                    "confbridge " + action + " failed conf=" + conferenceName
                            + " exit=" + proc.exitValue()
                            + " out=" + output
            );
        }
        String lower = output.toLowerCase();
        if (lower.contains("not found") || lower.contains("no conference") || lower.contains("no such")) {
            throw new IllegalStateException(
                    "confbridge " + action + " conf=" + conferenceName + " out=" + output
            );
        }
        log.info("confbridge_{} ok conf={} out={}", action, conferenceName, output.isEmpty() ? "(ok)" : output);
    }

    /** @deprecated Prefer {@link #muteConference}; kept for any leftover call sites. */
    void muteAudio(String channelName, boolean mute) throws Exception {
        throw new UnsupportedOperationException(
                "Dial-leg MUTEAUDIO removed — use ConfBridge muteConference"
        );
    }
}
