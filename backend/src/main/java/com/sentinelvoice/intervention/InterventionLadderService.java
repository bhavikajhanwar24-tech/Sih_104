package com.sentinelvoice.intervention;

import com.sentinelvoice.fusion.config.ActiveFusionConfigCache;
import com.sentinelvoice.fusion.config.FusionConfigDocument;
import com.sentinelvoice.fusion.engine.FusionRuntimeService;
import com.sentinelvoice.model.CallSession;
import com.sentinelvoice.model.InterventionLevel;
import com.sentinelvoice.service.CallSessionManager;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Facade over {@link FusionRuntimeService} for analyst override / level explain (F8).
 */
@Service
public class InterventionLadderService {

    private final FusionRuntimeService fusionRuntimeService;
    private final CallSessionManager callSessionManager;
    private final ActiveFusionConfigCache fusionConfigCache;

    public InterventionLadderService(
            FusionRuntimeService fusionRuntimeService,
            CallSessionManager callSessionManager,
            ActiveFusionConfigCache fusionConfigCache
    ) {
        this.fusionRuntimeService = fusionRuntimeService;
        this.callSessionManager = callSessionManager;
        this.fusionConfigCache = fusionConfigCache;
    }

    public InterventionDecision override(
            String sessionId,
            InterventionLevel targetLevel,
            String analystId,
            String reason,
            long nowMs
    ) {
        long pinMs = resolveOverridePinMs(sessionId);
        return fusionRuntimeService.override(sessionId, targetLevel, analystId, reason, nowMs, pinMs);
    }

    public InterventionLevel currentLevel(String sessionId) {
        return fusionRuntimeService.currentLevel(sessionId);
    }

    public void clearSession(String sessionId) {
        fusionRuntimeService.clearSession(sessionId);
    }

    /**
     * Stateless threshold snapshot from ACTIVE tenant fusion config (no dwell/hysteresis).
     */
    public InterventionLevel resolve(double riskScore) {
        FusionConfigDocument doc = null;
        try {
            var ctx = com.sentinelvoice.security.TenantContext.get();
            if (ctx != null) {
                doc = fusionConfigCache.get(ctx.tenantId())
                        .map(ActiveFusionConfigCache.CachedFusionConfig::document)
                        .orElse(null);
            }
        } catch (Exception ignored) {
            // fall through
        }
        if (doc == null) {
            return InterventionLevel.LEVEL_1_SILENT;
        }
        if (riskScore >= doc.level("L4").enter()) {
            return InterventionLevel.LEVEL_4_AUTO_HOLD;
        }
        if (riskScore >= doc.level("L3").enter()) {
            return InterventionLevel.LEVEL_3_STEP_UP_MFA;
        }
        if (riskScore >= doc.level("L2").enter()) {
            return InterventionLevel.LEVEL_2_SOFT_NUDGE;
        }
        return InterventionLevel.LEVEL_1_SILENT;
    }

    public Map<String, Object> explain(InterventionLevel level) {
        Map<String, Object> explanation = new LinkedHashMap<>();
        explanation.put("level", level.name());
        explanation.put("actions", FusionRuntimeService.actionsFor(level));
        explanation.put("action", switch (level) {
            case LEVEL_1_SILENT -> "Log and continue monitoring.";
            case LEVEL_2_SOFT_NUDGE -> "Display soft warning and request verification callback.";
            case LEVEL_3_STEP_UP_MFA -> "Require step-up authentication before transferring funds.";
            case LEVEL_4_AUTO_HOLD -> "Pause transaction completion and alert supervisor.";
            case LEVEL_5_TERMINATE -> "Terminate call and escalate to fraud response team.";
        });
        return Collections.unmodifiableMap(explanation);
    }

    private long resolveOverridePinMs(String sessionId) {
        try {
            CallSession session = callSessionManager.requireSession(sessionId);
            FusionConfigDocument doc = session.getFusionConfigSnapshot();
            if (doc != null) {
                return doc.overridePinDurationMs();
            }
            return fusionConfigCache.get(session.getTenantId())
                    .map(c -> c.document().overridePinDurationMs())
                    .orElse(ActiveFusionConfigCache.DEFAULT_OVERRIDE_PIN_MS);
        } catch (Exception e) {
            return ActiveFusionConfigCache.DEFAULT_OVERRIDE_PIN_MS;
        }
    }
}
