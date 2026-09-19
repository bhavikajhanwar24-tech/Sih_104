package com.sentinelvoice.directory;

import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.audit.AuditLedgerService;
import com.sentinelvoice.directory.entity.BeneficiaryAccountEntity;
import com.sentinelvoice.directory.entity.ExternalPartyEntity;
import com.sentinelvoice.directory.repo.BeneficiaryAccountRepository;
import com.sentinelvoice.directory.repo.ExternalPartyRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class ExternalDirectoryService {

    private static final List<String> TYPES = List.of("VENDOR", "CUSTOMER", "REGULATOR", "PARTNER", "OTHER");

    private final ExternalPartyRepository externalPartyRepository;
    private final BeneficiaryAccountRepository beneficiaryAccountRepository;
    private final AccountHmacService accountHmacService;
    private final AuditLedgerService auditLedgerService;

    public ExternalDirectoryService(
            ExternalPartyRepository externalPartyRepository,
            BeneficiaryAccountRepository beneficiaryAccountRepository,
            AccountHmacService accountHmacService,
            AuditLedgerService auditLedgerService
    ) {
        this.externalPartyRepository = externalPartyRepository;
        this.beneficiaryAccountRepository = beneficiaryAccountRepository;
        this.accountHmacService = accountHmacService;
        this.auditLedgerService = auditLedgerService;
    }

    @Transactional(readOnly = true)
    public Page<Map<String, Object>> searchExternals(UUID tenantId, String q, String type, int page, int size) {
        return externalPartyRepository.search(
                tenantId,
                blank(q),
                blank(type),
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by("name"))
        ).map(this::externalToMap);
    }

    @Transactional
    public Map<String, Object> createExternal(UUID tenantId, UUID actorId, Map<String, Object> body) {
        String name = str(body, "name");
        String type = str(body, "type");
        if (name == null || type == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name and type required");
        }
        type = type.toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid type");
        }
        ExternalPartyEntity e = new ExternalPartyEntity();
        e.setId(UUID.randomUUID());
        e.setTenantId(tenantId);
        e.setName(name.trim());
        e.setType(type);
        e.setVerified(bool(body, "verified", false));
        e.setNotes(str(body, "notes"));
        e.setPhones(phonesFrom(body.get("phones")));
        Instant now = Instant.now();
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        externalPartyRepository.save(e);
        Map<String, Object> view = externalToMap(e);
        audit(tenantId, actorId, AuditEventType.DIRECTORY_CREATED, "external_party", e.getId(), Map.of(), view);
        return view;
    }

    @Transactional
    public Map<String, Object> updateExternal(UUID tenantId, UUID actorId, UUID id, Map<String, Object> body) {
        ExternalPartyEntity e = externalPartyRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "not found"));
        Map<String, Object> before = externalToMap(e);
        if (body.containsKey("name") && str(body, "name") != null) {
            e.setName(str(body, "name"));
        }
        if (body.containsKey("type") && str(body, "type") != null) {
            String type = str(body, "type").toUpperCase(Locale.ROOT);
            if (!TYPES.contains(type)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid type");
            }
            e.setType(type);
        }
        if (body.containsKey("verified")) {
            e.setVerified(bool(body, "verified", false));
        }
        if (body.containsKey("notes")) {
            e.setNotes(str(body, "notes"));
        }
        if (body.containsKey("phones")) {
            e.setPhones(phonesFrom(body.get("phones")));
        }
        e.setUpdatedAt(Instant.now());
        externalPartyRepository.save(e);
        Map<String, Object> after = externalToMap(e);
        audit(tenantId, actorId, AuditEventType.DIRECTORY_UPDATED, "external_party", id, before, after);
        return after;
    }

    @Transactional
    public void deleteExternal(UUID tenantId, UUID actorId, UUID id) {
        ExternalPartyEntity e = externalPartyRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "not found"));
        Map<String, Object> before = externalToMap(e);
        externalPartyRepository.delete(e);
        audit(tenantId, actorId, AuditEventType.DIRECTORY_DELETED, "external_party", id, before, Map.of());
    }

    @Transactional(readOnly = true)
    public Page<Map<String, Object>> listBeneficiaries(UUID tenantId, int page, int size) {
        return beneficiaryAccountRepository.findByTenantId(
                tenantId,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by("firstSeenAt").descending())
        ).map(this::beneficiaryToMap);
    }

    @Transactional
    public Map<String, Object> createBeneficiary(UUID tenantId, UUID actorId, Map<String, Object> body) {
        String accountNumber = str(body, "accountNumber");
        if (accountNumber == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "accountNumber required");
        }
        String hash = accountHmacService.hashAccount(accountNumber);
        if (beneficiaryAccountRepository.findByTenantIdAndAccountRefHash(tenantId, hash).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "beneficiary already exists");
        }
        BeneficiaryAccountEntity b = new BeneficiaryAccountEntity();
        b.setId(UUID.randomUUID());
        b.setTenantId(tenantId);
        b.setAccountRefHash(hash);
        b.setLabel(str(body, "label"));
        b.setExternalPartyId(uuidOrNull(body.get("externalPartyId")));
        b.setVerified(bool(body, "verified", false));
        b.setFirstSeenAt(Instant.now());
        beneficiaryAccountRepository.save(b);
        Map<String, Object> view = beneficiaryToMap(b);
        audit(tenantId, actorId, AuditEventType.DIRECTORY_CREATED, "beneficiary_account", b.getId(), Map.of(), view);
        return view;
    }

    @Transactional
    public Map<String, Object> updateBeneficiary(UUID tenantId, UUID actorId, UUID id, Map<String, Object> body) {
        BeneficiaryAccountEntity b = beneficiaryAccountRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "not found"));
        Map<String, Object> before = beneficiaryToMap(b);
        if (body.containsKey("label")) {
            b.setLabel(str(body, "label"));
        }
        if (body.containsKey("verified")) {
            b.setVerified(bool(body, "verified", false));
        }
        if (body.containsKey("externalPartyId")) {
            b.setExternalPartyId(uuidOrNull(body.get("externalPartyId")));
        }
        if (body.containsKey("accountNumber") && str(body, "accountNumber") != null) {
            b.setAccountRefHash(accountHmacService.hashAccount(str(body, "accountNumber")));
        }
        beneficiaryAccountRepository.save(b);
        Map<String, Object> after = beneficiaryToMap(b);
        audit(tenantId, actorId, AuditEventType.DIRECTORY_UPDATED, "beneficiary_account", id, before, after);
        return after;
    }

    @Transactional
    public void deleteBeneficiary(UUID tenantId, UUID actorId, UUID id) {
        BeneficiaryAccountEntity b = beneficiaryAccountRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "not found"));
        Map<String, Object> before = beneficiaryToMap(b);
        beneficiaryAccountRepository.delete(b);
        audit(tenantId, actorId, AuditEventType.DIRECTORY_DELETED, "beneficiary_account", id, before, Map.of());
    }

    private void audit(
            UUID tenantId, UUID actorId, AuditEventType type, String entity, UUID entityId,
            Map<String, Object> before, Map<String, Object> after
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entity", entity);
        payload.put("entityId", entityId.toString());
        payload.put("diff", DirectoryAuditSupport.diff(before, after));
        auditLedgerService.append(tenantId, null, type, "USER",
                actorId == null ? null : actorId.toString(), payload);
    }

    private Map<String, Object> externalToMap(ExternalPartyEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("name", e.getName());
        m.put("type", e.getType());
        m.put("phones", e.getPhones());
        m.put("verified", e.isVerified());
        m.put("notes", e.getNotes());
        m.put("createdAt", e.getCreatedAt());
        m.put("updatedAt", e.getUpdatedAt());
        return m;
    }

    private Map<String, Object> beneficiaryToMap(BeneficiaryAccountEntity b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.getId());
        m.put("externalPartyId", b.getExternalPartyId());
        m.put("accountRefHash", b.getAccountRefHash());
        m.put("label", b.getLabel());
        m.put("firstSeenAt", b.getFirstSeenAt());
        m.put("verified", b.isVerified());
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> phonesFrom(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                out.add(new LinkedHashMap<>((Map<String, Object>) map));
            } else if (item instanceof String s) {
                out.add(Map.of("e164", s));
            }
        }
        return out;
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static boolean bool(Map<String, Object> body, String key, boolean def) {
        Object v = body.get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(v));
    }

    private static UUID uuidOrNull(Object v) {
        if (v == null || String.valueOf(v).isBlank()) {
            return null;
        }
        return UUID.fromString(String.valueOf(v));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
