package com.sentinelvoice.controller;

import com.sentinelvoice.model.ChallengeRequest;
import com.sentinelvoice.model.ChallengeVerification;
import com.sentinelvoice.service.ChallengeResponseService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class ChallengeResponseController {

    private final ChallengeResponseService challengeResponseService;

    public ChallengeResponseController(ChallengeResponseService challengeResponseService) {
        this.challengeResponseService = challengeResponseService;
    }

    @PostMapping("/challenge/issue")
    public ResponseEntity<ChallengeRequest> issueChallenge(@RequestParam String sessionId) {
        String challenge = challengeResponseService.issueChallenge();
        long issuedAt = Instant.now().toEpochMilli();
        return ResponseEntity.ok(new ChallengeRequest(sessionId, challenge, issuedAt));
    }

    @PostMapping("/challenge/verify")
    public ResponseEntity<Map<String, Object>> verifyChallenge(@RequestBody ChallengeVerification verification) {
        long latency = challengeResponseService.measureResponseLatency(
                Instant.ofEpochMilli(verification.issuedAt()),
                Instant.ofEpochMilli(verification.respondedAt())
        );

        boolean accepted = verification.submittedText() != null && verification.submittedText().equalsIgnoreCase(verification.challengeText());
        return ResponseEntity.ok(Map.of(
                "sessionId", verification.sessionId(),
                "accepted", accepted,
                "latencyMs", latency,
                "challengeText", verification.challengeText()
        ));
    }
}
