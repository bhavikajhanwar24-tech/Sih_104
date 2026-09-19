package com.sentinelvoice.directory;

import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.audit.AuditLedgerService;
import com.sentinelvoice.directory.entity.KnownRelationshipEntity;
import com.sentinelvoice.directory.repo.EmployeeRepository;
import com.sentinelvoice.directory.repo.ExternalPartyRepository;
import com.sentinelvoice.directory.repo.KnownRelationshipRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Known-relationship graph (replaces v1 InteractionEdge). F10 hooks recordContact after finalised calls.
 */
@Service
public class RelationshipService {

    private final KnownRelationshipRepository relationshipRepository;
    private final EmployeeRepository employeeRepository;
    private final ExternalPartyRepository externalPartyRepository;
    private final AuditLedgerService auditLedgerService;

    public RelationshipService(
            KnownRelationshipRepository relationshipRepository,
            EmployeeRepository employeeRepository,
            ExternalPartyRepository externalPartyRepository,
            AuditLedgerService auditLedgerService
    ) {
        this.relationshipRepository = relationshipRepository;
        this.employeeRepository = employeeRepository;
        this.externalPartyRepository = externalPartyRepository;
        this.auditLedgerService = auditLedgerService;
    }

    @Transactional(readOnly = true)
    public RelationshipView query(UUID tenantId, UUID fromEmployeeId, UUID toEmployeeId, UUID toExternalId) {
        if (fromEmployeeId == null) {
            return RelationshipView.firstContact();
        }
        Optional<KnownRelationshipEntity> found = relationshipRepository.findPair(
                tenantId, fromEmployeeId, toEmployeeId, toExternalId
        );
        if (found.isEmpty()) {
            return RelationshipView.firstContact();
        }
        KnownRelationshipEntity r = found.get();
        return new RelationshipView(
                r.getContactCount(),
                r.getLastContactAt(),
                r.getTypicalTopics() == null ? List.of() : List.copyOf(r.getTypicalTopics()),
                r.getContactCount() <= 0
        );
    }

    /**
     * Exposed for F10 call-finalised hook.
     */
    @Transactional
    public RelationshipView recordContact(
            UUID tenantId,
            UUID fromEmployeeId,
            UUID toEmployeeId,
            UUID toExternalId,
            List<String> topics
    ) {
        if (fromEmployeeId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "fromEmployeeId required");
        }
        employeeRepository.findByIdAndTenantId(fromEmployeeId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown from employee"));
        if (toEmployeeId != null) {
            employeeRepository.findByIdAndTenantId(toEmployeeId, tenantId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown to employee"));
        }
        if (toExternalId != null) {
            externalPartyRepository.findByIdAndTenantId(toExternalId, tenantId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown external"));
        }

        KnownRelationshipEntity r = relationshipRepository
                .findPair(tenantId, fromEmployeeId, toEmployeeId, toExternalId)
                .orElseGet(() -> {
                    KnownRelationshipEntity n = new KnownRelationshipEntity();
                    n.setId(UUID.randomUUID());
                    n.setTenantId(tenantId);
                    n.setFromEmployeeId(fromEmployeeId);
                    n.setToEmployeeId(toEmployeeId);
                    n.setToExternalId(toExternalId);
                    n.setRelationshipType(toExternalId != null ? "EXTERNAL" : "COLLEAGUE");
                    n.setTypicalTopics(new ArrayList<>());
                    n.setContactCount(0);
                    return n;
                });

        r.setContactCount(r.getContactCount() + 1);
        r.setLastContactAt(Instant.now());
        if (topics != null && !topics.isEmpty()) {
            Set<String> merged = new LinkedHashSet<>(r.getTypicalTopics() == null ? List.of() : r.getTypicalTopics());
            merged.addAll(topics);
            r.setTypicalTopics(new ArrayList<>(merged));
        }
        relationshipRepository.save(r);
        return query(tenantId, fromEmployeeId, toEmployeeId, toExternalId);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listForEmployee(UUID tenantId, UUID employeeId) {
        return relationshipRepository.findByTenantIdAndFromEmployeeId(tenantId, employeeId).stream()
                .map(this::toMap)
                .toList();
    }

    @Transactional
    public Map<String, Object> create(
            UUID tenantId, UUID actorId, UUID fromId, UUID toEmployeeId, UUID toExternalId, String type, List<String> topics
    ) {
        employeeRepository.findByIdAndTenantId(fromId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "from employee not found"));
        KnownRelationshipEntity r = new KnownRelationshipEntity();
        r.setId(UUID.randomUUID());
        r.setTenantId(tenantId);
        r.setFromEmployeeId(fromId);
        r.setToEmployeeId(toEmployeeId);
        r.setToExternalId(toExternalId);
        r.setRelationshipType(type == null || type.isBlank() ? "COLLEAGUE" : type);
        r.setTypicalTopics(topics == null ? List.of() : topics);
        r.setContactCount(0);
        relationshipRepository.save(r);
        Map<String, Object> view = toMap(r);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entity", "known_relationship");
        payload.put("entityId", r.getId().toString());
        payload.put("diff", DirectoryAuditSupport.diff(Map.of(), view));
        auditLedgerService.append(tenantId, null, AuditEventType.DIRECTORY_CREATED, "USER",
                actorId == null ? null : actorId.toString(), payload);
        return view;
    }

    private Map<String, Object> toMap(KnownRelationshipEntity r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("fromEmployeeId", r.getFromEmployeeId());
        m.put("toEmployeeId", r.getToEmployeeId());
        m.put("toExternalId", r.getToExternalId());
        m.put("relationshipType", r.getRelationshipType());
        m.put("typicalTopics", r.getTypicalTopics());
        m.put("lastContactAt", r.getLastContactAt());
        m.put("contactCount", r.getContactCount());
        return m;
    }
}
