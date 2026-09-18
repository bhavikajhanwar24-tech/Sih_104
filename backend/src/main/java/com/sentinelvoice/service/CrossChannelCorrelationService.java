package com.sentinelvoice.service;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class CrossChannelCorrelationService {

    public List<Map<String, Object>> correlate(String sessionId, int hours) {
        // SEEDED DEMO DATA - replaced by real correlation in P13.1
        return List.of(
                Map.of("channel", "email", "risk", 0.64, "sessionId", sessionId, "windowHours", hours),
                Map.of("channel", "sms", "risk", 0.72, "sessionId", sessionId, "windowHours", hours),
                Map.of("channel", "voice", "risk", 0.81, "sessionId", sessionId, "windowHours", hours)
        );
    }
}
