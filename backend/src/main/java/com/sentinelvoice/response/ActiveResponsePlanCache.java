package com.sentinelvoice.response;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
public class ActiveResponsePlanCache {

    private static final Logger log = LoggerFactory.getLogger(ActiveResponsePlanCache.class);

    public record CachedResponsePlan(
            UUID id,
            int version,
            String contentSha256,
            ResponsePlanDocument document,
            Instant loadedAt
    ) {
    }

    private final ResponsePlanRepository repository;
    private final LoadingCache<UUID, Optional<CachedResponsePlan>> cache;

    public ActiveResponsePlanCache(ResponsePlanRepository repository) {
        this.repository = repository;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(60))
                .maximumSize(2_000)
                .build(this::loadActive);
    }

    public Optional<CachedResponsePlan> get(UUID tenantId) {
        if (tenantId == null) {
            return Optional.empty();
        }
        return cache.get(tenantId);
    }

    public ResponsePlanDocument requireDocumentOrEmergency(UUID tenantId) {
        return get(tenantId)
                .map(CachedResponsePlan::document)
                .orElseGet(ResponsePlanDocument::emergencyPlan);
    }

    public void invalidate(UUID tenantId) {
        if (tenantId != null) {
            cache.invalidate(tenantId);
        }
    }

    @EventListener
    public void onActivated(ResponsePlanActivatedEvent event) {
        log.info("response_plan_cache_invalidate tenantId={} version={}", event.tenantId(), event.version());
        invalidate(event.tenantId());
        cache.get(event.tenantId());
    }

    private Optional<CachedResponsePlan> loadActive(UUID tenantId) {
        Optional<Map<String, Object>> active = repository.findActive(tenantId);
        if (active.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = active.get();
        UUID id = UUID.fromString(String.valueOf(row.get("id")));
        int version = ((Number) row.get("version")).intValue();
        String sha = row.get("contentSha256") == null ? "" : String.valueOf(row.get("contentSha256"));
        @SuppressWarnings("unchecked")
        Map<String, Object> plan = row.get("plan") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : Map.of();
        ResponsePlanDocument doc = ResponsePlanDocument.parse(plan);
        log.info("response_plan_cache_loaded tenantId={} version={}", tenantId, version);
        return Optional.of(new CachedResponsePlan(id, version, sha, doc, Instant.now()));
    }
}
