package com.sentinelvoice.policy;

import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.audit.AuditLedgerService;
import com.sentinelvoice.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class PolicyDocumentService {

    private static final Logger log = LoggerFactory.getLogger(PolicyDocumentService.class);
    private static final long MAX_BYTES = 15L * 1024 * 1024;
    private static final Set<String> DOC_TYPES = Set.of("POLICY", "SOP", "COMPLIANCE", "FAQ", "OTHER");

    private final PolicyDocumentRepository documentRepository;
    private final PolicyDocumentChunkRepository chunkRepository;
    private final PolicyDocumentExtractor extractor;
    private final AuditLedgerService auditLedgerService;
    private final AsyncTaskExecutor policyExtractionExecutor;
    private final TransactionTemplate transactionTemplate;
    private final PolicyRuleCitationRepository citationRepository;

    public PolicyDocumentService(
            PolicyDocumentRepository documentRepository,
            PolicyDocumentChunkRepository chunkRepository,
            PolicyDocumentExtractor extractor,
            AuditLedgerService auditLedgerService,
            @Qualifier("policyExtractionExecutor") AsyncTaskExecutor policyExtractionExecutor,
            PlatformTransactionManager transactionManager,
            PolicyRuleCitationRepository citationRepository
    ) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.extractor = extractor;
        this.auditLedgerService = auditLedgerService;
        this.policyExtractionExecutor = policyExtractionExecutor;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.citationRepository = citationRepository;
    }

    /**
     * @param includeArchived when true, return active + archived; when false, active only
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(UUID tenantId, boolean includeArchived) {
        List<PolicyDocumentEntity> rows = includeArchived
                ? documentRepository.findByTenantIdOrderByUploadedAtDesc(tenantId)
                : documentRepository.findByTenantIdAndStatusNotOrderByUploadedAtDesc(tenantId, "ARCHIVED");
        return rows.stream().map(this::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listArchivedOnly(UUID tenantId) {
        return documentRepository.findByTenantIdAndStatusOrderByUploadedAtDesc(tenantId, "ARCHIVED")
                .stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(UUID tenantId, UUID id) {
        PolicyDocumentEntity doc = require(tenantId, id);
        Map<String, Object> out = toSummary(doc);
        out.put("extractionError", doc.getExtractionError());
        out.put("languageDetected", doc.getLanguageDetected());
        out.put("textSha256", doc.getTextSha256());
        out.put("hasExtractedText", doc.getExtractedText() != null && !doc.getExtractedText().isBlank());
        out.put("extractedTextPreview", preview(doc.getExtractedText(), 2000));
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> chunks(UUID tenantId, UUID documentId) {
        require(tenantId, documentId);
        return chunkRepository.findByTenantIdAndDocumentIdOrderByOrdinalAsc(tenantId, documentId).stream()
                .map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", c.getId().toString());
                    m.put("ordinal", c.getOrdinal());
                    m.put("headingPath", c.getHeadingPath());
                    m.put("text", c.getText());
                    m.put("charStart", c.getCharStart());
                    m.put("charEnd", c.getCharEnd());
                    m.put("pageNo", c.getPageNo());
                    m.put("injectionFlags", c.getInjectionFlags() == null ? List.of() : c.getInjectionFlags());
                    m.put(
                            "hasInjectionFlags",
                            c.getInjectionFlags() != null && !c.getInjectionFlags().isEmpty()
                    );
                    return m;
                })
                .toList();
    }

    @Transactional
    public Map<String, Object> upload(
            UUID tenantId,
            UUID userId,
            MultipartFile file,
            String title,
            String docType
    ) {
        if (file == null || file.isEmpty()) {
            throw new PolicyDocumentException("EMPTY_FILE", "Upload is empty");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new PolicyDocumentException("TOO_LARGE", "File exceeds 15 MB limit");
        }
        String type = docType == null ? "POLICY" : docType.trim().toUpperCase(Locale.ROOT);
        if (!DOC_TYPES.contains(type)) {
            throw new PolicyDocumentException("BAD_DOC_TYPE", "docType must be one of " + DOC_TYPES);
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            throw new PolicyDocumentException("READ_FAILED", "Could not read upload");
        }
        String filename = file.getOriginalFilename() == null ? "upload.bin" : file.getOriginalFilename();
        String mime = extractor.sniffMime(bytes);
        extractor.assertExtensionMatchesDetected(filename, mime);
        String sha = sha256Hex(bytes);
        var existing = documentRepository.findByTenantIdAndSha256(tenantId, sha);
        if (existing.isPresent()) {
            PolicyDocumentEntity prev = existing.get();
            if ("ARCHIVED".equals(prev.getStatus())) {
                // Same bytes as an archived doc — restore instead of failing the unique constraint.
                return restore(tenantId, userId, prev.getId(), title, type, filename, mime, bytes);
            }
            throw new PolicyDocumentException("DUPLICATE", "Identical document already uploaded (sha256 match)");
        }

        PolicyDocumentEntity doc = new PolicyDocumentEntity();
        doc.setId(UUID.randomUUID());
        doc.setTenantId(tenantId);
        doc.setTitle(title == null || title.isBlank() ? stripExt(filename) : title.trim());
        doc.setDocType(type);
        doc.setOriginalFilename(filename);
        doc.setMimeType(mime);
        doc.setSizeBytes(bytes.length);
        doc.setSha256(sha);
        doc.setContent(bytes);
        doc.setStatus("UPLOADED");
        doc.setUploadedBy(userId);
        doc.setUploadedAt(Instant.now());
        doc.setInjectionFlags(List.of());
        try {
            documentRepository.saveAndFlush(doc);
        } catch (DataIntegrityViolationException ex) {
            throw new PolicyDocumentException("DUPLICATE", "Identical document already uploaded (sha256 match)");
        }

        audit(tenantId, userId, AuditEventType.POLICY_DOCUMENT_UPLOADED, Map.of(
                "documentId", doc.getId().toString(),
                "sha256", sha,
                "mimeType", mime,
                "sizeBytes", bytes.length,
                "docType", type
        ));

        UUID docId = doc.getId();
        scheduleExtractionAfterCommit(tenantId, docId);

        return toSummary(doc);
    }

    @Transactional
    public Map<String, Object> archive(UUID tenantId, UUID userId, UUID id) {
        PolicyDocumentEntity doc = require(tenantId, id);
        if ("ARCHIVED".equals(doc.getStatus())) {
            return toSummary(doc);
        }
        doc.setStatus("ARCHIVED");
        documentRepository.save(doc);
        audit(tenantId, userId, AuditEventType.POLICY_DOCUMENT_ARCHIVED, Map.of(
                "documentId", id.toString()
        ));
        return toSummary(doc);
    }

    /**
     * Bring an archived document back. If extracted text exists → EXTRACTED (Ready);
     * otherwise re-queue extraction from UPLOADED.
     */
    @Transactional
    public Map<String, Object> restore(UUID tenantId, UUID userId, UUID id) {
        PolicyDocumentEntity doc = require(tenantId, id);
        if (!"ARCHIVED".equals(doc.getStatus())) {
            throw new PolicyDocumentException("NOT_ARCHIVED", "Document is not archived");
        }
        return restore(tenantId, userId, id, null, null, null, null, null);
    }

    private Map<String, Object> restore(
            UUID tenantId,
            UUID userId,
            UUID id,
            String title,
            String docType,
            String filename,
            String mime,
            byte[] bytes
    ) {
        PolicyDocumentEntity doc = require(tenantId, id);
        if (title != null && !title.isBlank()) {
            doc.setTitle(title.trim());
        }
        if (docType != null && !docType.isBlank()) {
            doc.setDocType(docType.trim().toUpperCase(Locale.ROOT));
        }
        if (filename != null) {
            doc.setOriginalFilename(filename);
        }
        if (mime != null) {
            doc.setMimeType(mime);
        }
        if (bytes != null) {
            doc.setContent(bytes);
            doc.setSizeBytes(bytes.length);
            doc.setSha256(sha256Hex(bytes));
        }
        boolean hasText = doc.getExtractedText() != null && !doc.getExtractedText().isBlank();
        long chunkCount = chunkRepository.countByTenantIdAndDocumentId(tenantId, id);
        if (hasText && chunkCount > 0 && bytes == null) {
            doc.setStatus("EXTRACTED");
            doc.setExtractionError(null);
        } else {
            doc.setStatus("UPLOADED");
            doc.setExtractionError(null);
            doc.setInjectionFlags(List.of());
        }
        doc.setUploadedBy(userId);
        doc.setUploadedAt(Instant.now());
        documentRepository.save(doc);

        audit(tenantId, userId, AuditEventType.POLICY_DOCUMENT_RESTORED, Map.of(
                "documentId", id.toString(),
                "status", doc.getStatus()
        ));

        if ("UPLOADED".equals(doc.getStatus())) {
            scheduleExtractionAfterCommit(tenantId, id);
        }
        return toSummary(doc);
    }

    /**
     * Re-queue extraction for UPLOADED / FAILED documents (e.g. after a race left them stuck).
     */
    @Transactional
    public Map<String, Object> reextract(UUID tenantId, UUID userId, UUID id) {
        PolicyDocumentEntity doc = require(tenantId, id);
        if ("ARCHIVED".equals(doc.getStatus())) {
            throw new PolicyDocumentException("ARCHIVED", "Restore the document before re-extracting");
        }
        doc.setStatus("UPLOADED");
        doc.setExtractionError(null);
        documentRepository.save(doc);
        scheduleExtractionAfterCommit(tenantId, id);
        audit(tenantId, userId, AuditEventType.POLICY_DOCUMENT_UPLOADED, Map.of(
                "documentId", id.toString(),
                "reextract", true
        ));
        return toSummary(doc);
    }

    /**
     * Must run only after the upload/restore transaction commits, otherwise the async
     * worker can miss the row under RLS / READ COMMITTED and leave status stuck on UPLOADED.
     */
    private void scheduleExtractionAfterCommit(UUID tenantId, UUID documentId) {
        Runnable job = () -> policyExtractionExecutor.execute(() -> {
            try {
                runExtraction(tenantId, documentId);
            } catch (Exception ex) {
                log.error("policy_extraction_dispatch_failed documentId={} cause={}", documentId, ex.toString(), ex);
            }
        });
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    job.run();
                }
            });
        } else {
            job.run();
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> deleteBlockers(UUID tenantId, UUID id) {
        PolicyDocumentEntity doc = require(tenantId, id);
        if (!"ARCHIVED".equals(doc.getStatus())) {
            throw new PolicyDocumentException("NOT_ARCHIVED", "Archive the document first");
        }
        List<Map<String, Object>> blocking = citationRepository.findBlockingCitations(tenantId, id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("documentId", id.toString());
        out.put("blockingRules", blocking);
        out.put("blocked", !blocking.isEmpty());
        return out;
    }

    /**
     * Soft-archive (default DELETE) or permanent delete when {@code permanent=true}.
     */
    @Transactional
    public Map<String, Object> delete(UUID tenantId, UUID userId, UUID id, boolean permanent) {
        if (!permanent) {
            return archive(tenantId, userId, id);
        }
        return permanentDelete(tenantId, userId, id);
    }

    @Transactional
    public Map<String, Object> permanentDelete(UUID tenantId, UUID userId, UUID id) {
        PolicyDocumentEntity doc = require(tenantId, id);
        if (!"ARCHIVED".equals(doc.getStatus())) {
            throw new PolicyDocumentException("NOT_ARCHIVED", "Archive the document first");
        }
        List<Map<String, Object>> blocking;
        try {
            blocking = citationRepository.findBlockingCitations(tenantId, id);
        } catch (Exception ex) {
            log.warn("citation_check_failed documentId={} cause={}", id, ex.toString());
            blocking = List.of();
        }
        if (!blocking.isEmpty()) {
            throw PolicyDocumentException.cited(blocking);
        }

        try {
            citationRepository.markSourceDeleted(tenantId, id);
        } catch (Exception ex) {
            log.warn("mark_source_deleted_failed documentId={} cause={}", id, ex.toString());
        }

        long chunkCount = chunkRepository.countByTenantIdAndDocumentId(tenantId, id);
        String title = doc.getTitle();
        String originalFilename = doc.getOriginalFilename();
        String sha = doc.getSha256();
        long sizeBytes = doc.getSizeBytes();

        chunkRepository.deleteByTenantIdAndDocumentId(tenantId, id);
        documentRepository.deleteById(id);
        documentRepository.flush();

        audit(tenantId, userId, AuditEventType.POLICY_DOCUMENT_DELETED, Map.of(
                "documentId", id.toString(),
                "title", title == null ? "" : title,
                "originalFilename", originalFilename == null ? "" : originalFilename,
                "sha256", sha == null ? "" : sha,
                "sizeBytes", sizeBytes,
                "deletedBy", userId.toString(),
                "chunkCount", chunkCount
        ));

        log.info("policy_document_permanently_deleted tenantId={} documentId={} chunks={}", tenantId, id, chunkCount);

        return Map.of(
                "deleted", true,
                "id", id.toString(),
                "chunkCount", chunkCount
        );
    }

    /**
     * Bulk permanent delete — one audit event per document. Continues collecting results.
     */
    @Transactional
    public Map<String, Object> permanentDeleteBulk(UUID tenantId, UUID userId, List<UUID> ids) {
        List<Map<String, Object>> deleted = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        for (UUID id : ids) {
            try {
                deleted.add(permanentDelete(tenantId, userId, id));
            } catch (PolicyDocumentException ex) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("id", id.toString());
                err.put("code", ex.getCode());
                err.put("message", ex.getMessage());
                if (!ex.getDetails().isEmpty()) {
                    err.putAll(ex.getDetails());
                }
                failed.add(err);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deleted", deleted);
        out.put("failed", failed);
        return out;
    }

    @Transactional(readOnly = true)
    public PolicyDocumentEntity requireForDownload(UUID tenantId, UUID id) {
        return require(tenantId, id);
    }

    public void auditDownload(UUID tenantId, UUID userId, UUID id) {
        audit(tenantId, userId, AuditEventType.POLICY_DOCUMENT_DOWNLOADED, Map.of(
                "documentId", id.toString()
        ));
    }

    void runExtraction(UUID tenantId, UUID documentId) {
        log.info("policy_extraction_start tenantId={} documentId={}", tenantId, documentId);
        long t0 = System.nanoTime();
        try {
            TenantContext.runAs(tenantId, () -> {
                transactionTemplate.executeWithoutResult(status -> extractInTxn(tenantId, documentId));
                return null;
            });
            log.info(
                    "policy_extraction_done tenantId={} documentId={} elapsedMs={}",
                    tenantId,
                    documentId,
                    (System.nanoTime() - t0) / 1_000_000L
            );
        } catch (Exception ex) {
            log.error("policy_extraction_failed documentId={} cause={}", documentId, ex.toString(), ex);
            TenantContext.runAs(tenantId, () -> {
                transactionTemplate.executeWithoutResult(status -> {
                    PolicyDocumentEntity doc = documentRepository.findByTenantIdAndId(tenantId, documentId).orElse(null);
                    if (doc != null && !"ARCHIVED".equals(doc.getStatus())) {
                        fail(doc, "EXTRACTION_FAILED: " + ex.getMessage());
                    }
                });
                return null;
            });
        }
    }

    private void extractInTxn(UUID tenantId, UUID documentId) {
        PolicyDocumentEntity doc = documentRepository.findByTenantIdAndId(tenantId, documentId).orElse(null);
        if (doc == null) {
            log.warn("policy_extraction_skip documentId={} reason=not_visible_yet_or_missing", documentId);
            return;
        }
        if ("ARCHIVED".equals(doc.getStatus())) {
            log.info("policy_extraction_skip documentId={} reason=archived", documentId);
            return;
        }
        try {
            PolicyDocumentExtractor.ExtractionResult result = extractor.extract(doc.getContent(), doc.getMimeType());
            String text = PolicyTextChunker.stripControlChars(result.text());
            boolean pdf = PolicyDocumentExtractor.isPaginatedMime(doc.getMimeType());
            List<PolicyTextChunker.ChunkDraft> drafts = PolicyTextChunker.chunk(
                    text,
                    pdf,
                    result.tables()
            );

            chunkRepository.deleteByTenantIdAndDocumentId(tenantId, documentId);
            List<PolicyDocumentChunkEntity> entities = new ArrayList<>();
            List<Map<String, Object>> docFlags = new ArrayList<>();
            for (PolicyTextChunker.ChunkDraft d : drafts) {
                UUID chunkId = UUID.randomUUID();
                List<PolicyTextChunker.InjectionFlag> matches =
                        PolicyTextChunker.scanInjectionFlags(d.text());
                List<Map<String, Object>> chunkFlags = new ArrayList<>();
                for (PolicyTextChunker.InjectionFlag flag : matches) {
                    chunkFlags.add(flag.toMap());
                    Map<String, Object> docFlag = new LinkedHashMap<>();
                    docFlag.put("chunkId", chunkId.toString());
                    docFlag.put("phrase", flag.phrase());
                    docFlag.put("charStart", flag.charStart());
                    docFlag.put("charEnd", flag.charEnd());
                    docFlags.add(docFlag);
                }
                PolicyDocumentChunkEntity c = new PolicyDocumentChunkEntity();
                c.setId(chunkId);
                c.setTenantId(tenantId);
                c.setDocumentId(documentId);
                c.setOrdinal(d.ordinal());
                c.setHeadingPath(d.headingPath());
                c.setText(d.text());
                c.setCharStart(d.charStart());
                c.setCharEnd(d.charEnd());
                c.setPageNo(pdf ? resolvePageNo(text, d.charStart(), d.pageNo()) : null);
                c.setInjectionFlags(chunkFlags);
                entities.add(c);
            }
            for (int i = 0; i < entities.size(); i++) {
                entities.get(i).setOrdinal(i);
            }
            chunkRepository.saveAll(entities);

            doc.setExtractedText(text);
            doc.setTextSha256(sha256Hex(text.getBytes(StandardCharsets.UTF_8)));
            doc.setLanguageDetected(PolicyTextChunker.detectLanguageHeuristic(text));
            doc.setInjectionFlags(docFlags);
            doc.setExtractionError(null);
            doc.setStatus("EXTRACTED");
            documentRepository.save(doc);

            audit(tenantId, doc.getUploadedBy(), AuditEventType.POLICY_DOCUMENT_EXTRACTED, Map.of(
                    "documentId", documentId.toString(),
                    "chunkCount", entities.size(),
                    "injectionFlagCount", docFlags.size()
            ));
        } catch (PolicyDocumentException ex) {
            fail(doc, ex.getCode() + ": " + ex.getMessage());
        } catch (Exception ex) {
            log.warn("policy_extraction_unexpected documentId={} cause={}", documentId, ex.toString());
            fail(doc, "EXTRACTION_FAILED: " + ex.getMessage());
        }
    }

    private void fail(PolicyDocumentEntity doc, String error) {
        doc.setStatus("FAILED");
        doc.setExtractionError(error);
        documentRepository.save(doc);
        audit(doc.getTenantId(), doc.getUploadedBy(), AuditEventType.POLICY_DOCUMENT_EXTRACTION_FAILED, Map.of(
                "documentId", doc.getId().toString(),
                "error", error
        ));
    }

    private static Integer resolvePageNo(String fullText, int charStart, Integer hinted) {
        if (hinted != null) {
            return hinted;
        }
        if (fullText == null || charStart <= 0) {
            return 1;
        }
        int page = 1;
        int limit = Math.min(charStart, fullText.length());
        for (int i = 0; i < limit; i++) {
            if (fullText.charAt(i) == '\f') {
                page++;
            }
        }
        return page;
    }

    private PolicyDocumentEntity require(UUID tenantId, UUID id) {
        return documentRepository.findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> new PolicyDocumentException("NOT_FOUND", "Document not found"));
    }

    private Map<String, Object> toSummary(PolicyDocumentEntity doc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", doc.getId().toString());
        m.put("title", doc.getTitle());
        m.put("docType", doc.getDocType());
        m.put("originalFilename", doc.getOriginalFilename());
        m.put("mimeType", doc.getMimeType());
        m.put("sizeBytes", doc.getSizeBytes());
        m.put("sha256", doc.getSha256());
        m.put("status", doc.getStatus());
        m.put("uploadedBy", doc.getUploadedBy() == null ? null : doc.getUploadedBy().toString());
        m.put("uploadedAt", doc.getUploadedAt() == null ? null : doc.getUploadedAt().toString());
        m.put("extractionError", doc.getExtractionError());
        m.put("injectionFlags", doc.getInjectionFlags());
        m.put("hasInjectionFlags", doc.getInjectionFlags() != null && !doc.getInjectionFlags().isEmpty());
        return m;
    }

    private void audit(UUID tenantId, UUID userId, AuditEventType type, Map<String, Object> payload) {
        auditLedgerService.append(
                tenantId,
                null,
                type,
                "USER",
                userId == null ? null : userId.toString(),
                payload
        );
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static String stripExt(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private static String preview(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
