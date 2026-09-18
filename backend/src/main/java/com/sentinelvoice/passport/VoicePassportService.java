package com.sentinelvoice.passport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.audit.AuditLedgerService;
import com.sentinelvoice.config.SentinelProperties;
import com.sentinelvoice.identity.IdentityVerdict;
import com.sentinelvoice.model.AuditBlock;
import com.sentinelvoice.model.ChannelProfile;
import com.sentinelvoice.passport.model.ConsentRecord;
import com.sentinelvoice.passport.model.VoicePassport;
import com.sentinelvoice.repository.AuditBlockRepository;
import com.sentinelvoice.repository.ConsentRecordRepository;
import com.sentinelvoice.repository.VoicePassportRepository;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * Consent-gated Voice Passport enrolment, verification, and DPDP erasure (Context §13.2–§13.3).
 *
 * <p>Java stores embeddings only. Audio never enters this process — the ML sidecar embeds and
 * zeroises; we persist float vectors via {@link EmbeddingCodec}.
 */
@Service
public class VoicePassportService {

    public static final String PURPOSE_VOICE_PASSPORT = "VOICE_PASSPORT_ENROLMENT";
    public static final String DEFAULT_NOTICE_VERSION = "notice-v1-2026";

    private final VoicePassportRepository passportRepository;
    private final ConsentRecordRepository consentRepository;
    private final AuditLedgerService auditLedgerService;
    private final AuditBlockRepository auditBlockRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String mlEnrolUrl;
    private final double cosineMatchMin;
    private final double cosineMismatchMax;

    public VoicePassportService(
            VoicePassportRepository passportRepository,
            ConsentRecordRepository consentRepository,
            AuditLedgerService auditLedgerService,
            AuditBlockRepository auditBlockRepository,
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            SentinelProperties properties
    ) {
        this.passportRepository = passportRepository;
        this.consentRepository = consentRepository;
        this.auditLedgerService = auditLedgerService;
        this.auditBlockRepository = auditBlockRepository;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.mlEnrolUrl = trimSlash(properties.ml().baseUrl()) + "/enrol";
        this.cosineMatchMin = properties.identity().cosineMatchMin();
        this.cosineMismatchMax = properties.identity().cosineMismatchMax();
    }

    @Transactional
    public ConsentRecord grantConsent(PassportDtos.ConsentRequest request) {
        if (request.employeeId() == null || request.employeeId().isBlank()) {
            throw new IllegalArgumentException("employeeId is required");
        }
        ConsentRecord record = new ConsentRecord();
        record.setEmployeeId(request.employeeId().trim());
        record.setPurpose(
                request.purpose() == null || request.purpose().isBlank()
                        ? PURPOSE_VOICE_PASSPORT
                        : request.purpose().trim()
        );
        record.setNoticeVersion(
                request.noticeVersion() == null || request.noticeVersion().isBlank()
                        ? DEFAULT_NOTICE_VERSION
                        : request.noticeVersion().trim()
        );
        record.setGrantedAt(Instant.now());
        record.setGrantedBy(
                request.grantedBy() == null || request.grantedBy().isBlank()
                        ? "system"
                        : request.grantedBy().trim()
        );
        record.setMethod(
                request.method() == null || request.method().isBlank()
                        ? "AFFIRMATIVE_UI"
                        : request.method().trim()
        );
        return consentRepository.save(record);
    }

    @Transactional(readOnly = true)
    public List<ConsentRecord> listConsents() {
        return consentRepository.findAllByOrderByGrantedAtDesc();
    }

    @Transactional
    public ConsentRecord withdrawConsent(long consentId) {
        ConsentRecord record = consentRepository.findById(consentId)
                .orElseThrow(() -> new NoSuchElementException("consent not found: " + consentId));
        if (record.getWithdrawnAt() == null) {
            record.setWithdrawnAt(Instant.now());
            record = consentRepository.save(record);
        }
        return record;
    }

    @Transactional
    public PassportDtos.EnrolResponse enrol(PassportDtos.EnrolRequest request) {
        if (request.employeeId() == null || request.employeeId().isBlank()) {
            throw new IllegalArgumentException("employeeId is required");
        }
        if (request.audioRef() == null || request.audioRef().isBlank()) {
            throw new IllegalArgumentException("audioRef is required");
        }
        String employeeId = request.employeeId().trim();
        if (consentRepository.findActiveConsent(employeeId, PURPOSE_VOICE_PASSPORT).isEmpty()) {
            throw new ConsentRequiredException(employeeId);
        }

        MlEnrolResult ml = callMlEnrol(request.audioRef());
        List<String> profileIds = new ArrayList<>();
        List<String> channels = new ArrayList<>();

        // Store both wideband and narrowband from one recording (Context §12 channel fix).
        for (Map.Entry<ChannelProfile, float[]> entry : ml.embeddings().entrySet()) {
            ChannelProfile profile = entry.getKey();
            float[] embedding = entry.getValue();
            Optional<VoicePassport> existing =
                    passportRepository.findByEmployeeIdAndChannelProfileAndActiveTrue(employeeId, profile);
            VoicePassport passport = existing.orElseGet(VoicePassport::new);
            if (passport.getProfileId() == null) {
                passport.setProfileId("pp-" + UUID.randomUUID());
            }
            passport.setEmployeeId(employeeId);
            passport.setChannelProfile(profile);
            passport.setEmbedding(EmbeddingCodec.encode(embedding));
            passport.setEmbeddingModelId(ml.modelId());
            passport.setEnrolledAt(Instant.now());
            passport.setActive(true);
            passportRepository.save(passport);
            profileIds.add(passport.getProfileId());
            channels.add(profile.name());
        }

        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("employeeId", employeeId);
        auditPayload.put("profileIds", profileIds);
        auditPayload.put("channelProfiles", channels);
        auditPayload.put("embeddingModelId", ml.modelId());
        auditPayload.put("audioRef", request.audioRef());
        // Audio itself is never logged — only the opaque fixture/path reference.
        AuditBlock block = auditLedgerService.append(
                auditSessionId(employeeId),
                AuditEventType.PASSPORT_ENROLLED,
                auditPayload
        );

        return new PassportDtos.EnrolResponse(
                employeeId,
                profileIds,
                channels,
                ml.modelId(),
                block.getBlockIndex()
        );
    }

    /**
     * DPDP §11 — right to information about processing, NOT the biometric template.
     * Returning the vector would be a security regression (template theft → spoof enrolment).
     */
    @Transactional(readOnly = true)
    public PassportDtos.PassportMetadata getMetadata(String profileId) {
        VoicePassport passport = passportRepository.findById(profileId)
                .orElseThrow(() -> new NoSuchElementException("passport not found: " + profileId));
        List<Map<String, Object>> log = buildProcessingLog(passport.getEmployeeId(), profileId);
        return new PassportDtos.PassportMetadata(
                passport.getProfileId(),
                passport.getEmployeeId(),
                passport.getChannelProfile(),
                passport.getEmbeddingModelId(),
                passport.getEnrolledAt(),
                passport.getLastVerifiedAt(),
                passport.isActive(),
                log
        );
    }

    /**
     * Hard-delete the embedding (DPDP §12 erasure) and append a cryptographic tombstone.
     *
     * <p>The tombstone is SHA-256 of the erased float32 bytes. It proves <em>what</em> was
     * deleted (a specific template) to auditors without retaining the biometric — you cannot
     * invert SHA-256 to recover the embedding. That is the compliance-slide point.
     */
    @Transactional
    public PassportDtos.DeletionCertificate erase(String profileId) {
        VoicePassport passport = passportRepository.findById(profileId)
                .orElseThrow(() -> new NoSuchElementException("passport not found: " + profileId));

        float[] embedding = EmbeddingCodec.decode(passport.getEmbedding());
        String tombstoneHash = EmbeddingCodec.sha256Hex(embedding);
        String employeeId = passport.getEmployeeId();
        ChannelProfile channel = passport.getChannelProfile();

        // Hard delete — not soft. DPDP §12 means the personal data is gone.
        passportRepository.delete(passport);
        passportRepository.flush();

        Instant erasedAt = Instant.now();
        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("profileId", profileId);
        auditPayload.put("employeeId", employeeId);
        auditPayload.put("channelProfile", channel.name());
        auditPayload.put("tombstoneHash", tombstoneHash);
        auditPayload.put("erasedAt", erasedAt.toString());
        AuditBlock block = auditLedgerService.append(
                auditSessionId(employeeId),
                AuditEventType.PASSPORT_ERASED,
                auditPayload
        );

        return new PassportDtos.DeletionCertificate(
                profileId,
                erasedAt,
                tombstoneHash,
                block.getBlockIndex()
        );
    }

    /**
     * Compare a live embedding to the enrolled passport for the <em>same</em> channel profile.
     * Missing profile → INCONCLUSIVE / CHANNEL_MISMATCH — never fall back to another channel.
     */
    @Transactional
    public PassportDtos.VerifyResult verify(
            String employeeId,
            float[] liveEmbedding,
            ChannelProfile channelProfile
    ) {
        if (employeeId == null || employeeId.isBlank()) {
            throw new IllegalArgumentException("employeeId is required");
        }
        if (channelProfile == null) {
            throw new IllegalArgumentException("channelProfile is required");
        }
        Optional<VoicePassport> opt = passportRepository
                .findByEmployeeIdAndChannelProfileAndActiveTrue(employeeId.trim(), channelProfile);
        if (opt.isEmpty()) {
            return new PassportDtos.VerifyResult(
                    null,
                    IdentityVerdict.INCONCLUSIVE,
                    "CHANNEL_MISMATCH",
                    channelProfile
            );
        }
        VoicePassport passport = opt.get();
        float[] enrolled = EmbeddingCodec.decode(passport.getEmbedding());
        double cosine = EmbeddingCodec.cosine(liveEmbedding, enrolled);
        IdentityVerdict verdict = verdictFromCosine(cosine);
        passport.setLastVerifiedAt(Instant.now());
        passportRepository.save(passport);
        return new PassportDtos.VerifyResult(cosine, verdict, null, channelProfile);
    }

    private IdentityVerdict verdictFromCosine(double cosine) {
        if (cosine >= cosineMatchMin) {
            return IdentityVerdict.VERIFIED;
        }
        if (cosine < cosineMismatchMax) {
            // Without a live spoof score here, treat low cosine as human impersonation default.
            return IdentityVerdict.IMPERSONATION_HUMAN;
        }
        return IdentityVerdict.INCONCLUSIVE;
    }

    private MlEnrolResult callMlEnrol(String audioRef) {
        try {
            Map<String, Object> body = Map.of("audioRef", audioRef);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<String> response = restTemplate.postForEntity(
                    mlEnrolUrl,
                    new HttpEntity<>(body, headers),
                    String.class
            );
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new IllegalStateException("ml-engine /enrol failed: " + response.getStatusCode());
            }
            return parseMlResponse(response.getBody());
        } catch (ConsentRequiredException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalStateException("ml-engine /enrol call failed: " + e.getMessage(), e);
        }
    }

    MlEnrolResult parseMlResponse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            String modelId = root.path("modelId").asText("speechbrain/spkrec-ecapa-voxceleb");
            Map<ChannelProfile, float[]> embeddings = new EnumMap<>(ChannelProfile.class);
            JsonNode embNode = root.path("embeddings");
            if (embNode.isObject()) {
                embNode.fields().forEachRemaining(entry -> {
                    ChannelProfile profile = ChannelProfile.valueOf(entry.getKey());
                    embeddings.put(profile, readEmbedding(entry.getValue()));
                });
            }
            if (embeddings.isEmpty()) {
                throw new IllegalStateException("ml-engine /enrol returned no embeddings");
            }
            return new MlEnrolResult(modelId, embeddings);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("invalid ml-engine /enrol payload", e);
        }
    }

    private float[] readEmbedding(JsonNode node) {
        if (node.isArray()) {
            float[] out = new float[EmbeddingCodec.DIM];
            if (node.size() != EmbeddingCodec.DIM) {
                throw new IllegalStateException("embedding dim " + node.size());
            }
            for (int i = 0; i < EmbeddingCodec.DIM; i++) {
                out[i] = (float) node.get(i).asDouble();
            }
            return out;
        }
        if (node.isTextual()) {
            return EmbeddingCodec.fromBase64(node.asText());
        }
        throw new IllegalStateException("embedding must be array or base64 string");
    }

    private List<Map<String, Object>> buildProcessingLog(String employeeId, String profileId) {
        List<Map<String, Object>> log = new ArrayList<>();
        for (AuditBlock block : auditBlockRepository
                .findBySessionIdOrderByBlockIndexAsc(auditSessionId(employeeId))) {
            if (block.getEventType().startsWith("PASSPORT_")) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("blockIndex", block.getBlockIndex());
                entry.put("eventType", block.getEventType());
                entry.put("tsEpochMs", block.getTsEpochMs());
                entry.put("currentHash", block.getCurrentHash());
                entry.put("profileId", profileId);
                log.add(entry);
            }
        }
        return log;
    }

    static String auditSessionId(String employeeId) {
        return "passport:" + employeeId;
    }

    private static String trimSlash(String url) {
        if (url == null || url.isBlank()) {
            return "http://localhost:8000";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    record MlEnrolResult(String modelId, Map<ChannelProfile, float[]> embeddings) {
    }
}
