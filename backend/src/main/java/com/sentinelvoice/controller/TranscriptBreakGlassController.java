package com.sentinelvoice.controller;

import com.sentinelvoice.transcript.BreakGlassTranscriptService;
import com.sentinelvoice.transcript.BreakGlassTranscriptService.Window;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/session/{sessionId}/transcript")
public class TranscriptBreakGlassController {

    private final BreakGlassTranscriptService breakGlassTranscriptService;

    public TranscriptBreakGlassController(BreakGlassTranscriptService breakGlassTranscriptService) {
        this.breakGlassTranscriptService = breakGlassTranscriptService;
    }

    @GetMapping("/status")
    public Map<String, Object> status(@PathVariable String sessionId) {
        return toBody(breakGlassTranscriptService.status(sessionId), false);
    }

    @PostMapping("/request")
    public ResponseEntity<?> request(
            @PathVariable String sessionId,
            @RequestBody Map<String, Object> body,
            Authentication auth
    ) {
        String justification = body.get("justification") == null ? "" : String.valueOf(body.get("justification"));
        try {
            Window w = breakGlassTranscriptService.request(sessionId, auth.getName(), justification);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(toBody(w, false));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/approve")
    public ResponseEntity<?> approve(@PathVariable String sessionId, Authentication auth) {
        try {
            Window w = breakGlassTranscriptService.approve(sessionId, auth.getName());
            return ResponseEntity.ok(toBody(w, true));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", ex.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<?> get(@PathVariable String sessionId) {
        try {
            Window w = breakGlassTranscriptService.requireApproved(sessionId);
            return ResponseEntity.ok(toBody(w, true));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", ex.getMessage(),
                    "status", breakGlassTranscriptService.status(sessionId).status().name()
            ));
        }
    }

    private static Map<String, Object> toBody(Window w, boolean includeText) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", w.status().name());
        body.put("requester", w.requester());
        body.put("approver", w.approver());
        body.put("justification", w.justification());
        body.put("windowStartMs", w.windowStartMs());
        body.put("windowEndMs", w.windowEndMs());
        if (includeText && w.status() == BreakGlassTranscriptService.Status.APPROVED) {
            body.put("redactedText", w.redactedText());
        } else {
            body.put("redactedText", null);
        }
        return body;
    }
}
