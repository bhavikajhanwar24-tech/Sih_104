package com.sentinelvoice.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class CrossChannelController {

    @GetMapping("/cross-channel")
    public ResponseEntity<Map<String, Object>> getCrossChannelSignals(
            @RequestParam String sessionId,
            @RequestParam(defaultValue = "48") int hours
    ) {
        return ResponseEntity.ok(Map.of(
                "sessionId", sessionId,
                "windowHours", hours,
                "signals", List.of(
                        Map.of("channel", "email", "risk", 0.64),
                        Map.of("channel", "sms", "risk", 0.72),
                        Map.of("channel", "voice", "risk", 0.81)
                )
        ));
    }
}
