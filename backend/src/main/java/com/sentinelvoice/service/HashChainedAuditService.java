package com.sentinelvoice.service;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class HashChainedAuditService {

    private final List<Map<String, String>> ledger = new ArrayList<>();

    public Map<String, String> append(String sessionId, String eventType, String details, String previousHash) {
        String timestamp = Instant.now().toString();
        String payload = String.join("|", sessionId, eventType, details, previousHash, timestamp);
        String currentHash = hash(payload);

        Map<String, String> block = new LinkedHashMap<>();
        block.put("sessionId", sessionId);
        block.put("eventType", eventType);
        block.put("details", details);
        block.put("timestamp", timestamp);
        block.put("previousHash", previousHash);
        block.put("currentHash", currentHash);
        ledger.add(block);
        return block;
    }

    public List<Map<String, String>> getLedger() {
        return List.copyOf(ledger);
    }

    private String hash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", e);
        }
    }
}
