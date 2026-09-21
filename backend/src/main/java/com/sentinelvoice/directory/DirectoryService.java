package com.sentinelvoice.directory;

import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.audit.AuditLedgerService;
import com.sentinelvoice.directory.entity.DepartmentEntity;
import com.sentinelvoice.directory.entity.EmployeeAuthorityEntity;
import com.sentinelvoice.directory.entity.EmployeeEntity;
import com.sentinelvoice.directory.entity.EmployeePhoneEntity;
import com.sentinelvoice.directory.repo.DepartmentRepository;
import com.sentinelvoice.directory.repo.EmployeeAuthorityRepository;
import com.sentinelvoice.directory.repo.EmployeePhoneRepository;
import com.sentinelvoice.directory.repo.EmployeeRepository;
import com.sentinelvoice.identity.model.DirectoryRecord;
import com.sentinelvoice.security.TenantContext;
import com.sentinelvoice.telephony.SipEndpointRepository;
import com.sentinelvoice.telephony.TelephonyModels;
import com.sentinelvoice.telephony.TelephonyResolveService;
import com.sentinelvoice.tenant.TenantEntity;
import com.sentinelvoice.tenant.TenantRepository;
import com.sentinelvoice.tenant.TenantSettingsEntity;
import com.sentinelvoice.tenant.TenantSettingsRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant directory resolve + employee/department CRUD (F4).
 * F10: CLI provenance consults sip_endpoints + trunks.cli_prefixes (SUSPECT_TRUNK).
 */
@Service
public class DirectoryService {

    private static final double DEFAULT_FUZZY_MIN = 0.72;

    private final EmployeeRepository employeeRepository;
    private final EmployeePhoneRepository phoneRepository;
    private final EmployeeAuthorityRepository authorityRepository;
    private final DepartmentRepository departmentRepository;
    private final TenantRepository tenantRepository;
    private final TenantSettingsRepository tenantSettingsRepository;
    private final PhoneNormaliser phoneNormaliser;
    private final AuditLedgerService auditLedgerService;
    private final SipEndpointRepository sipEndpointRepository;
    private final TelephonyResolveService telephonyResolveService;

    public DirectoryService(
            EmployeeRepository employeeRepository,
            EmployeePhoneRepository phoneRepository,
            EmployeeAuthorityRepository authorityRepository,
            DepartmentRepository departmentRepository,
            TenantRepository tenantRepository,
            TenantSettingsRepository tenantSettingsRepository,
            PhoneNormaliser phoneNormaliser,
            AuditLedgerService auditLedgerService,
            SipEndpointRepository sipEndpointRepository,
            @Lazy TelephonyResolveService telephonyResolveService
    ) {
        this.employeeRepository = employeeRepository;
        this.phoneRepository = phoneRepository;
        this.authorityRepository = authorityRepository;
        this.departmentRepository = departmentRepository;
        this.tenantRepository = tenantRepository;
        this.tenantSettingsRepository = tenantSettingsRepository;
        this.phoneNormaliser = phoneNormaliser;
        this.auditLedgerService = auditLedgerService;
        this.sipEndpointRepository = sipEndpointRepository;
        this.telephonyResolveService = telephonyResolveService;
    }

    // -------------------------------------------------------------------------
    // Resolve
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public DirectoryMatch resolve(UUID tenantId, String callerNumber, String claimedName, String claimedRole) {
        String region = tenantRepository.findById(tenantId).map(TenantEntity::getRegion).orElse("IN");
        double fuzzyMin = fuzzyThreshold(tenantId);

        DirectoryMatch.NumberProvenance provenance = DirectoryMatch.NumberProvenance.EXTERNAL_UNKNOWN;
        Optional<EmployeePhoneEntity> byNumber = Optional.empty();
        Optional<EmployeePhoneEntity> byExt = Optional.empty();
        Optional<UUID> sipEmployeeId = Optional.empty();

        if (callerNumber != null && !callerNumber.isBlank()) {
            if (phoneNormaliser.looksLikeExtension(callerNumber)) {
                String ext = phoneNormaliser.extensionDigits(callerNumber);
                byExt = phoneRepository.findFirstByTenantIdAndSipExtension(tenantId, ext);
                if (byExt.isPresent()) {
                    provenance = DirectoryMatch.NumberProvenance.INTERNAL_EXT;
                } else {
                    // F10: also match provisioned sip_endpoints
                    var sip = sipEndpointRepository.findByExtension(tenantId, ext);
                    if (sip.isPresent() && !"DISABLED".equals(sip.get().status())) {
                        provenance = DirectoryMatch.NumberProvenance.INTERNAL_EXT;
                        sipEmployeeId = Optional.ofNullable(sip.get().employeeId());
                    }
                }
            } else {
                Optional<String> e164 = phoneNormaliser.toE164(callerNumber, region);
                if (e164.isPresent()) {
                    byNumber = phoneRepository.findByTenantIdAndE164(tenantId, e164.get());
                    if (byNumber.isPresent()) {
                        provenance = DirectoryMatch.NumberProvenance.KNOWN_MOBILE;
                    }
                } else if (phoneNormaliser.looksLikeExtension(callerNumber)) {
                    byExt = phoneRepository.findFirstByTenantIdAndSipExtension(
                            tenantId, phoneNormaliser.extensionDigits(callerNumber));
                    if (byExt.isPresent()) {
                        provenance = DirectoryMatch.NumberProvenance.INTERNAL_EXT;
                    }
                }
            }

            // F10: replace remaining trunk stub — unmatched CLI matching carrier/CCAAS prefixes
            if (provenance == DirectoryMatch.NumberProvenance.EXTERNAL_UNKNOWN
                    && byNumber.isEmpty()
                    && byExt.isEmpty()
                    && sipEmployeeId.isEmpty()) {
                TelephonyModels.NumberClassifyResult cls =
                        telephonyResolveService.classifyNumber(tenantId, callerNumber);
                if (cls.classification() == TelephonyModels.NumberClass.SUSPECT_TRUNK) {
                    provenance = DirectoryMatch.NumberProvenance.SUSPECT_TRUNK;
                }
            }
        }

        if (byNumber.isPresent()) {
            return buildMatch(tenantId, byNumber.get().getEmployeeId(), DirectoryMatch.MatchType.NUMBER_EXACT, 1.0, provenance);
        }
        if (byExt.isPresent()) {
            return buildMatch(tenantId, byExt.get().getEmployeeId(), DirectoryMatch.MatchType.EXT_EXACT, 0.98, provenance);
        }
        if (sipEmployeeId.isPresent()) {
            return buildMatch(tenantId, sipEmployeeId.get(), DirectoryMatch.MatchType.EXT_EXACT, 0.98, provenance);
        }

        if (claimedName != null && !claimedName.isBlank()) {
            String roleNorm = NameSimilarity.normaliseRole(claimedRole);
            EmployeeEntity best = null;
            double bestScore = 0.0;
            for (EmployeeEntity e : employeeRepository.findByTenantId(tenantId)) {
                if ("TERMINATED".equals(e.getStatus())) {
                    continue;
                }
                double score = NameSimilarity.tokenJaccard(claimedName, e.getFullName());
                if (roleNorm != null && !roleNorm.isBlank() && e.getRoleKey() != null) {
                    if (NameSimilarity.normaliseRole(e.getRoleKey()).equals(roleNorm)) {
                        score = Math.min(1.0, score + 0.15);
                    }
                }
                if (score > bestScore) {
                    bestScore = score;
                    best = e;
                }
            }
            if (best != null && bestScore >= fuzzyMin) {
                return buildMatch(tenantId, best.getId(), DirectoryMatch.MatchType.NAME_FUZZY, bestScore, provenance);
            }
        }

        return DirectoryMatch.none(provenance);
    }

    private DirectoryMatch buildMatch(
            UUID tenantId,
            UUID employeeId,
            DirectoryMatch.MatchType type,
            double confidence,
            DirectoryMatch.NumberProvenance provenance
    ) {
        EmployeeEntity emp = employeeRepository.findByIdAndTenantId(employeeId, tenantId)
                .orElse(null);
        if (emp == null) {
            return DirectoryMatch.none(provenance);
        }
        String deptName = null;
        if (emp.getDepartmentId() != null) {
            deptName = departmentRepository.findByIdAndTenantId(emp.getDepartmentId(), tenantId)
                    .map(DepartmentEntity::getName)
                    .orElse(null);
        }
        List<Map<String, Object>> authority = authorityRepository.findByTenantIdAndEmployeeId(tenantId, employeeId)
                .stream()
                .map(this::authorityToMap)
                .toList();
        return new DirectoryMatch(
                emp.getId(),
                type,
                confidence,
                emp.getStatus(),
                emp.getRoleKey(),
                authority,
                provenance,
                emp.getFullName(),
                deptName,
                emp.isHighAuthority(),
                emp.getEmployeeCode()
        );
    }

    private double fuzzyThreshold(UUID tenantId) {
        return tenantSettingsRepository.findById(tenantId)
                .map(TenantSettingsEntity::getExtras)
                .map(extras -> {
                    Object dir = extras.get("directory");
                    if (dir instanceof Map<?, ?> m) {
                        Object v = m.get("nameFuzzyMin");
                        if (v instanceof Number n) {
                            return n.doubleValue();
                        }
                        if (v instanceof String s) {
                            try {
                                return Double.parseDouble(s);
                            } catch (NumberFormatException ignored) {
                                return DEFAULT_FUZZY_MIN;
                            }
                        }
                    }
                    return DEFAULT_FUZZY_MIN;
                })
                .orElse(DEFAULT_FUZZY_MIN);
    }

    // -------------------------------------------------------------------------
    // Adapter for v1 pipeline (REMOVE IN F7 — telemetry identity rebuilt from DirectoryMatch)
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Optional<DirectoryRecord> toDirectoryRecord(UUID tenantId, UUID employeeId) {
        return employeeRepository.findByIdAndTenantId(employeeId, tenantId).map(e -> toDirectoryRecord(tenantId, e));
    }

    public DirectoryRecord toDirectoryRecord(UUID tenantId, EmployeeEntity e) {
        DirectoryRecord r = new DirectoryRecord();
        r.setEmployeeId(e.getId().toString());
        r.setName(e.getFullName());
        r.setRole(e.getRoleKey());
        if (e.getDepartmentId() != null) {
            departmentRepository.findByIdAndTenantId(e.getDepartmentId(), tenantId)
                    .ifPresent(d -> r.setDepartment(d.getName()));
        }
        List<EmployeePhoneEntity> phones = phoneRepository
                .findByTenantIdAndEmployeeIdOrderByPrimaryDescCreatedAtAsc(tenantId, e.getId());
        phones.stream().filter(EmployeePhoneEntity::isPrimary).findFirst()
                .or(() -> phones.stream().findFirst())
                .ifPresent(p -> {
                    r.setPrimaryCli(p.getE164());
                    r.setExtension(p.getSipExtension());
                });
        double maxAuth = authorityRepository.findByTenantIdAndEmployeeId(tenantId, e.getId()).stream()
                .map(EmployeeAuthorityEntity::getMaxAmountInr)
                .filter(a -> a != null)
                .mapToDouble(BigDecimal::doubleValue)
                .max()
                .orElse(0.0);
        r.setVerbalAuthorityLimitInr(maxAuth);
        List<String> channels = authorityRepository.findByTenantIdAndEmployeeId(tenantId, e.getId()).stream()
                .flatMap(a -> a.getAllowedChannels().stream())
                .distinct()
                .toList();
        r.setPermittedChannels(String.join(",", channels));
        r.setHierarchyLevel(e.isHighAuthority() ? 1 : 5);
        r.setPassportEnrolled(false);
        r.setExpectedPresence(e.getStatus());
        return r;
    }

    @Transactional(readOnly = true)
    public Optional<DirectoryRecord> findByEmployeeId(String employeeId) {
        UUID tenantId = TenantContext.require().tenantId();
        UUID id;
        try {
            id = UUID.fromString(employeeId);
        } catch (Exception e) {
            return Optional.empty();
        }
        return toDirectoryRecord(tenantId, id);
    }

    @Transactional(readOnly = true)
    public Optional<DirectoryRecord> findByCli(String cli) {
        UUID tenantId = TenantContext.require().tenantId();
        DirectoryMatch match = resolve(tenantId, cli, null, null);
        if (match.matchedEmployeeId() == null) {
            return Optional.empty();
        }
        return toDirectoryRecord(tenantId, match.matchedEmployeeId());
    }

    @Transactional(readOnly = true)
    public Optional<DirectoryRecord> findByClaimedIdentity(String claimedName, String claimedRole) {
        UUID tenantId = TenantContext.require().tenantId();
        DirectoryMatch match = resolve(tenantId, null, claimedName, claimedRole);
        if (match.matchedEmployeeId() == null) {
            return Optional.empty();
        }
        return toDirectoryRecord(tenantId, match.matchedEmployeeId());
    }

    @Transactional(readOnly = true)
    public long count() {
        return employeeRepository.countByTenantId(TenantContext.require().tenantId());
    }

    // -------------------------------------------------------------------------
    // Departments
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listDepartments(UUID tenantId) {
        return departmentRepository.findByTenantIdOrderByNameAsc(tenantId).stream()
                .map(this::departmentToMap)
                .toList();
    }

    @Transactional
    public Map<String, Object> createDepartment(UUID tenantId, UUID actorId, String name, UUID parentId) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name required");
        }
        if (parentId != null) {
            departmentRepository.findByIdAndTenantId(parentId, tenantId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown parent"));
        }
        DepartmentEntity d = new DepartmentEntity();
        d.setId(UUID.randomUUID());
        d.setTenantId(tenantId);
        d.setName(name.trim());
        d.setParentId(parentId);
        d.setCreatedAt(Instant.now());
        departmentRepository.save(d);
        Map<String, Object> view = departmentToMap(d);
        audit(tenantId, actorId, AuditEventType.DIRECTORY_CREATED, "department", d.getId(), Map.of(), view);
        return view;
    }

    @Transactional
    public Map<String, Object> updateDepartment(UUID tenantId, UUID actorId, UUID id, String name, UUID parentId) {
        DepartmentEntity d = departmentRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "department not found"));
        Map<String, Object> before = departmentToMap(d);
        if (name != null && !name.isBlank()) {
            d.setName(name.trim());
        }
        if (parentId != null) {
            if (parentId.equals(id)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "parent cannot be self");
            }
            departmentRepository.findByIdAndTenantId(parentId, tenantId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown parent"));
            d.setParentId(parentId);
        }
        departmentRepository.save(d);
        Map<String, Object> after = departmentToMap(d);
        audit(tenantId, actorId, AuditEventType.DIRECTORY_UPDATED, "department", id, before, after);
        return after;
    }

    @Transactional
    public void deleteDepartment(UUID tenantId, UUID actorId, UUID id) {
        DepartmentEntity d = departmentRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "department not found"));
        if (departmentRepository.existsByTenantIdAndParentId(tenantId, id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "department has children");
        }
        Map<String, Object> before = departmentToMap(d);
        departmentRepository.delete(d);
        audit(tenantId, actorId, AuditEventType.DIRECTORY_DELETED, "department", id, before, Map.of());
    }

    // -------------------------------------------------------------------------
    // Employees
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<Map<String, Object>> searchEmployees(
            UUID tenantId, String q, UUID departmentId, String status, String roleKey, int page, int size
    ) {
        Page<EmployeeEntity> result = employeeRepository.search(
                tenantId,
                q == null || q.isBlank() ? null : q.trim().toLowerCase(java.util.Locale.ROOT),
                departmentId,
                blankToNull(status),
                roleKey == null || roleKey.isBlank()
                        ? null
                        : roleKey.trim().toLowerCase(java.util.Locale.ROOT),
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by("fullName"))
        );
        return result.map(e -> employeeSummary(tenantId, e));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getEmployee(UUID tenantId, UUID id) {
        EmployeeEntity e = employeeRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "employee not found"));
        return employeeDetail(tenantId, e);
    }

    @Transactional
    public Map<String, Object> createEmployee(UUID tenantId, UUID actorId, Map<String, Object> body) {
        String code = str(body, "employeeCode");
        String fullName = str(body, "fullName");
        if (code == null || fullName == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "employeeCode and fullName required");
        }
        if (employeeRepository.findByTenantIdAndEmployeeCodeIgnoreCase(tenantId, code).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "employee_code already exists");
        }
        EmployeeEntity e = new EmployeeEntity();
        e.setId(UUID.randomUUID());
        e.setTenantId(tenantId);
        e.setEmployeeCode(code.trim());
        e.setFullName(fullName.trim());
        applyEmployeeFields(tenantId, e, body, true);
        Instant now = Instant.now();
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        employeeRepository.save(e);
        Map<String, Object> view = employeeDetail(tenantId, e);
        audit(tenantId, actorId, AuditEventType.DIRECTORY_CREATED, "employee", e.getId(), Map.of(), view);
        return view;
    }

    @Transactional
    public Map<String, Object> updateEmployee(UUID tenantId, UUID actorId, UUID id, Map<String, Object> body) {
        EmployeeEntity e = employeeRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "employee not found"));
        Map<String, Object> before = employeeDetail(tenantId, e);
        applyEmployeeFields(tenantId, e, body, false);
        e.setUpdatedAt(Instant.now());
        employeeRepository.save(e);
        Map<String, Object> after = employeeDetail(tenantId, e);
        audit(tenantId, actorId, AuditEventType.DIRECTORY_UPDATED, "employee", id, before, after);
        return after;
    }

    @Transactional
    public void deleteEmployee(UUID tenantId, UUID actorId, UUID id) {
        EmployeeEntity e = employeeRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "employee not found"));
        Map<String, Object> before = employeeDetail(tenantId, e);
        authorityRepository.deleteByTenantIdAndEmployeeId(tenantId, id);
        phoneRepository.deleteByTenantIdAndEmployeeId(tenantId, id);
        employeeRepository.delete(e);
        audit(tenantId, actorId, AuditEventType.DIRECTORY_DELETED, "employee", id, before, Map.of());
    }

    @Transactional
    public Map<String, Object> setStatus(
            UUID tenantId, UUID actorId, UUID id, String status, Instant statusUntil, String statusNote
    ) {
        EmployeeEntity e = employeeRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "employee not found"));
        validateStatus(status);
        Map<String, Object> before = Map.of(
                "status", e.getStatus(),
                "statusUntil", e.getStatusUntil(),
                "statusNote", e.getStatusNote()
        );
        e.setStatus(status);
        e.setStatusUntil(statusUntil);
        e.setStatusNote(statusNote);
        e.setUpdatedAt(Instant.now());
        employeeRepository.save(e);
        Map<String, Object> after = Map.of(
                "status", e.getStatus(),
                "statusUntil", e.getStatusUntil(),
                "statusNote", e.getStatusNote()
        );
        audit(tenantId, actorId, AuditEventType.DIRECTORY_STATUS_CHANGED, "employee", id, before, after);
        return employeeDetail(tenantId, e);
    }

    // -------------------------------------------------------------------------
    // Phones / Authority
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listPhones(UUID tenantId, UUID employeeId) {
        requireEmployee(tenantId, employeeId);
        return phoneRepository.findByTenantIdAndEmployeeIdOrderByPrimaryDescCreatedAtAsc(tenantId, employeeId)
                .stream().map(this::phoneToMap).toList();
    }

    @Transactional
    public Map<String, Object> addPhone(UUID tenantId, UUID actorId, UUID employeeId, Map<String, Object> body) {
        requireEmployee(tenantId, employeeId);
        String region = tenantRepository.findById(tenantId).map(TenantEntity::getRegion).orElse("IN");
        String raw = str(body, "e164");
        if (raw == null) {
            raw = str(body, "phone");
        }
        String e164 = phoneNormaliser.toE164(raw, region)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid E.164 phone"));
        if (phoneRepository.existsByTenantIdAndE164(tenantId, e164)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "phone already registered");
        }
        String label = str(body, "label");
        if (label == null) {
            label = "MOBILE";
        }
        label = label.toUpperCase(Locale.ROOT);
        if (!List.of("OFFICE", "MOBILE", "HOME").contains(label)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "label must be OFFICE|MOBILE|HOME");
        }
        EmployeePhoneEntity p = new EmployeePhoneEntity();
        p.setId(UUID.randomUUID());
        p.setTenantId(tenantId);
        p.setEmployeeId(employeeId);
        p.setE164(e164);
        p.setLabel(label);
        p.setPrimary(bool(body, "primary", false) || bool(body, "isPrimary", false));
        p.setSipExtension(blankToNull(str(body, "sipExtension")));
        p.setCreatedAt(Instant.now());
        if (p.isPrimary()) {
            clearPrimary(tenantId, employeeId);
        }
        phoneRepository.save(p);
        Map<String, Object> view = phoneToMap(p);
        audit(tenantId, actorId, AuditEventType.DIRECTORY_CREATED, "employee_phone", p.getId(), Map.of(), view);
        return view;
    }

    @Transactional
    public void deletePhone(UUID tenantId, UUID actorId, UUID employeeId, UUID phoneId) {
        EmployeePhoneEntity p = phoneRepository.findByIdAndTenantId(phoneId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "phone not found"));
        if (!p.getEmployeeId().equals(employeeId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "phone not found");
        }
        Map<String, Object> before = phoneToMap(p);
        phoneRepository.delete(p);
        audit(tenantId, actorId, AuditEventType.DIRECTORY_DELETED, "employee_phone", phoneId, before, Map.of());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listAuthority(UUID tenantId, UUID employeeId) {
        requireEmployee(tenantId, employeeId);
        return authorityRepository.findByTenantIdAndEmployeeId(tenantId, employeeId).stream()
                .map(this::authorityToMap).toList();
    }

    @Transactional
    public Map<String, Object> addAuthority(UUID tenantId, UUID actorId, UUID employeeId, Map<String, Object> body) {
        requireEmployee(tenantId, employeeId);
        String actionType = str(body, "actionType");
        if (actionType == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "actionType required");
        }
        EmployeeAuthorityEntity a = new EmployeeAuthorityEntity();
        a.setId(UUID.randomUUID());
        a.setTenantId(tenantId);
        a.setEmployeeId(employeeId);
        a.setActionType(actionType.trim().toUpperCase(Locale.ROOT));
        if (body.get("maxAmountInr") != null) {
            a.setMaxAmountInr(new BigDecimal(body.get("maxAmountInr").toString()));
        }
        a.setRequiresDualApproval(bool(body, "requiresDualApproval", false));
        Object channels = body.get("allowedChannels");
        if (channels instanceof List<?> list) {
            a.setAllowedChannels(list.stream().map(String::valueOf).toList());
        } else {
            a.setAllowedChannels(List.of("VOICE"));
        }
        a.setCreatedAt(Instant.now());
        authorityRepository.save(a);
        Map<String, Object> view = authorityToMap(a);
        audit(tenantId, actorId, AuditEventType.DIRECTORY_CREATED, "employee_authority", a.getId(), Map.of(), view);
        return view;
    }

    @Transactional
    public void deleteAuthority(UUID tenantId, UUID actorId, UUID employeeId, UUID authId) {
        EmployeeAuthorityEntity a = authorityRepository.findByIdAndTenantId(authId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "authority not found"));
        if (!a.getEmployeeId().equals(employeeId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "authority not found");
        }
        Map<String, Object> before = authorityToMap(a);
        authorityRepository.delete(a);
        audit(tenantId, actorId, AuditEventType.DIRECTORY_DELETED, "employee_authority", authId, before, Map.of());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> orgChart(UUID tenantId) {
        List<Map<String, Object>> departments = listDepartments(tenantId);
        List<Map<String, Object>> employees = employeeRepository.findByTenantId(tenantId).stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", e.getId());
                    m.put("fullName", e.getFullName());
                    m.put("roleKey", e.getRoleKey());
                    m.put("departmentId", e.getDepartmentId());
                    m.put("managerId", e.getManagerId());
                    m.put("status", e.getStatus());
                    return m;
                }).toList();
        return Map.of("departments", departments, "employees", employees);
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private void applyEmployeeFields(UUID tenantId, EmployeeEntity e, Map<String, Object> body, boolean creating) {
        if (body.containsKey("email") || creating) {
            e.setEmail(blankToNull(str(body, "email")));
        }
        if (body.containsKey("jobTitle") || creating) {
            e.setJobTitle(blankToNull(str(body, "jobTitle")));
        }
        if (body.containsKey("roleKey") || creating) {
            String rk = str(body, "roleKey");
            e.setRoleKey(rk == null ? null : NameSimilarity.normaliseRole(rk));
        }
        if (body.containsKey("departmentId")) {
            UUID deptId = uuidOrNull(body.get("departmentId"));
            if (deptId != null) {
                departmentRepository.findByIdAndTenantId(deptId, tenantId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown department"));
            }
            e.setDepartmentId(deptId);
        }
        if (body.containsKey("managerId")) {
            UUID mgr = uuidOrNull(body.get("managerId"));
            if (mgr != null) {
                if (mgr.equals(e.getId())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "manager cannot be self");
                }
                employeeRepository.findByIdAndTenantId(mgr, tenantId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown manager"));
            }
            e.setManagerId(mgr);
        }
        if (body.containsKey("highAuthority")) {
            e.setHighAuthority(bool(body, "highAuthority", false));
        }
        if (body.containsKey("fullName") && !creating) {
            String n = str(body, "fullName");
            if (n != null) {
                e.setFullName(n.trim());
            }
        }
        if (body.containsKey("status") && creating) {
            String st = str(body, "status");
            if (st != null) {
                validateStatus(st);
                e.setStatus(st);
            }
        }
    }

    private void clearPrimary(UUID tenantId, UUID employeeId) {
        for (EmployeePhoneEntity p : phoneRepository
                .findByTenantIdAndEmployeeIdOrderByPrimaryDescCreatedAtAsc(tenantId, employeeId)) {
            if (p.isPrimary()) {
                p.setPrimary(false);
                phoneRepository.save(p);
            }
        }
    }

    private EmployeeEntity requireEmployee(UUID tenantId, UUID employeeId) {
        return employeeRepository.findByIdAndTenantId(employeeId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "employee not found"));
    }

    private void validateStatus(String status) {
        if (status == null || !DirectoryMatch.EMPLOYEE_STATUSES.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid status");
        }
    }

    private void audit(
            UUID tenantId,
            UUID actorId,
            AuditEventType type,
            String entity,
            UUID entityId,
            Map<String, Object> before,
            Map<String, Object> after
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entity", entity);
        payload.put("entityId", entityId == null ? null : entityId.toString());
        payload.put("diff", DirectoryAuditSupport.diff(before, after));
        auditLedgerService.append(
                tenantId,
                null,
                type,
                "USER",
                actorId == null ? null : actorId.toString(),
                payload
        );
    }

    private Map<String, Object> departmentToMap(DepartmentEntity d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("name", d.getName());
        m.put("parentId", d.getParentId());
        m.put("createdAt", d.getCreatedAt());
        return m;
    }

    private Map<String, Object> employeeSummary(UUID tenantId, EmployeeEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("employeeCode", e.getEmployeeCode());
        m.put("fullName", e.getFullName());
        m.put("email", e.getEmail());
        m.put("departmentId", e.getDepartmentId());
        if (e.getDepartmentId() != null) {
            departmentRepository.findByIdAndTenantId(e.getDepartmentId(), tenantId)
                    .ifPresent(d -> m.put("departmentName", d.getName()));
        }
        m.put("jobTitle", e.getJobTitle());
        m.put("roleKey", e.getRoleKey());
        m.put("managerId", e.getManagerId());
        m.put("status", e.getStatus());
        m.put("statusUntil", e.getStatusUntil());
        m.put("highAuthority", e.isHighAuthority());
        phoneRepository.findByTenantIdAndEmployeeIdOrderByPrimaryDescCreatedAtAsc(tenantId, e.getId())
                .stream().findFirst()
                .ifPresent(p -> m.put("primaryPhone", p.getE164()));
        return m;
    }

    private Map<String, Object> employeeDetail(UUID tenantId, EmployeeEntity e) {
        Map<String, Object> m = employeeSummary(tenantId, e);
        m.put("statusNote", e.getStatusNote());
        m.put("createdAt", e.getCreatedAt());
        m.put("updatedAt", e.getUpdatedAt());
        m.put("phones", listPhones(tenantId, e.getId()));
        m.put("authority", listAuthority(tenantId, e.getId()));
        return m;
    }

    Map<String, Object> phoneToMap(EmployeePhoneEntity p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("employeeId", p.getEmployeeId());
        m.put("e164", p.getE164());
        m.put("label", p.getLabel());
        m.put("primary", p.isPrimary());
        m.put("sipExtension", p.getSipExtension());
        return m;
    }

    Map<String, Object> authorityToMap(EmployeeAuthorityEntity a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("employeeId", a.getEmployeeId());
        m.put("actionType", a.getActionType());
        m.put("maxAmountInr", a.getMaxAmountInr());
        m.put("requiresDualApproval", a.isRequiresDualApproval());
        m.put("allowedChannels", a.getAllowedChannels());
        return m;
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

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
