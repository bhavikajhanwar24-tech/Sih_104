package com.sentinelvoice.service;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class CrossChannelCorrelationService {

    public List<Map<String, Object>> correlate(String sessionId) {
        return List.of(
                Map.of("channel", "email", "risk", 0.61),
                Map.of("channel", "sms", "risk", 0.74),
                Map.of("channel", "voice", "risk", 0.83),
                Map.of("channel", "account", "risk", 0.68)
        );
    }
}
