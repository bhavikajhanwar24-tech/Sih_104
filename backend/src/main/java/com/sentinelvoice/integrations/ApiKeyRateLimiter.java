package com.sentinelvoice.integrations;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-key token-bucket rate limiter (in-memory).
 */
@Component
public class ApiKeyRateLimiter {

    private final ConcurrentHashMap<UUID, Bucket> buckets = new ConcurrentHashMap<>();

    public boolean tryConsume(UUID keyId, int rateLimitRps) {
        int rps = Math.max(1, Math.min(rateLimitRps <= 0 ? 60 : rateLimitRps, 1000));
        Bucket b = buckets.computeIfAbsent(keyId, id -> new Bucket(rps));
        b.configure(rps);
        return b.tryConsume();
    }

    private static final class Bucket {
        private double tokens;
        private double capacity;
        private double refillPerMs;
        private long lastRefillMs;

        Bucket(int rps) {
            configure(rps);
            this.tokens = capacity;
            this.lastRefillMs = System.currentTimeMillis();
        }

        synchronized void configure(int rps) {
            this.capacity = rps;
            this.refillPerMs = rps / 1000.0;
            if (tokens > capacity) {
                tokens = capacity;
            }
        }

        synchronized boolean tryConsume() {
            long now = System.currentTimeMillis();
            double elapsed = now - lastRefillMs;
            if (elapsed > 0) {
                tokens = Math.min(capacity, tokens + elapsed * refillPerMs);
                lastRefillMs = now;
            }
            if (tokens < 1.0) {
                return false;
            }
            tokens -= 1.0;
            return true;
        }
    }
}
