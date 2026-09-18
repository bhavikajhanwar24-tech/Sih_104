package com.sentinelvoice.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * MOCK core-banking freeze endpoint for demos with zero external dependencies.
 * Clearly labeled MOCK — not a real CBS integration.
 */
@RestController
@RequestMapping("/mock-cbs")
public class MockCbsController {

    private final List<Map<String, Object>> freezes = new CopyOnWriteArrayList<>();

    /**
     * MOCK: record a beneficiary/account freeze instruction.
     */
    @PostMapping("/freeze")
    public ResponseEntity<Map<String, Object>> freeze(@RequestBody Map<String, Object> body) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("receivedAt", Instant.now().toString());
        record.put("mock", true);
        record.put("label", "MOCK_CBS");
        if (body != null) {
            record.putAll(body);
        }
        freezes.add(record);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "RECORDED");
        response.put("mock", true);
        response.put("message", "MOCK CBS: freeze recorded in memory (not a real bank system)");
        response.put("index", freezes.size() - 1);
        return ResponseEntity.ok(response);
    }

    /**
     * MOCK: list freeze instructions received this process lifetime (demo panel).
     */
    @GetMapping("/freeze")
    public ResponseEntity<List<Map<String, Object>>> listFreezes() {
        return ResponseEntity.ok(new ArrayList<>(freezes));
    }
}
