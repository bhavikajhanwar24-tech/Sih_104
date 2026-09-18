package com.sentinelvoice.controller;

import com.sentinelvoice.actuation.OobMfaService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Demo MFA respond API for OOB challenge (Context §11.6).
 */
@RestController
@RequestMapping("/api/v1/mfa")
public class MfaController {

    private final OobMfaService oobMfaService;

    public MfaController(OobMfaService oobMfaService) {
        this.oobMfaService = oobMfaService;
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String sessionId) {
        return oobMfaService.current(sessionId)
                .map(c -> ResponseEntity.ok(toBody(c)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{sessionId}/respond")
    public ResponseEntity<Map<String, Object>> respond(
            @PathVariable String sessionId,
            @Valid @RequestBody MfaRespondRequest request
    ) {
        OobMfaService.MfaChallenge challenge = oobMfaService.respond(
                sessionId,
                Boolean.TRUE.equals(request.approve()),
                request.code()
        );
        return ResponseEntity.ok(toBody(challenge));
    }

    private static Map<String, Object> toBody(OobMfaService.MfaChallenge c) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema", "sentinelvoice.MfaChallenge/1");
        body.put("sessionId", c.sessionId());
        body.put("status", c.status().name());
        body.put("issuedAtMs", c.issuedAtMs());
        body.put("expiresAtMs", c.expiresAtMs());
        // Expose code in lab demos so the analyst console can complete MFA live.
        body.put("code", c.code());
        body.put("mock", true);
        return body;
    }

    public record MfaRespondRequest(
            @NotNull Boolean approve,
            String code
    ) {
    }
}
