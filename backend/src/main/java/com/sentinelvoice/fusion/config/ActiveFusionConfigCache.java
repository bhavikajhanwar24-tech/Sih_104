package com.sentinelvoice.fusion.config;

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

/**
 * Per-tenant Caffeine cache of ACTIVE fusion configs (F8).
 * Invalidated on {@link FusionConfigActivatedEvent}.
 */
@Component
public class ActiveFusionConfigCache {

    private static final Logger log = LoggerFactory.getLogger(ActiveFusionConfigCache.class);

    /** Matches platform_fusion_defaults.overridePinDurationMs seed (V015). */
    public static final long DEFAULT_OVERRIDE_PIN_MS = 120_000L;

    public record CachedFusionConfig(
            UUID id,
            int version,
            String contentSha256,
            FusionConfigDocument document,
            Instant loadedAt
    ) {
    }

    private final FusionConfigRepository repository;
    private final LoadingCache<UUID, Optional<CachedFusionConfig>> cache;

    public ActiveFusionConfigCache(FusionConfigRepository repository) {
        this.repository = repository;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(60))
                .maximumSize(2_000)
                .build(this::loadActive);
    }

    public Optional<CachedFusionConfig> get(UUID tenantId) {
        if (tenantId == null) {
            return Optional.empty();
        }
        return cache.get(tenantId);
    }

    public FusionConfigDocument requireDocument(UUID tenantId) {
        return get(tenantId)
                .map(CachedFusionConfig::document)
                .orElseThrow(() -> new FusionConfigException(
                        "NOT_FOUND", "No ACTIVE fusion config for tenant " + tenantId));
    }

    public void invalidate(UUID tenantId) {
        if (tenantId != null) {
            cache.invalidate(tenantId);
        }
    }

    @EventListener
    public void onActivated(FusionConfigActivatedEvent event) {
        log.info("fusion_cache_invalidate tenantId={} version={}", event.tenantId(), event.version());
        invalidate(event.tenantId());
        cache.get(event.tenantId());
    }

    private Optional<CachedFusionConfig> loadActive(UUID tenantId) {
        Optional<Map<String, Object>> active = repository.findActive(tenantId);
        if (active.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = active.get();
        UUID id = UUID.fromString(String.valueOf(row.get("id")));
        int version = ((Number) row.get("version")).intValue();
        String sha = row.get("contentSha256") == null ? "" : String.valueOf(row.get("contentSha256"));
        @SuppressWarnings("unchecked")
        Map<String, Object> config = row.get("config") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : Map.of();
        FusionConfigDocument doc = FusionConfigDocument.parse(config);
        log.info("fusion_cache_loaded tenantId={} version={}", tenantId, version);
        return Optional.of(new CachedFusionConfig(id, version, sha, doc, Instant.now()));
    }
}
