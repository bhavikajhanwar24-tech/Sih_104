package com.sentinelvoice.audit;

import com.sentinelvoice.model.AuditBlock;
import com.sentinelvoice.repository.AuditBlockRepository;
import com.sentinelvoice.tenant.BootstrapTenant;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-tenant SHA-256 hash-chained audit ledger.
 *
 * <p>Hash rule (F1):
 * {@code SHA-256(prev_hash || seq || event_type || canonical_json(payload) || created_at ISO-8601)}.
 * Appends are serialised per tenant with {@code pg_advisory_xact_lock(hashtext(tenant_id::text))}.
 */
@Service
public class AuditLedgerService {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    private final AuditBlockRepository repository;
    private final CanonicalJson canonicalJson;
    private final TransactionTemplate transactionTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    public AuditLedgerService(
            AuditBlockRepository repository,
            CanonicalJson canonicalJson,
            PlatformTransactionManager transactionManager
    ) {
        this.repository = repository;
        this.canonicalJson = canonicalJson;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Append under the bootstrap tenant (pre-F2). {@code sessionId} is stored inside payload.
     */
    public AuditBlock append(String sessionId, AuditEventType type, Map<String, Object> payload) {
        return append(
                BootstrapTenant.ID,
                sessionId,
                type,
                "SYSTEM",
                null,
                payload
        );
    }

    public AuditBlock append(
            UUID tenantId,
            String sessionId,
            AuditEventType type,
            String actorType,
            String actorId,
            Map<String, Object> payload
    ) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (type == null) {
            throw new IllegalArgumentException("event type is required");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        if (payload != null) {
            body.putAll(payload);
        }
        if (sessionId != null && !sessionId.isBlank()) {
            body.putIfAbsent("sessionId", sessionId);
        }
        String safeActorType = actorType == null || actorType.isBlank() ? "SYSTEM" : actorType;
        return transactionTemplate.execute(status ->
                persistLocked(tenantId, type, safeActorType, actorId, body));
    }

    /**
     * Verify the full chain for a tenant.
     *
     * @return {@code valid}, {@code blocksChecked}, {@code firstBrokenSeq} (null if valid / empty)
     */
    @Transactional(readOnly = true)
    public TenantChainVerification verifyTenant(UUID tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        List<AuditBlock> blocks = repository.findByTenantIdOrderBySeqAsc(tenantId);
        if (blocks.isEmpty()) {
            return TenantChainVerification.empty();
        }
        for (int i = 0; i < blocks.size(); i++) {
            AuditBlock block = blocks.get(i);
            long expectedSeq = i + 1L; // seq is 1-based
            if (block.getSeq() != expectedSeq) {
                return TenantChainVerification.broken(blocks.size(), block.getSeq());
            }
            String expectedPrev = i == 0
                    ? BootstrapTenant.GENESIS_PREV_HASH
                    : blocks.get(i - 1).getHash();
            if (!expectedPrev.equals(block.getPrevHash())) {
                return TenantChainVerification.broken(blocks.size(), block.getSeq());
            }
            String expectedHash = computeHash(
                    block.getPrevHash(),
                    block.getSeq(),
                    block.getEventType(),
                    canonicalJson.serialize(block.getPayload()),
                    block.getCreatedAt()
            );
            if (!expectedHash.equals(block.getHash())) {
                return TenantChainVerification.broken(blocks.size(), block.getSeq());
            }
        }
        return TenantChainVerification.ok(blocks.size());
    }

    /** @deprecated session-scoped verify; prefer {@link #verifyTenant(UUID)}. */
    public ChainVerificationResult verify(String sessionId) {
        TenantChainVerification v = verifyTenant(BootstrapTenant.ID);
        if (v.blocksChecked() == 0) {
            return ChainVerificationResult.empty();
        }
        if (v.valid()) {
            return ChainVerificationResult.ok(v.blocksChecked());
        }
        int broken = v.firstBrokenSeq() == null ? 0 : v.firstBrokenSeq().intValue();
        return ChainVerificationResult.broken(v.blocksChecked(), broken, "chain", "mismatch");
    }

    public List<AuditBlock> listTenant(UUID tenantId) {
        return repository.findByTenantIdOrderBySeqAsc(tenantId);
    }

    public AuditBlockView toView(AuditBlock block) {
        Map<String, Object> payload = block.getPayload() == null ? Map.of() : block.getPayload();
        String sessionId = block.sessionIdFromPayload();
        if (sessionId == null) {
            sessionId = "";
        }
        double smoothedRisk = 0.0;
        Object risk = payload.get("smoothedRisk");
        if (risk instanceof Number number) {
            smoothedRisk = number.doubleValue();
        }
        Object levelObj = payload.get("level");
        String level = levelObj == null ? "LEVEL_1_SILENT" : String.valueOf(levelObj);
        return new AuditBlockView(
                AuditBlockView.SCHEMA,
                sessionId,
                (int) Math.min(block.getSeq(), Integer.MAX_VALUE),
                block.getCreatedAt().toEpochMilli(),
                block.getEventType(),
                payload,
                smoothedRisk,
                level,
                block.getPrevHash(),
                block.getHash()
        );
    }

    private AuditBlock persistLocked(
            UUID tenantId,
            AuditEventType type,
            String actorType,
            String actorId,
            Map<String, Object> payload
    ) {
        entityManager.createNativeQuery(
                        "SELECT pg_advisory_xact_lock(hashtext(CAST(:tid AS text)))")
                .setParameter("tid", tenantId.toString())
                .getResultList();

        var tip = repository.findTopByTenantIdOrderBySeqDesc(tenantId);
        long nextSeq = tip.map(b -> b.getSeq() + 1L).orElse(1L);
        String prevHash = tip.map(AuditBlock::getHash).orElse(BootstrapTenant.GENESIS_PREV_HASH);

        Instant createdAt = Instant.now();
        String canonicalPayload = canonicalJson.serialize(payload);
        String hash = computeHash(prevHash, nextSeq, type.name(), canonicalPayload, createdAt);

        AuditBlock block = new AuditBlock();
        block.setId(UUID.randomUUID());
        block.setTenantId(tenantId);
        block.setSeq(nextSeq);
        block.setPrevHash(prevHash);
        block.setHash(hash);
        block.setEventType(type.name());
        block.setActorType(actorType);
        block.setActorId(actorId);
        block.setPayload(payload);
        block.setCreatedAt(createdAt);
        return repository.saveAndFlush(block);
    }

    static String computeHash(
            String prevHash,
            long seq,
            String eventType,
            String canonicalPayload,
            Instant createdAt
    ) {
        String createdIso = ISO.format(createdAt);
        return sha256Hex(prevHash + seq + eventType + canonicalPayload + createdIso);
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
}
