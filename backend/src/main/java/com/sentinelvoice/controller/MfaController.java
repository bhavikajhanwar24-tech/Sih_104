package com.sentinelvoice.controller;

import com.sentinelvoice.actuation.OobMfaService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/mfa")
public class MfaController {

    private final OobMfaService oobMfaService;

    public MfaController(OobMfaService oobMfaService) {
        this.oobMfaService = oobMfaService;
    }

    /**
     * Demo endpoint: approve or deny a pending OOB MFA challenge.
     * Body: {@code {"decision":"approve"|"deny"}}.
     */
    @PostMapping("/{sessionId}/respond")
    public ResponseEntity<Map<String, Object>> respond(
            @PathVariable String sessionId,
            @RequestBody Map<String, Object> body
    ) {
        Object decision = body == null ? null : body.get("decision");
        return ResponseEntity.ok(oobMfaService.respond(sessionId, decision == null ? null : decision.toString()));
    }
}
