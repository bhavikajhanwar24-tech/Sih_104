package com.sentinelvoice.service;

import com.sentinelvoice.model.InterventionLevel;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class InterventionLadderService {

    public InterventionLevel resolve(double riskScore) {
        if (riskScore >= 0.90) {
            return InterventionLevel.LEVEL_5_TERMINATE;
        }
        if (riskScore >= 0.75) {
            return InterventionLevel.LEVEL_4_AUTO_HOLD;
        }
        if (riskScore >= 0.55) {
            return InterventionLevel.LEVEL_3_STEP_UP_MFA;
        }
        if (riskScore >= 0.35) {
            return InterventionLevel.LEVEL_2_SOFT_NUDGE;
        }
        return InterventionLevel.LEVEL_1_SILENT;
    }

    public Map<String, Object> explain(InterventionLevel level) {
        Map<String, Object> explanation = new LinkedHashMap<>();
        explanation.put("level", level.name());
        switch (level) {
            case LEVEL_1_SILENT -> explanation.put("action", "Log and continue monitoring.");
            case LEVEL_2_SOFT_NUDGE -> explanation.put("action", "Display soft warning and request verification callback.");
            case LEVEL_3_STEP_UP_MFA -> explanation.put("action", "Require step-up authentication before transferring funds.");
            case LEVEL_4_AUTO_HOLD -> explanation.put("action", "Pause transaction completion and alert supervisor.");
            case LEVEL_5_TERMINATE -> explanation.put("action", "Terminate call and escalate to fraud response team.");
            default -> explanation.put("action", "No action.");
        }
        return Collections.unmodifiableMap(explanation);
    }
}
