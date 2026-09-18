package com.sentinelvoice.service;

import com.sentinelvoice.model.LinguisticAssessment;
import com.sentinelvoice.model.LinguisticFamily;
import org.springframework.stereotype.Service;

/**
 * Thin consumer of FeatureFrame.linguistic. No keyword matching — that belongs in Python (P10.2).
 */
@Service
public class NaturalLanguageFraudService {

    public LinguisticAssessment assess(LinguisticFamily linguistic) {
        if (linguistic == null || !linguistic.available()) {
            return LinguisticAssessment.unavailable();
        }
        double urgency = nz(linguistic.urgency());
        double secrecy = nz(linguistic.secrecy());
        double authority = nz(linguistic.authorityInvocation());
        double coercion = nz(linguistic.emotionalCoercion());
        double composite = (urgency + secrecy + authority + coercion) / 4.0;
        boolean askDetected = Boolean.TRUE.equals(linguistic.askDetected());
        long ageMs = linguistic.ageMs() == null ? 0L : linguistic.ageMs();
        return new LinguisticAssessment(
                true,
                urgency,
                secrecy,
                authority,
                coercion,
                askDetected,
                linguistic.claimedIdentity(),
                linguistic.claimedRole(),
                linguistic.language(),
                ageMs,
                composite
        );
    }

    private static double nz(Double value) {
        return value == null ? 0.0 : value;
    }
}
