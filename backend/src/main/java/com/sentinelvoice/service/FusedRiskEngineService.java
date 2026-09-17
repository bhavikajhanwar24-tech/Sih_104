package com.sentinelvoice.service;

import com.sentinelvoice.model.RiskAssessmentResult;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class FusedRiskEngineService {

    public RiskAssessmentResult evaluate(
            String sessionId,
            double voiceAuthenticity,
            double channelForensics,
            double prosody,
            double nlpSignal,
            double transactionDeviation,
            double relationshipRisk
    ) {
        double voiceWeight = 0.22;
        double channelWeight = 0.10;
        double prosodyWeight = 0.13;
        double nlpWeight = 0.25;
        double transactionWeight = 0.18;
        double graphWeight = 0.12;

        double score =
                voiceAuthenticity * voiceWeight +
                channelForensics * channelWeight +
                prosody * prosodyWeight +
                nlpSignal * nlpWeight +
                transactionDeviation * transactionWeight +
                relationshipRisk * graphWeight;

        score = Math.max(0.0, Math.min(1.0, score));

        Map<String, Double> breakdown = new LinkedHashMap<>();
        breakdown.put("voiceAuthenticity", voiceAuthenticity);
        breakdown.put("channelForensics", channelForensics);
        breakdown.put("prosody", prosody);
        breakdown.put("nlpSignal", nlpSignal);
        breakdown.put("transactionDeviation", transactionDeviation);
        breakdown.put("relationshipRisk", relationshipRisk);

        String explanation = "Weighted fraud risk fused from voice integrity, channel evidence, prosody, NLP urgency, transaction anomaly, and relationship graph signals.";
        return new RiskAssessmentResult(sessionId, score, breakdown, explanation);
    }
}
