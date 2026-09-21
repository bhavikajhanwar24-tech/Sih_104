package com.sentinelvoice.service;

import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.audit.AuditLedgerService;
import com.sentinelvoice.config.SentinelProperties;
import com.sentinelvoice.fusion.config.ActiveFusionConfigCache;
import com.sentinelvoice.model.CallSession;
import com.sentinelvoice.model.SessionStartRequest;
import com.sentinelvoice.model.TelemetryEntry;
import com.sentinelvoice.policy.engine.ActivePolicyCache;
import com.sentinelvoice.response.ActiveResponsePlanCache;
import com.sentinelvoice.security.TenantContext;
import com.sentinelvoice.tenant.TenantSettingsEntity;
import com.sentinelvoice.tenant.TenantSettingsRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory call sessions keyed by sessionId, always carrying {@code tenantId}.
 * Cross-tenant access returns not-found (404 semantics).
 */
@Service
public class CallSessionManager {

    private final ConcurrentHashMap<String, CallSession> sessions = new ConcurrentHashMap<>();
    private final SentinelProperties properties;
    private final AuditLedgerService auditLedgerService;
    private final TenantSettingsRepository tenantSettingsRepository;
    private final ActiveFusionConfigCache fusionConfigCache;
    private final ActivePolicyCache policyCache;
    private final ActiveResponsePlanCache responsePlanCache;

    public CallSessionManager(
            SentinelProperties properties,
            AuditLedgerService auditLedgerService,
            TenantSettingsRepository tenantSettingsRepository,
            ActiveFusionConfigCache fusionConfigCache,
            ActivePolicyCache policyCache,
            ActiveResponsePlanCache responsePlanCache
    ) {
        this.properties = properties;
        this.auditLedgerService = auditLedgerService;
        this.tenantSettingsRepository = tenantSettingsRepository;
        this.fusionConfigCache = fusionConfigCache;
        this.policyCache = policyCache;
        this.responsePlanCache = responsePlanCache;
    }

    public CallSession createSession(SessionStartRequest request) {
        UUID tenantId = TenantContext.require().tenantId();
        int maxConcurrent = resolveMaxConcurrent(tenantId);
        synchronized (this) {
            if (countActiveForTenant(tenantId) >= maxConcurrent) {
                throw new IllegalStateException(
                        "session limit reached: maxConcurrent=" + maxConcurrent + " tenant=" + tenantId
                );
            }
            String sessionId = request.sessionId();
            if (sessionId == null || sessionId.isBlank()) {
                sessionId = UUID.randomUUID().toString();
            }
            if (sessions.containsKey(sessionId)) {
                throw new IllegalArgumentException("session already exists: " + sessionId);
            }
            CallSession session = new CallSession(
                    tenantId,
                    sessionId,
                    request.callerId(),
                    request.calleeId(),
                    request.channelProfile(),
                    request.scenarioId()
            );

            Optional<ActiveFusionConfigCache.CachedFusionConfig> fusion =
                    fusionConfigCache.get(tenantId);
            fusion.ifPresent(c -> {
                session.setFusionConfigVersion(c.version());
                session.setFusionConfigSnapshot(c.document());
            });
            policyCache.get(tenantId).ifPresent(p -> session.setPolicyVersion(p.version()));
            Optional<ActiveResponsePlanCache.CachedResponsePlan> plan = responsePlanCache.get(tenantId);
            if (plan.isPresent()) {
                session.setResponsePlanVersion(plan.get().version());
                session.setResponsePlanSnapshot(plan.get().document());
            } else {
                session.setResponsePlanVersion(null);
                session.setResponsePlanSnapshot(com.sentinelvoice.response.ResponsePlanDocument.emergencyPlan());
            }

            sessions.put(sessionId, session);
            try {
                Map<String, Object> payload = openPayload(session);
                auditLedgerService.append(
                        tenantId,
                        sessionId,
                        AuditEventType.SESSION_OPENED,
                        "USER",
                        TenantContext.require().userId() == null
                                ? null
                                : TenantContext.require().userId().toString(),
                        payload
                );
                auditLedgerService.append(
                        tenantId,
                        sessionId,
                        AuditEventType.SESSION_CONFIG_SNAPSHOT,
                        "SYSTEM",
                        null,
                        Map.of(
                                "fusionConfigVersion", session.getFusionConfigVersion() == null
                                        ? "" : session.getFusionConfigVersion(),
                                "policyVersion", session.getPolicyVersion() == null
                                        ? "" : session.getPolicyVersion(),
                                "responsePlanVersion", session.getResponsePlanVersion() == null
                                        ? "" : session.getResponsePlanVersion()
                        )
                );
            } catch (RuntimeException ex) {
                sessions.remove(sessionId);
                throw ex;
            }
            return session;
        }
    }

    /** ML ingest / internal: resolve by sessionId only (tenant taken from the session). */
    public Optional<CallSession> getSession(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    /** API access: 404 if missing or wrong tenant. */
    public CallSession requireSessionForTenant(UUID tenantId, String sessionId) {
        CallSession session = sessions.get(sessionId);
        if (session == null || !session.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found");
        }
        return session;
    }

    public CallSession requireSession(String sessionId) {
        UUID tenantId = TenantContext.get() == null ? null : TenantContext.get().tenantId();
        if (tenantId != null) {
            return requireSessionForTenant(tenantId, sessionId);
        }
        return getSession(sessionId).orElseThrow(
                () -> new NoSuchElementException("session not found: " + sessionId)
        );
    }

    public List<CallSession> listSessionsForTenant(UUID tenantId) {
        List<CallSession> copy = new ArrayList<>();
        for (CallSession s : sessions.values()) {
            if (s.getTenantId().equals(tenantId)) {
                copy.add(s);
            }
        }
        copy.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));
        return List.copyOf(copy);
    }

    /** @deprecated use {@link #listSessionsForTenant(UUID)} */
    public List<CallSession> listSessions() {
        TenantContext ctx = TenantContext.get();
        if (ctx != null) {
            return listSessionsForTenant(ctx.tenantId());
        }
        return List.of();
    }

    public void recordTelemetry(String sessionId, TelemetryEntry entry) {
        requireSession(sessionId).recordTelemetry(entry);
    }

    public void closeSession(String sessionId) {
        closeSession(sessionId, "closed");
    }

    public int activeSessionCount() {
        return sessions.size();
    }

    public int countActiveForTenant(UUID tenantId) {
        int n = 0;
        for (CallSession s : sessions.values()) {
            if (s.getTenantId().equals(tenantId)) {
                n++;
            }
        }
        return n;
    }

    public List<String> evictIdleSessions() {
        Instant cutoff = Instant.now().minus(properties.session().ttlMinutes(), ChronoUnit.MINUTES);
        List<String> idle = new ArrayList<>();
        for (CallSession session : sessions.values()) {
            if (session.getLastFrameAt().isBefore(cutoff)) {
                idle.add(session.getSessionId());
            }
        }
        for (String sessionId : idle) {
            closeSession(sessionId, "idle_ttl_exceeded");
        }
        return List.copyOf(idle);
    }

    private void closeSession(String sessionId, String reason) {
        CallSession session = sessions.remove(sessionId);
        if (session == null) {
            return;
        }
        session.close();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", reason);
        payload.put("smoothedRisk", session.getSmoothedRisk());
        payload.put("level", session.getCurrentLevel().name());
        TenantContext.runAs(session.getTenantId(), () ->
                auditLedgerService.append(
                        session.getTenantId(),
                        sessionId,
                        AuditEventType.SESSION_CLOSED,
                        "SYSTEM",
                        null,
                        payload
                )
        );
    }

    private int resolveMaxConcurrent(UUID tenantId) {
        return tenantSettingsRepository.findById(tenantId)
                .map(TenantSettingsEntity::getMaxConcurrentCalls)
                .orElse(20);
    }

    private static Map<String, Object> openPayload(CallSession session) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("callerId", session.getCallerId());
        payload.put("calleeId", session.getCalleeId());
        payload.put("channelProfile", session.getChannelProfile().name());
        payload.put("smoothedRisk", 0.0);
        payload.put("level", session.getCurrentLevel().name());
        payload.put("fusionConfigVersion", session.getFusionConfigVersion());
        payload.put("policyVersion", session.getPolicyVersion());
        payload.put("responsePlanVersion", session.getResponsePlanVersion());
        if (session.getScenarioId() != null) {
            payload.put("scenarioId", session.getScenarioId());
        }
        return payload;
    }
}
