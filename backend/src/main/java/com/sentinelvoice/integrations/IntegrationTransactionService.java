package com.sentinelvoice.integrations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelvoice.actuation.TransactionLockService;
import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.audit.AuditLedgerService;
import com.sentinelvoice.model.CallSession;
import com.sentinelvoice.model.InterventionLevel;
import com.sentinelvoice.service.CallSessionManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pre-transaction warning / F11 gate-check semantics for core-system callers (F17).
 */
@Service
public class IntegrationTransactionService {

    private final JdbcTemplate jdbc;
    private final CallSessionManager callSessionManager;
    private final TransactionLockService transactionLockService;
    private final AuditLedgerService auditLedgerService;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate = new RestTemplate();
    private final String mlBaseUrl;
    private final String mlServiceToken;

    public IntegrationTransactionService(
            JdbcTemplate jdbc,
            CallSessionManager callSessionManager,
            TransactionLockService transactionLockService,
            AuditLedgerService auditLedgerService,
            ObjectMapper objectMapper,
            @Value("${sentinel.ml.base-url:http://127.0.0.1:8000}") String mlBaseUrl,
            @Value("${ML_SERVICE_TOKEN:}") String mlServiceToken
    ) {
        this.jdbc = jdbc;
        this.callSessionManager = callSessionManager;
        this.transactionLockService = transactionLockService;
        this.auditLedgerService = auditLedgerService;
        this.objectMapper = objectMapper;
        this.mlBaseUrl = mlBaseUrl.endsWith("/") ? mlBaseUrl.substring(0, mlBaseUrl.length() - 1) : mlBaseUrl;
        this.mlServiceToken = mlServiceToken == null ? "" : mlServiceToken;
    }

    @Transactional
    public Map<String, Object> request(
            UUID tenantId,
            UUID apiKeyId,
            String sessionId,
            String callReference,
            String actionType,
            BigDecimal amountInr,
            String beneficiaryRef,
            String requesterEmployeeRef,
            String channel
    ) {
        if (actionType == null || actionType.isBlank()) {
            throw new IllegalArgumentException("actionType required");
        }
        long t0 = System.nanoTime();
        List<String> reasons = new ArrayList<>();
        InterventionLevel level = InterventionLevel.LEVEL_1_SILENT;
        String resolvedSessionId = sessionId;

        if ((resolvedSessionId == null || resolvedSessionId.isBlank())
                && callReference != null && !callReference.isBlank()) {
            resolvedSessionId = findSessionByCallReference(tenantId, callReference);
        }

        if (resolvedSessionId != null && !resolvedSessionId.isBlank()) {
            try {
                bestEffortGateWait(resolvedSessionId);
                CallSession session = callSessionManager.requireSessionForTenant(tenantId, resolvedSessionId);
                level = session.getCurrentLevel() == null
                        ? InterventionLevel.LEVEL_1_SILENT
                        : session.getCurrentLevel();
                reasons.add("gate_check");
                if (transactionLockService.isLocked(resolvedSessionId)) {
                    reasons.add("transaction_locked");
                }
            } catch (Exception ex) {
                reasons.add("session_unavailable");
                level = InterventionLevel.LEVEL_3_STEP_UP_MFA;
            }
        } else {
            reasons.add("no_active_session");
            if (amountInr != null && amountInr.compareTo(new BigDecimal("100000")) >= 0) {
                level = InterventionLevel.LEVEL_3_STEP_UP_MFA;
                reasons.add("high_value_amount");
            } else {
                level = InterventionLevel.LEVEL_2_SOFT_NUDGE;
            }
        }

        String decision = mapDecision(level);
        int gateMs = (int) Math.max(0, (System.nanoTime() - t0) / 1_000_000L);

        UUID id = UUID.randomUUID();
        String reasonsJson;
        try {
            reasonsJson = objectMapper.writeValueAsString(reasons);
        } catch (Exception ex) {
            reasonsJson = "[]";
        }

        jdbc.update("""
                INSERT INTO transaction_contexts (
                  id, tenant_id, session_id, call_reference, action_type, amount_inr,
                  beneficiary_ref, requester_employee_ref, channel, source,
                  decision, level, reasons, gate_check_ms, api_key_id
                ) VALUES (?,?,?,?,?,?,?,?,?,'CORE_SYSTEM',?,?,?::jsonb,?,?)
                """,
                id,
                tenantId,
                resolvedSessionId,
                callReference,
                actionType.trim(),
                amountInr,
                beneficiaryRef,
                requesterEmployeeRef,
                channel,
                decision,
                level.name(),
                reasonsJson,
                gateMs,
                apiKeyId
        );

        auditLedgerService.append(
                tenantId,
                resolvedSessionId,
                AuditEventType.CONFIG_CHANGE,
                apiKeyId == null ? "SYSTEM" : "API_KEY",
                apiKeyId == null ? null : apiKeyId.toString(),
                Map.of(
                        "area", "transaction_request",
                        "transactionContextId", id.toString(),
                        "decision", decision,
                        "level", level.name(),
                        "actionType", actionType,
                        "source", "CORE_SYSTEM"
                )
        );

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id.toString());
        out.put("decision", decision);
        out.put("level", level.name());
        out.put("reasons", reasons);
        out.put("gateCheckMs", gateMs);
        out.put("sessionId", resolvedSessionId);
        out.put("source", "CORE_SYSTEM");
        return out;
    }

    private void bestEffortGateWait(String sessionId) {
        try {
            RequestEntity<Void> req = RequestEntity
                    .post(URI.create(mlBaseUrl + "/internal/v1/sessions/" + sessionId + "/gate-wait"))
                    .header("X-ML-Service-Token", mlServiceToken)
                    .accept(MediaType.APPLICATION_JSON)
                    .build();
            restTemplate.exchange(req, Map.class);
        } catch (Exception ignored) {
            // best-effort
        }
    }

    private String findSessionByCallReference(UUID tenantId, String callReference) {
        for (CallSession s : callSessionManager.listSessionsForTenant(tenantId)) {
            if (callReference.equals(s.getSessionId())
                    || callReference.equals(s.getCallerId())
                    || callReference.equals(s.getCalleeId())) {
                return s.getSessionId();
            }
        }
        return null;
    }

    static String mapDecision(InterventionLevel level) {
        if (level == null) {
            return "ALLOW";
        }
        return switch (level) {
            case LEVEL_1_SILENT -> "ALLOW";
            case LEVEL_2_SOFT_NUDGE, LEVEL_3_STEP_UP_MFA -> "CHALLENGE";
            case LEVEL_4_AUTO_HOLD, LEVEL_5_TERMINATE -> "BLOCK";
        };
    }
}
