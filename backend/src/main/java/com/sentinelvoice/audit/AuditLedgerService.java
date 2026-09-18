package com.sentinelvoice.audit;

import com.sentinelvoice.config.SentinelProperties;
import com.sentinelvoice.model.AuditBlock;
import com.sentinelvoice.model.InterventionLevel;
import com.sentinelvoice.repository.AuditBlockRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-authoritative SHA-256 hash-chained audit ledger. Callers never supply {@code previousHash}.
 */
@Service
public class AuditLedgerService {

    private final AuditBlockRepository repository;
    private final CanonicalJson canonicalJson;
    private final SentinelProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final ConcurrentHashMap<String, Object> sessionLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ChainCursor> cursors = new ConcurrentHashMap<>();

    public AuditLedgerService(
            AuditBlockRepository repository,
            CanonicalJson canonicalJson,
            SentinelProperties properties,
            PlatformTransactionManager transactionManager
    ) {
        this.repository = repository;
        this.canonicalJson = canonicalJson;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public AuditBlock append(String sessionId, AuditEventType type, Map<String, Object> payload) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        if (type == null) {
            throw new IllegalArgumentException("event type is required");
        }
        Map<String, Object> safePayload = payload == null ? Map.of() : payload;
        synchronized (lockFor(sessionId)) {
            return transactionTemplate.execute(status -> persistLocked(sessionId, type, safePayload));
        }
    }

    public ChainVerificationResult verify(String sessionId) {
        List<AuditBlock> blocks = repository.findBySessionIdOrderByBlockIndexAsc(sessionId);
        if (blocks.isEmpty()) {
            return ChainVerificationResult.empty();
        }
        String genesisPrefix = properties.audit().genesisPrefix();
        for (int i = 0; i < blocks.size(); i++) {
            AuditBlock block = blocks.get(i);
            if (block.getBlockIndex() != i) {
                return ChainVerificationResult.broken(
                        blocks.size(),
                        i,
                        "blockIndex=" + i,
                        "blockIndex=" + block.getBlockIndex()
                );
            }
            String expectedPrevious;
            if (i == 0) {
                expectedPrevious = genesisPreviousHash(genesisPrefix, sessionId, block.getTsEpochMs());
            } else {
                expectedPrevious = blocks.get(i - 1).getCurrentHash();
            }
            if (!expectedPrevious.equals(block.getPreviousHash())) {
                return ChainVerificationResult.broken(
                        blocks.size(),
                        i,
                        expectedPrevious,
                        block.getPreviousHash()
                );
            }
            String canonicalPayload = canonicalJson.serialize(canonicalJson.deserialize(block.getDetails()));
            String expectedCurrent = hashBlock(
                    block.getPreviousHash(),
                    block.getTsEpochMs(),
                    block.getSessionId(),
                    block.getEventType(),
                    canonicalPayload
            );
            if (!expectedCurrent.equals(block.getCurrentHash())) {
                return ChainVerificationResult.broken(
                        blocks.size(),
                        i,
                        expectedCurrent,
                        block.getCurrentHash()
                );
            }
        }
        return ChainVerificationResult.ok(blocks.size());
    }

    public Page<AuditBlockView> chain(String sessionId, Pageable pageable) {
        return repository.findBySessionIdOrderByBlockIndexAsc(sessionId, pageable).map(this::toView);
    }

    public AuditBlockView toView(AuditBlock block) {
        return new AuditBlockView(
                AuditBlockView.SCHEMA,
                block.getSessionId(),
                block.getBlockIndex(),
                block.getTsEpochMs(),
                block.getEventType(),
                canonicalJson.deserialize(block.getDetails()),
                block.getSmoothedRisk(),
                block.getLevel(),
                block.getPreviousHash(),
                block.getCurrentHash()
        );
    }

    public static String genesisPreviousHash(String genesisPrefix, String sessionId, long createdAtEpochMs) {
        return sha256Hex(genesisPrefix + "|" + sessionId + "|" + createdAtEpochMs);
    }

    private AuditBlock persistLocked(String sessionId, AuditEventType type, Map<String, Object> payload) {
        ChainCursor cursor = cursors.computeIfAbsent(sessionId, this::loadCursor);
        long tsEpochMs = Instant.now().toEpochMilli();
        String previousHash = cursor.tailHash();
        if (previousHash == null) {
            previousHash = genesisPreviousHash(properties.audit().genesisPrefix(), sessionId, tsEpochMs);
        }
        String canonicalPayload = canonicalJson.serialize(payload);
        String currentHash = hashBlock(previousHash, tsEpochMs, sessionId, type.name(), canonicalPayload);

        AuditBlock block = new AuditBlock();
        block.setSessionId(sessionId);
        block.setBlockIndex(cursor.nextIndex());
        block.setEventType(type.name());
        block.setDetails(canonicalPayload);
        block.setTsEpochMs(tsEpochMs);
        block.setSmoothedRisk(extractSmoothedRisk(payload));
        block.setLevel(extractLevel(payload));
        block.setPreviousHash(previousHash);
        block.setCurrentHash(currentHash);
        AuditBlock saved = repository.saveAndFlush(block);
        cursors.put(sessionId, new ChainCursor(currentHash, cursor.nextIndex() + 1));
        return saved;
    }

    private ChainCursor loadCursor(String sessionId) {
        return repository.findTopBySessionIdOrderByBlockIndexDesc(sessionId)
                .map(block -> new ChainCursor(block.getCurrentHash(), block.getBlockIndex() + 1))
                .orElseGet(() -> new ChainCursor(null, 0));
    }

    private Object lockFor(String sessionId) {
        return sessionLocks.computeIfAbsent(sessionId, id -> new Object());
    }

    private static String hashBlock(
            String previousHash,
            long tsEpochMs,
            String sessionId,
            String eventType,
            String canonicalPayload
    ) {
        return sha256Hex(previousHash + "|" + tsEpochMs + "|" + sessionId + "|" + eventType + "|" + canonicalPayload);
    }

    static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", e);
        }
    }

    private static double extractSmoothedRisk(Map<String, Object> payload) {
        Object value = payload.get("smoothedRisk");
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return 0.0;
    }

    private static String extractLevel(Map<String, Object> payload) {
        Object value = payload.get("level");
        if (value instanceof String string && !string.isBlank()) {
            return string;
        }
        return InterventionLevel.LEVEL_1_SILENT.name();
    }

    private record ChainCursor(String tailHash, int nextIndex) {
    }
}
