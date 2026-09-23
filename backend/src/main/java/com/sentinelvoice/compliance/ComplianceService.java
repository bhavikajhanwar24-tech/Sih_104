package com.sentinelvoice.compliance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.audit.AuditLedgerService;
import com.sentinelvoice.passport.ConsentRequiredException;
import com.sentinelvoice.response.crypto.SecretBox;
import com.sentinelvoice.security.TenantContext;
import com.sentinelvoice.tenant.TenantSettingsEntity;
import com.sentinelvoice.tenant.TenantSettingsRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * F15 — consent, notice assets, voice passport (encrypted embedding), erasure/export, retention.
 */
@Service
public class ComplianceService {

    private static final int NOTICE_TEXT_MAX = 8000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final TenantSettingsRepository settingsRepository;
    private final AuditLedgerService auditLedgerService;
    private final SecretBox secretBox;
    private final ObjectMapper mapper;
    private final RestTemplate restTemplate;
    private final String mlBaseUrl;
    private final String mlServiceToken;
    private final Path noticeRoot;

    public ComplianceService(
            JdbcTemplate jdbc,
            TenantSettingsRepository settingsRepository,
            AuditLedgerService auditLedgerService,
            SecretBox secretBox,
            ObjectMapper mapper,
            RestTemplate restTemplate,
            @Value("${sentinelvoice.ml.base-url:http://127.0.0.1:8000}") String mlBaseUrl,
            @Value("${sentinelvoice.ml-service.service-token:}") String mlServiceToken,
            @Value("${sentinelvoice.compliance.notice-dir:backend/data/notice}") String noticeDir
    ) {
        this.jdbc = jdbc;
        this.settingsRepository = settingsRepository;
        this.auditLedgerService = auditLedgerService;
        this.secretBox = secretBox;
        this.mapper = mapper;
        this.restTemplate = restTemplate;
        this.mlBaseUrl = mlBaseUrl.endsWith("/") ? mlBaseUrl.substring(0, mlBaseUrl.length() - 1) : mlBaseUrl;
        this.mlServiceToken = mlServiceToken == null ? "" : mlServiceToken.trim();
        this.noticeRoot = Path.of(noticeDir).toAbsolutePath().normalize();
    }

    // ------------------------------------------------------------------ settings
    @Transactional(readOnly = true)
    public Map<String, Object> retentionSnapshot(UUID tenantId) {
        TenantSettingsEntity s = requireSettings(tenantId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", "2");
        out.put("tenantId", tenantId.toString());
        out.put("retentionDays", s.getRetentionDays());
        out.put("playCallNotice", s.isPlayCallNotice());
        out.put("noticeVersion", s.getNoticeVersion());
        out.put("noticeAssetId", s.getNoticeAssetId() == null ? null : s.getNoticeAssetId().toString());
        out.put("consentLanguages", s.getConsentLanguages());
        out.put("consentNoticeText", s.getConsentNoticeText());
        out.put("lastPurgeAt", s.getLastRetentionPurgeAt() == null ? null : s.getLastRetentionPurgeAt().toString());
        out.put("lastPurgeStats", s.getLastRetentionPurgeStats());
        out.put("nextScheduledPurge", Instant.now().plus(1, ChronoUnit.DAYS).toString());
        out.put("auditChainRetained", true);
        out.put("auditChainNote",
                "audit_blocks are never purged — they hold IDs/hashes only, not personal content.");
        out.put("rawAudioBytesPersisted", 0);
        return out;
    }

    @Transactional
    public Map<String, Object> patchComplianceSettings(UUID tenantId, UUID actorId, Map<String, Object> body) {
        TenantSettingsEntity s = requireSettings(tenantId);
        if (body.containsKey("retentionDays") && body.get("retentionDays") != null) {
            int days = asInt(body.get("retentionDays"));
            if (days < 7 || days > 365) {
                throw new IllegalArgumentException("retentionDays must be between 7 and 365");
            }
            s.setRetentionDays(days);
        }
        if (body.containsKey("consentNoticeText")) {
            String text = body.get("consentNoticeText") == null ? null : String.valueOf(body.get("consentNoticeText"));
            if (text != null && text.length() > NOTICE_TEXT_MAX) {
                throw new IllegalArgumentException("consentNoticeText max " + NOTICE_TEXT_MAX + " chars");
            }
            String prev = s.getConsentNoticeText();
            s.setConsentNoticeText(text);
            if (prev == null ? text != null : !prev.equals(text)) {
                s.setNoticeVersion(Math.max(1, s.getNoticeVersion()) + 1);
            }
        }
        if (body.containsKey("playCallNotice")) {
            s.setPlayCallNotice(Boolean.TRUE.equals(body.get("playCallNotice"))
                    || "true".equalsIgnoreCase(String.valueOf(body.get("playCallNotice"))));
        }
        if (body.containsKey("consentLanguages") && body.get("consentLanguages") != null) {
            s.setConsentLanguages(String.valueOf(body.get("consentLanguages")));
        }
        if (body.containsKey("noticeAssetId")) {
            Object raw = body.get("noticeAssetId");
            if (raw == null || String.valueOf(raw).isBlank()) {
                s.setNoticeAssetId(null);
            } else {
                UUID assetId = UUID.fromString(String.valueOf(raw));
                Integer n = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM notice_assets WHERE tenant_id = ? AND id = ?",
                        Integer.class, tenantId, assetId
                );
                if (n == null || n == 0) {
                    throw new IllegalArgumentException("notice asset not found");
                }
                s.setNoticeAssetId(assetId);
            }
        }
        s.setUpdatedAt(Instant.now());
        settingsRepository.save(s);
        auditLedgerService.append(tenantId, null, AuditEventType.TENANT_SETTINGS_UPDATED, "USER",
                actorId == null ? null : actorId.toString(),
                Map.of("area", "compliance", "retentionDays", s.getRetentionDays(),
                        "noticeVersion", s.getNoticeVersion(), "playCallNotice", s.isPlayCallNotice()));
        return retentionSnapshot(tenantId);
    }

    @Transactional
    public Map<String, Object> uploadNoticeAsset(
            UUID tenantId, UUID actorId, MultipartFile file, String ttsText
    ) throws Exception {
        Files.createDirectories(noticeRoot.resolve(tenantId.toString()));
        UUID id = UUID.randomUUID();
        String filename = file != null && file.getOriginalFilename() != null
                ? file.getOriginalFilename() : "notice.wav";
        byte[] bytes;
        String contentType;
        String kind;
        if (file != null && !file.isEmpty()) {
            bytes = file.getBytes();
            contentType = file.getContentType() == null ? "audio/wav" : file.getContentType();
            kind = "CALL_START";
        } else if (ttsText != null && !ttsText.isBlank()) {
            // Persist TTS script as UTF-8 asset; Asterisk playback may synthesise later.
            bytes = ttsText.trim().getBytes(StandardCharsets.UTF_8);
            contentType = "text/plain";
            kind = "TTS_TEXT";
            filename = "notice-tts.txt";
        } else {
            throw new IllegalArgumentException("file or ttsText required");
        }
        if (bytes.length > 2_000_000) {
            throw new IllegalArgumentException("notice asset too large (max 2MB)");
        }
        Path dest = noticeRoot.resolve(tenantId.toString()).resolve(id + "-" + sanitize(filename));
        Files.write(dest, bytes);
        String sha = sha256Hex(bytes);
        jdbc.update(
                """
                INSERT INTO notice_assets (
                  id, tenant_id, kind, filename, content_type, storage_path, byte_size, sha256, tts_text, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, tenantId, kind, filename, contentType, dest.toString(), bytes.length, sha,
                kind.equals("TTS_TEXT") ? ttsText.trim() : null, actorId
        );
        TenantSettingsEntity s = requireSettings(tenantId);
        s.setNoticeAssetId(id);
        s.setPlayCallNotice(true);
        s.setNoticeVersion(Math.max(1, s.getNoticeVersion()) + 1);
        s.setUpdatedAt(Instant.now());
        settingsRepository.save(s);
        auditLedgerService.append(tenantId, null, AuditEventType.NOTICE_ASSET_UPLOADED, "USER",
                actorId == null ? null : actorId.toString(),
                Map.of("assetId", id.toString(), "sha256", sha, "kind", kind, "byteSize", bytes.length));
        return Map.of(
                "schemaVersion", "2",
                "id", id.toString(),
                "kind", kind,
                "sha256", sha,
                "noticeVersion", s.getNoticeVersion()
        );
    }

    // ------------------------------------------------------------------ consents
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listConsents(UUID tenantId, String status, String purpose) {
        StringBuilder sql = new StringBuilder("""
                SELECT c.id, c.employee_id, e.full_name, e.employee_code, c.purpose, c.status,
                       c.method, c.notice_version, c.evidence_ref, c.granted_at, c.withdrawn_at, c.updated_at
                FROM consents c
                JOIN employees e ON e.id = c.employee_id AND e.tenant_id = c.tenant_id
                WHERE c.tenant_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (status != null && !status.isBlank()) {
            sql.append(" AND c.status = ?");
            args.add(status.trim().toUpperCase(Locale.ROOT));
        }
        if (purpose != null && !purpose.isBlank()) {
            sql.append(" AND c.purpose = ?");
            args.add(purpose.trim().toUpperCase(Locale.ROOT));
        }
        sql.append(" ORDER BY c.updated_at DESC LIMIT 500");
        return jdbc.query(sql.toString(), (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getObject("id", UUID.class).toString());
            m.put("employeeId", rs.getObject("employee_id", UUID.class).toString());
            m.put("employeeName", rs.getString("full_name"));
            m.put("employeeCode", rs.getString("employee_code"));
            m.put("purpose", rs.getString("purpose"));
            m.put("status", rs.getString("status"));
            m.put("method", rs.getString("method"));
            m.put("noticeVersion", rs.getInt("notice_version"));
            m.put("evidenceRef", rs.getString("evidence_ref"));
            m.put("grantedAt", ts(rs.getTimestamp("granted_at")));
            m.put("withdrawnAt", ts(rs.getTimestamp("withdrawn_at")));
            m.put("updatedAt", ts(rs.getTimestamp("updated_at")));
            return m;
        }, args.toArray());
    }

    @Transactional
    public Map<String, Object> upsertConsent(
            UUID tenantId, UUID actorId, UUID employeeId, String purpose, String status, String method
    ) {
        requireEmployee(tenantId, employeeId);
        String p = purpose.trim().toUpperCase(Locale.ROOT);
        String st = status.trim().toUpperCase(Locale.ROOT);
        String meth = method == null || method.isBlank() ? "ADMIN" : method.trim().toUpperCase(Locale.ROOT);
        if (!List.of("MONITORING", "VOICE_PASSPORT").contains(p)) {
            throw new IllegalArgumentException("invalid purpose");
        }
        if (!List.of("GRANTED", "WITHDRAWN", "NOT_REQUIRED").contains(st)) {
            throw new IllegalArgumentException("invalid status");
        }
        int noticeVersion = requireSettings(tenantId).getNoticeVersion();
        Instant now = Instant.now();
        jdbc.update(
                """
                INSERT INTO consents (
                  id, tenant_id, employee_id, purpose, status, method, notice_version,
                  granted_at, withdrawn_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())
                ON CONFLICT (tenant_id, employee_id, purpose) DO UPDATE SET
                  status = EXCLUDED.status,
                  method = EXCLUDED.method,
                  notice_version = EXCLUDED.notice_version,
                  granted_at = CASE WHEN EXCLUDED.status = 'GRANTED' THEN now() ELSE consents.granted_at END,
                  withdrawn_at = CASE WHEN EXCLUDED.status = 'WITHDRAWN' THEN now() ELSE NULL END,
                  updated_at = now()
                """,
                UUID.randomUUID(), tenantId, employeeId, p, st, meth, noticeVersion,
                "GRANTED".equals(st) ? java.sql.Timestamp.from(now) : null,
                "WITHDRAWN".equals(st) ? java.sql.Timestamp.from(now) : null
        );
        AuditEventType evt = "WITHDRAWN".equals(st) ? AuditEventType.CONSENT_WITHDRAWN : AuditEventType.CONSENT_GRANTED;
        auditLedgerService.append(tenantId, null, evt, "USER",
                actorId == null ? null : actorId.toString(),
                Map.of("employeeId", employeeId.toString(), "purpose", p, "status", st, "method", meth));
        if ("WITHDRAWN".equals(st) && "VOICE_PASSPORT".equals(p)) {
            deletePassportInternal(tenantId, employeeId, actorId, false);
        }
        return Map.of("employeeId", employeeId.toString(), "purpose", p, "status", st);
    }

    @Transactional
    public Map<String, Object> bulkConsent(
            UUID tenantId, UUID actorId, List<UUID> employeeIds, String purpose, String status
    ) {
        int n = 0;
        for (UUID id : employeeIds) {
            upsertConsent(tenantId, actorId, id, purpose, status, "BULK");
            n++;
        }
        return Map.of("updated", n, "purpose", purpose, "status", status);
    }

    @Transactional
    public Map<String, Object> issuePublicConsentToken(
            UUID tenantId, UUID actorId, UUID employeeId, String purpose, int ttlHours
    ) {
        requireEmployee(tenantId, employeeId);
        String p = purpose.trim().toUpperCase(Locale.ROOT);
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String hash = sha256Hex(token.getBytes(StandardCharsets.UTF_8));
        UUID id = UUID.randomUUID();
        Instant exp = Instant.now().plus(Math.max(1, Math.min(ttlHours, 168)), ChronoUnit.HOURS);
        jdbc.update(
                """
                INSERT INTO consent_tokens (
                  id, tenant_id, employee_id, purpose, token_hash, expires_at, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                id, tenantId, employeeId, p, hash, java.sql.Timestamp.from(exp), actorId
        );
        auditLedgerService.append(tenantId, null, AuditEventType.CONSENT_TOKEN_ISSUED, "USER",
                actorId == null ? null : actorId.toString(),
                Map.of("employeeId", employeeId.toString(), "purpose", p, "expiresAt", exp.toString()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", token);
        out.put("expiresAt", exp.toString());
        out.put("path", "/consent/" + token);
        out.put("purpose", p);
        out.put("employeeId", employeeId.toString());
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> publicConsentPreview(String token) {
        TokenRow row = findToken(token);
        if (row == null) {
            throw new IllegalArgumentException("invalid or expired token");
        }
        return TenantContext.runAs(row.tenantId(), () -> {
            TenantSettingsEntity s = requireSettings(row.tenantId());
            Map<String, Object> emp = jdbc.query(
                    "SELECT full_name, employee_code FROM employees WHERE tenant_id = ? AND id = ?",
                    rs -> {
                        if (!rs.next()) return Map.<String, Object>of();
                        return Map.<String, Object>of(
                                "fullName", rs.getString("full_name"),
                                "employeeCode", rs.getString("employee_code")
                        );
                    },
                    row.tenantId(), row.employeeId()
            );
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("schemaVersion", "2");
            out.put("purpose", row.purpose());
            out.put("employee", emp);
            out.put("noticeText", s.getConsentNoticeText());
            out.put("noticeVersion", s.getNoticeVersion());
            out.put("languages", s.getConsentLanguages());
            out.put("expiresAt", row.expiresAt().toString());
            return out;
        });
    }

    @Transactional
    public Map<String, Object> publicConsentAccept(String token) {
        TokenRow row = findToken(token);
        if (row == null) {
            throw new IllegalArgumentException("invalid or expired token");
        }
        return TenantContext.runAs(row.tenantId(), () -> {
            jdbc.update("UPDATE consent_tokens SET consumed_at = now() WHERE id = ? AND consumed_at IS NULL",
                    row.id());
            upsertConsent(row.tenantId(), null, row.employeeId(), row.purpose(), "GRANTED", "PUBLIC_LINK");
            return Map.of("status", "GRANTED", "purpose", row.purpose());
        });
    }

    public void requireVoicePassportConsent(UUID tenantId, UUID employeeId) {
        Integer n = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM consents
                WHERE tenant_id = ? AND employee_id = ? AND purpose = 'VOICE_PASSPORT' AND status = 'GRANTED'
                """,
                Integer.class, tenantId, employeeId
        );
        if (n == null || n == 0) {
            throw new ConsentRequiredException("VOICE_PASSPORT consent GRANTED is required before enrolment");
        }
    }

    // ------------------------------------------------------------------ passport
    @Transactional
    public Map<String, Object> enrolPassport(
            UUID tenantId, UUID actorId, UUID employeeId, String audioRef, MultipartFile audioFile
    ) {
        requireEmployee(tenantId, employeeId);
        requireVoicePassportConsent(tenantId, employeeId);
        Map<String, Object> enrol = callMlEnrol(audioRef, audioFile);
        @SuppressWarnings("unchecked")
        Map<String, Object> embeddings = (Map<String, Object>) enrol.get("embeddings");
        Object wide = pickWidebandEmbedding(embeddings);
        if (wide == null) {
            throw new IllegalStateException("ml enrol returned no wideband embedding");
        }
        List<Number> vec = mapper.convertValue(wide, new TypeReference<>() {
        });
        byte[] plain = floatsToBytes(vec);
        byte[] cipher = secretBox.encryptBytes(plain);
        // zeroise plaintext buffer
        java.util.Arrays.fill(plain, (byte) 0);
        String model = String.valueOf(enrol.getOrDefault("modelId", "unknown"));
        int dim = enrol.get("dim") instanceof Number n ? n.intValue() : vec.size();
        jdbc.update(
                """
                INSERT INTO voice_passports (id, tenant_id, employee_id, embedding_cipher, model_version, dim)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, employee_id) DO UPDATE SET
                  embedding_cipher = EXCLUDED.embedding_cipher,
                  model_version = EXCLUDED.model_version,
                  dim = EXCLUDED.dim,
                  updated_at = now()
                """,
                UUID.randomUUID(), tenantId, employeeId, cipher, model, dim
        );
        auditLedgerService.append(tenantId, null, AuditEventType.PASSPORT_ENROLLED, "USER",
                actorId == null ? null : actorId.toString(),
                Map.of("employeeId", employeeId.toString(), "modelVersion", model, "dim", dim));
        return passportMeta(tenantId, employeeId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> passportMeta(UUID tenantId, UUID employeeId) {
        List<Map<String, Object>> rows = jdbc.query(
                """
                SELECT id, model_version, dim, created_at, updated_at
                FROM voice_passports WHERE tenant_id = ? AND employee_id = ?
                """,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getObject("id", UUID.class).toString());
                    m.put("employeeId", employeeId.toString());
                    m.put("modelVersion", rs.getString("model_version"));
                    m.put("dim", rs.getInt("dim"));
                    m.put("createdAt", ts(rs.getTimestamp("created_at")));
                    m.put("updatedAt", ts(rs.getTimestamp("updated_at")));
                    m.put("enrolled", true);
                    return m;
                },
                tenantId, employeeId
        );
        if (rows.isEmpty()) {
            return Map.of("employeeId", employeeId.toString(), "enrolled", false);
        }
        return rows.getFirst();
    }

    /** Decrypt embedding for in-process verify only — never returned on HTTP. */
    @Transactional(readOnly = true)
    public float[] loadDecryptedEmbedding(UUID tenantId, UUID employeeId) {
        List<byte[]> blobs = jdbc.query(
                "SELECT embedding_cipher FROM voice_passports WHERE tenant_id = ? AND employee_id = ?",
                (rs, i) -> rs.getBytes("embedding_cipher"),
                tenantId, employeeId
        );
        if (blobs.isEmpty() || blobs.getFirst() == null) {
            return null;
        }
        byte[] plain = secretBox.decryptBytes(blobs.getFirst());
        try {
            ByteBuffer buf = ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN);
            float[] out = new float[plain.length / 4];
            for (int i = 0; i < out.length; i++) {
                out[i] = buf.getFloat();
            }
            return out;
        } finally {
            java.util.Arrays.fill(plain, (byte) 0);
        }
    }

    @Transactional
    public Map<String, Object> deletePassport(UUID tenantId, UUID actorId, UUID employeeId) {
        return deletePassportInternal(tenantId, employeeId, actorId, true);
    }

    private Map<String, Object> deletePassportInternal(
            UUID tenantId, UUID employeeId, UUID actorId, boolean audit
    ) {
        int n = jdbc.update("DELETE FROM voice_passports WHERE tenant_id = ? AND employee_id = ?",
                tenantId, employeeId);
        if (audit && n > 0) {
            auditLedgerService.append(tenantId, null, AuditEventType.PASSPORT_ERASED, "USER",
                    actorId == null ? null : actorId.toString(),
                    Map.of("employeeId", employeeId.toString(), "deleted", true));
        }
        return Map.of("employeeId", employeeId.toString(), "deleted", n > 0);
    }

    // ------------------------------------------------------------------ data subject
    @Transactional(readOnly = true)
    public Map<String, Object> exportEmployee(UUID tenantId, UUID actorId, UUID employeeId) {
        requireEmployee(tenantId, employeeId);
        Map<String, Object> emp = jdbc.query(
                """
                SELECT id, employee_code, full_name, email, job_title, role_key, status, created_at
                FROM employees WHERE tenant_id = ? AND id = ?
                """,
                rs -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    if (!rs.next()) return m;
                    m.put("id", rs.getObject("id", UUID.class).toString());
                    m.put("employeeCode", rs.getString("employee_code"));
                    m.put("fullName", rs.getString("full_name"));
                    m.put("email", rs.getString("email"));
                    m.put("jobTitle", rs.getString("job_title"));
                    m.put("roleKey", rs.getString("role_key"));
                    m.put("status", rs.getString("status"));
                    m.put("createdAt", ts(rs.getTimestamp("created_at")));
                    return m;
                },
                tenantId, employeeId
        );
        List<Map<String, Object>> phones = jdbc.query(
                "SELECT e164, label FROM employee_phones WHERE tenant_id = ? AND employee_id = ?",
                (rs, i) -> Map.of("e164", rs.getString("e164"), "label", rs.getString("label") == null ? "" : rs.getString("label")),
                tenantId, employeeId
        );
        List<Map<String, Object>> consents = listConsents(tenantId, null, null).stream()
                .filter(c -> employeeId.toString().equals(c.get("employeeId")))
                .toList();
        Map<String, Object> passport = passportMeta(tenantId, employeeId);
        Map<String, Object> passportOut = new LinkedHashMap<>();
        passportOut.put("enrolled", Boolean.TRUE.equals(passport.get("enrolled")));
        passportOut.put("modelVersion", passport.get("modelVersion"));
        passportOut.put("dim", passport.get("dim"));
        // embedding never exported
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", "2");
        out.put("tenantId", tenantId.toString());
        out.put("exportedAt", Instant.now().toString());
        out.put("employee", emp);
        out.put("phones", phones);
        out.put("consents", consents);
        out.put("voicePassport", passportOut);
        auditLedgerService.append(tenantId, null, AuditEventType.EMPLOYEE_EXPORT, "USER",
                actorId == null ? null : actorId.toString(),
                Map.of("employeeId", employeeId.toString()));
        return out;
    }

    @Transactional
    public Map<String, Object> eraseEmployee(UUID tenantId, UUID actorId, UUID employeeId, String confirm) {
        if (!"ERASE".equals(confirm)) {
            throw new IllegalArgumentException("confirm must be the string ERASE");
        }
        requireEmployee(tenantId, employeeId);
        String shortId = employeeId.toString().replace("-", "").substring(0, 8);
        String erasureLabel = "Erased #" + shortId;
        String evidenceHash = sha256Hex((tenantId + "|" + employeeId + "|" + Instant.now()).getBytes(StandardCharsets.UTF_8));

        deletePassportInternal(tenantId, employeeId, actorId, true);
        jdbc.update("DELETE FROM employee_phones WHERE tenant_id = ? AND employee_id = ?", tenantId, employeeId);
        jdbc.update("DELETE FROM consents WHERE tenant_id = ? AND employee_id = ?", tenantId, employeeId);
        jdbc.update("DELETE FROM consent_tokens WHERE tenant_id = ? AND employee_id = ?", tenantId, employeeId);
        jdbc.update(
                """
                UPDATE call_sessions SET caller_employee_id = NULL
                WHERE tenant_id = ? AND caller_employee_id = ?
                """,
                tenantId, employeeId
        );
        jdbc.update(
                """
                UPDATE call_sessions SET callee_employee_id = NULL
                WHERE tenant_id = ? AND callee_employee_id = ?
                """,
                tenantId, employeeId
        );
        jdbc.update(
                """
                UPDATE employees SET
                  full_name = ?,
                  email = NULL,
                  job_title = NULL,
                  status = 'TERMINATED',
                  status_note = 'F15 erasure',
                  updated_at = now()
                WHERE tenant_id = ? AND id = ?
                """,
                erasureLabel, tenantId, employeeId
        );
        auditLedgerService.append(tenantId, null, AuditEventType.EMPLOYEE_ERASURE, "USER",
                actorId == null ? null : actorId.toString(),
                Map.of(
                        "employeeId", employeeId.toString(),
                        "erasureHash", evidenceHash,
                        "label", erasureLabel
                ));
        return Map.of(
                "schemaVersion", "2",
                "employeeId", employeeId.toString(),
                "label", erasureLabel,
                "erasureHash", evidenceHash,
                "status", "ERASED"
        );
    }

    // ------------------------------------------------------------------ retention
    @Transactional
    public Map<String, Object> purgeTenant(UUID tenantId) {
        TenantSettingsEntity s = requireSettings(tenantId);
        int days = Math.max(7, Math.min(365, s.getRetentionDays()));
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        java.sql.Timestamp cut = java.sql.Timestamp.from(cutoff);

        int ticks = jdbc.update(
                """
                DELETE FROM session_ticks st
                USING call_sessions cs
                WHERE st.tenant_id = ? AND cs.tenant_id = st.tenant_id
                  AND (st.session_id = cs.sv_session_uuid OR st.session_id = cs.id)
                  AND cs.started_at < ?
                """,
                tenantId, cut
        );
        // Also delete orphan ticks by created_at if join misses
        int ticks2 = jdbc.update(
                "DELETE FROM session_ticks WHERE tenant_id = ? AND created_at < ?",
                tenantId, cut
        );
        int reasons = jdbc.update(
                "DELETE FROM session_reasons WHERE tenant_id = ? AND created_at < ?",
                tenantId, cut
        );
        int extractions = jdbc.update(
                "DELETE FROM session_extractions WHERE tenant_id = ? AND created_at < ?",
                tenantId, cut
        );
        int dossiers = jdbc.update(
                "DELETE FROM forensic_dossiers WHERE tenant_id = ? AND created_at < ?",
                tenantId, cut
        );

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("cutoff", cutoff.toString());
        stats.put("retentionDays", days);
        stats.put("sessionTicks", ticks + ticks2);
        stats.put("sessionReasons", reasons);
        stats.put("sessionExtractions", extractions);
        stats.put("forensicDossiers", dossiers);
        stats.put("auditBlocksDeleted", 0);
        stats.put("auditBlocksRetained", true);

        s.setLastRetentionPurgeAt(Instant.now());
        s.setLastRetentionPurgeStats(stats);
        s.setUpdatedAt(Instant.now());
        settingsRepository.save(s);

        auditLedgerService.append(tenantId, null, AuditEventType.RETENTION_PURGE, "SYSTEM", null, stats);
        return stats;
    }

    public Map<String, Object> dpdpMapping() {
        List<Map<String, Object>> rows = List.of(
                row("Lawful basis / consent", "Consent Register + public link", "/app/compliance?tab=consent", "supports"),
                row("Notice before processing", "Tenant consent notice + call-start announcement asset", "/app/compliance?tab=retention", "supports"),
                row("Purpose limitation (monitoring / passport)", "consents.purpose enum + passport gate", "/api/v2/compliance/consents", "supports"),
                row("Data minimisation", "No call PCM/transcripts persisted; passport stores encrypted embedding only", "/app/compliance?tab=dpdp", "supports"),
                row("Retention limits", "Per-tenant retention_days (7–365) + scheduled purge", "/app/compliance?tab=retention", "supports"),
                row("Right to access", "Export employee data JSON", "/app/compliance?tab=dsr", "supports"),
                row("Right to erasure", "Erase employee (passport, phones, anonymise directory)", "/app/compliance?tab=dsr", "supports"),
                row("Accountability / audit", "Hash-chained audit_blocks (IDs/hashes only; never purged)", "/app/audit", "supports")
        );
        return Map.of(
                "schemaVersion", "2",
                "disclaimer", "This table describes features that support DPDP-oriented controls; it never guarantees regulatory compliance.",
                "items", rows
        );
    }

    // ------------------------------------------------------------------ helpers
    private Map<String, Object> callMlEnrol(String audioRef, MultipartFile audioFile) {
        if (mlServiceToken.isBlank() || mlServiceToken.length() < 16) {
            throw new IllegalStateException("ML_SERVICE_TOKEN not configured");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-ML-Service-Token", mlServiceToken);
        try {
            if (audioFile != null && !audioFile.isEmpty()) {
                headers.setContentType(MediaType.MULTIPART_FORM_DATA);
                org.springframework.util.LinkedMultiValueMap<String, Object> body =
                        new org.springframework.util.LinkedMultiValueMap<>();
                body.add("file", new org.springframework.core.io.ByteArrayResource(audioFile.getBytes()) {
                    @Override
                    public String getFilename() {
                        return audioFile.getOriginalFilename() == null ? "enrol.wav" : audioFile.getOriginalFilename();
                    }
                });
                ResponseEntity<Map> resp = restTemplate.postForEntity(
                        mlBaseUrl + "/enrol", new HttpEntity<>(body, headers), Map.class);
                return resp.getBody() == null ? Map.of() : resp.getBody();
            }
            headers.setContentType(MediaType.APPLICATION_JSON);
            String ref = audioRef == null || audioRef.isBlank() ? "synthetic:enrol" : audioRef;
            ResponseEntity<Map> resp = restTemplate.postForEntity(
                    mlBaseUrl + "/enrol",
                    new HttpEntity<>(Map.of("audioRef", ref), headers),
                    Map.class
            );
            return resp.getBody() == null ? Map.of() : resp.getBody();
        } catch (Exception e) {
            throw new IllegalStateException("ml enrol failed: " + e.getMessage(), e);
        }
    }

    private TenantSettingsEntity requireSettings(UUID tenantId) {
        return settingsRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalStateException("tenant settings missing"));
    }

    private void requireEmployee(UUID tenantId, UUID employeeId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM employees WHERE tenant_id = ? AND id = ?",
                Integer.class, tenantId, employeeId
        );
        if (n == null || n == 0) {
            throw new IllegalArgumentException("employee not found");
        }
    }

    private TokenRow findToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String hash = sha256Hex(token.getBytes(StandardCharsets.UTF_8));
        List<TokenRow> rows = jdbc.query(
                "SELECT id, tenant_id, employee_id, purpose, expires_at, consumed_at FROM fn_find_consent_token(?)",
                (rs, i) -> new TokenRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("employee_id", UUID.class),
                        rs.getString("purpose"),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("consumed_at") == null ? null : rs.getTimestamp("consumed_at").toInstant()
                ),
                hash
        );
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static Map<String, Object> row(String obligation, String feature, String evidence, String support) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("obligation", obligation);
        m.put("feature", feature);
        m.put("evidence", evidence);
        m.put("support", support);
        return m;
    }

    /**
     * ml-engine /enrol keys embeddings by ChannelProfile value
     * ({@code WEBRTC_WIDEBAND}, {@code VOIP_WIDEBAND}, …), not a bare {@code wideband} label.
     */
    private static Object pickWidebandEmbedding(Map<String, Object> embeddings) {
        if (embeddings == null || embeddings.isEmpty()) {
            return null;
        }
        for (String key : List.of("WEBRTC_WIDEBAND", "VOIP_WIDEBAND", "wideband", "WIDEBAND")) {
            if (embeddings.get(key) != null) {
                return embeddings.get(key);
            }
        }
        for (Map.Entry<String, Object> e : embeddings.entrySet()) {
            if (e.getKey() != null && e.getKey().toUpperCase(Locale.ROOT).contains("WIDEBAND") && e.getValue() != null) {
                return e.getValue();
            }
        }
        return null;
    }

    private static byte[] floatsToBytes(List<Number> vec) {
        ByteBuffer buf = ByteBuffer.allocate(vec.size() * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (Number n : vec) {
            buf.putFloat(n.floatValue());
        }
        return buf.array();
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static int asInt(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        return Integer.parseInt(String.valueOf(o));
    }

    private static String ts(java.sql.Timestamp t) {
        return t == null ? null : t.toInstant().toString();
    }

    private record TokenRow(
            UUID id, UUID tenantId, UUID employeeId, String purpose, Instant expiresAt, Instant consumedAt
    ) {
    }
}
