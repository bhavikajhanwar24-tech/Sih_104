package com.sentinelvoice.controller;

import com.sentinelvoice.challenge.ChallengeService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Server-authoritative liveness challenge API (P8.4 / defect #6).
 *
 * <p>Clients receive phrase + nonce only. Timing is measured on the server; any
 * client-supplied {@code issuedAt} / {@code respondedAt} fields are ignored / rejected.
 */
@RestController
@RequestMapping("/api/v1/challenge")
public class ChallengeResponseController {

    private final ChallengeService challengeService;

    public ChallengeResponseController(ChallengeService challengeService) {
        this.challengeService = challengeService;
    }

    @PostMapping("/issue")
    public ResponseEntity<Map<String, Object>> issue(
            @RequestParam String sessionId,
            @RequestParam(defaultValue = "en") String language
    ) {
        try {
            return ResponseEntity.ok(challengeService.issue(sessionId, language));
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        }
    }

    @PostMapping("/{nonce}/displayed")
    public ResponseEntity<Map<String, Object>> displayed(@PathVariable String nonce) {
        try {
            return ResponseEntity.ok(challengeService.markDisplayed(nonce));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage());
        }
    }

    /**
     * ml-engine → Decision Plane: first VAD speech onset after display.
     * Body may include diagnostic {@code mlMonotonicNanos}; it does not drive the stopwatch.
     */
    @PostMapping("/speech-onset")
    public ResponseEntity<Map<String, Object>> speechOnset(@RequestBody Map<String, Object> body) {
        String sessionId = str(body.get("sessionId"));
        String nonce = str(body.get("nonce"));
        Long mlMono = asLong(body.get("mlMonotonicNanos"));
        try {
            return ResponseEntity.ok(challengeService.onSpeechOnset(sessionId, nonce, mlMono));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        }
    }

    /**
     * ml-engine → Decision Plane: ASR transcript + acoustic cosine of the response window.
     * Intentionally does <em>not</em> accept issuedAt / respondedAt from any caller.
     */
    @PostMapping("/{nonce}/evidence")
    public ResponseEntity<Map<String, Object>> evidence(
            @PathVariable String nonce,
            @RequestBody Map<String, Object> body
    ) {
        // Reject legacy spoof fields explicitly so tests can prove they have no effect.
        if (body.containsKey("issuedAt") || body.containsKey("respondedAt") || body.containsKey("issuedAtEpochMs")) {
            // Still evaluate using server clocks — ignore the forged fields.
            body = Map.copyOf(body);
        }
        String transcript = str(body.get("transcript"));
        Double cosine = asDouble(body.get("acousticCosine"));
        try {
            return ResponseEntity.ok(challengeService.submitEvidence(nonce, transcript, cosine));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        }
    }

    @GetMapping("/session/{sessionId}")
    public ResponseEntity<Map<String, Object>> current(@PathVariable String sessionId) {
        // Always 200 — empty challenge is normal for polling (404 floods browser console).
        return challengeService.current(sessionId)
                .map(body -> {
                    body.put("active", true);
                    return ResponseEntity.ok(body);
                })
                .orElseGet(() -> ResponseEntity.ok(Map.of("active", false, "sessionId", sessionId)));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Long asLong(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        if (o instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Double asDouble(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
