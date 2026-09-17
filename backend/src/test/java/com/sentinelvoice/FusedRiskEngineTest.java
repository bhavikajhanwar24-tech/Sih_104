package com.sentinelvoice;

import com.sentinelvoice.model.RiskAssessmentResult;
import com.sentinelvoice.service.FusedRiskEngineService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FusedRiskEngineTest {

    @Test
    void shouldComputeRiskWithinExpectedRange() {
        FusedRiskEngineService engine = new FusedRiskEngineService();

        RiskAssessmentResult result = engine.evaluate(
                "session-1",
                0.82,
                0.62,
                0.7,
                0.9,
                0.75,
                0.68
        );

        assertNotNull(result);
        assertTrue(result.totalRisk() >= 0.0);
        assertTrue(result.totalRisk() <= 1.0);
        assertNotNull(result.explanation());
        assertFalse(result.explanation().isBlank());
    }
}
