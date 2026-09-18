package com.sentinelvoice.controller;

import com.sentinelvoice.service.CrossChannelCorrelationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class CrossChannelController {

    private final CrossChannelCorrelationService crossChannelCorrelationService;

    public CrossChannelController(CrossChannelCorrelationService crossChannelCorrelationService) {
        this.crossChannelCorrelationService = crossChannelCorrelationService;
    }

    @GetMapping("/cross-channel")
    public ResponseEntity<Map<String, Object>> getCrossChannelSignals(
            @RequestParam String sessionId,
            @RequestParam(defaultValue = "48") int hours
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("windowHours", hours);
        body.put("signals", crossChannelCorrelationService.correlate(sessionId, hours));
        return ResponseEntity.ok(body);
    }
}
