package com.sentinelvoice.service;

import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class NaturalLanguageFraudService {

    public double scoreUrgency(String transcript) {
        if (transcript == null || transcript.isBlank()) {
            return 0.15;
        }
        String normalized = transcript.toLowerCase();
        if (normalized.contains("immediately") || normalized.contains("urgent") || normalized.contains("do not tell anyone")) {
            return 0.91;
        }
        return 0.42;
    }

    public Map<String, Object> explain(String transcript) {
        return Map.of(
                "transcript", transcript,
                "urgencyScore", scoreUrgency(transcript),
                "status", "NLP urgency signal evaluated"
        );
    }
}
