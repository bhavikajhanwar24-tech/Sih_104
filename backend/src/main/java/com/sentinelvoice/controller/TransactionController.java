package com.sentinelvoice.controller;

import com.sentinelvoice.actuation.TransactionLockService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mock banking approve endpoint — lock is server-enforced (HTTP 423).
 */
@RestController
@RequestMapping("/api/v1/transaction")
public class TransactionController {

    private final TransactionLockService transactionLockService;

    public TransactionController(TransactionLockService transactionLockService) {
        this.transactionLockService = transactionLockService;
    }

    @PostMapping("/{sessionId}/approve")
    public ResponseEntity<Map<String, Object>> approve(
            @PathVariable String sessionId,
            @RequestBody(required = false) Map<String, Object> body
    ) {
        String actorId = body != null && body.get("actorId") instanceof String s ? s : "teller-demo";
        try {
            return ResponseEntity.ok(transactionLockService.approve(sessionId, actorId));
        } catch (ResponseStatusException ex) {
            if (ex.getStatusCode() == HttpStatus.LOCKED) {
                Map<String, Object> locked = new LinkedHashMap<>();
                locked.put("status", "LOCKED");
                locked.put("reason", TransactionLockService.LOCK_REASON);
                locked.put("level", transactionLockService.effectiveLevel(sessionId).name());
                locked.put("sessionId", sessionId);
                return ResponseEntity.status(HttpStatus.LOCKED).body(locked);
            }
            throw ex;
        }
    }

    @PostMapping("/{sessionId}/lock-status")
    public ResponseEntity<Map<String, Object>> lockStatus(@PathVariable String sessionId) {
        boolean locked = transactionLockService.isLocked(sessionId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("locked", locked);
        body.put("level", transactionLockService.effectiveLevel(sessionId).name());
        return ResponseEntity.ok(body);
    }
}
