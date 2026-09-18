package com.sentinelvoice.controller;

import com.sentinelvoice.actuation.CoreBankingWebhookService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MOCK core-banking freeze sink — demo only. Do not treat as a real CBS integration.
 */
@RestController
@RequestMapping("/mock-cbs")
public class MockCbsController {

    private final CoreBankingWebhookService coreBankingWebhookService;

    public MockCbsController(CoreBankingWebhookService coreBankingWebhookService) {
        this.coreBankingWebhookService = coreBankingWebhookService;
    }

    @PostMapping("/freeze")
    public ResponseEntity<Map<String, Object>> freeze(@RequestBody Map<String, Object> body) {
        coreBankingWebhookService.recordMockFreeze(body);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "MOCK_ACCEPTED");
        resp.put("mock", true);
        resp.put("label", "MOCK_CBS — recorded freeze; no real account was touched");
        resp.put("sessionId", body.get("sessionId"));
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/freeze")
    public ResponseEntity<Map<String, Object>> list() {
        List<Map<String, Object>> log = coreBankingWebhookService.mockFreezeLog();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("mock", true);
        resp.put("label", "MOCK_CBS freeze call log");
        resp.put("count", log.size());
        resp.put("calls", log);
        return ResponseEntity.ok(resp);
    }
}
