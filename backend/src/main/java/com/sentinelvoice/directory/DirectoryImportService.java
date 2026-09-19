package com.sentinelvoice.directory;

import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.audit.AuditLedgerService;
import com.sentinelvoice.directory.entity.DirectoryImportEntity;
import com.sentinelvoice.directory.entity.EmployeeAuthorityEntity;
import com.sentinelvoice.directory.entity.EmployeeEntity;
import com.sentinelvoice.directory.entity.EmployeePhoneEntity;
import com.sentinelvoice.directory.entity.ExternalPartyEntity;
import com.sentinelvoice.directory.repo.DepartmentRepository;
import com.sentinelvoice.directory.repo.DirectoryImportRepository;
import com.sentinelvoice.directory.repo.EmployeeAuthorityRepository;
import com.sentinelvoice.directory.repo.EmployeePhoneRepository;
import com.sentinelvoice.directory.repo.EmployeeRepository;
import com.sentinelvoice.directory.repo.ExternalPartyRepository;
import com.sentinelvoice.tenant.TenantEntity;
import com.sentinelvoice.tenant.TenantRepository;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class DirectoryImportService {

    private final DirectoryImportRepository importRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeePhoneRepository phoneRepository;
    private final EmployeeAuthorityRepository authorityRepository;
    private final ExternalPartyRepository externalPartyRepository;
    private final DepartmentRepository departmentRepository;
    private final TenantRepository tenantRepository;
    private final PhoneNormaliser phoneNormaliser;
    private final AuditLedgerService auditLedgerService;

    public DirectoryImportService(
            DirectoryImportRepository importRepository,
            EmployeeRepository employeeRepository,
            EmployeePhoneRepository phoneRepository,
            EmployeeAuthorityRepository authorityRepository,
            ExternalPartyRepository externalPartyRepository,
            DepartmentRepository departmentRepository,
            TenantRepository tenantRepository,
            PhoneNormaliser phoneNormaliser,
            AuditLedgerService auditLedgerService
    ) {
        this.importRepository = importRepository;
        this.employeeRepository = employeeRepository;
        this.phoneRepository = phoneRepository;
        this.authorityRepository = authorityRepository;
        this.externalPartyRepository = externalPartyRepository;
        this.departmentRepository = departmentRepository;
        this.tenantRepository = tenantRepository;
        this.phoneNormaliser = phoneNormaliser;
        this.auditLedgerService = auditLedgerService;
    }

    public byte[] templateCsv(String kind) {
        return switch (kind.toUpperCase(Locale.ROOT)) {
            case "EMPLOYEES" -> """
                    employee_code,full_name,email,department_name,job_title,role_key,manager_code,high_authority,status,e164,phone_label,sip_extension
                    E001,Ada Lovelace,ada@example.com,Finance,CFO,CFO,,true,ACTIVE,+919876543210,MOBILE,
                    """.getBytes(StandardCharsets.UTF_8);
            case "PHONES" -> """
                    employee_code,e164,label,is_primary,sip_extension
                    E001,+919876543210,MOBILE,true,
                    """.getBytes(StandardCharsets.UTF_8);
            case "AUTHORITY" -> """
                    employee_code,action_type,max_amount_inr,requires_dual_approval,allowed_channels
                    E001,WIRE_TRANSFER,500000,true,"VOICE;EMAIL"
                    """.getBytes(StandardCharsets.UTF_8);
            case "EXTERNAL_PARTIES" -> """
                    name,type,phone,verified,notes
                    Acme Vendors,VENDOR,+912212345678,false,AP vendor
                    """.getBytes(StandardCharsets.UTF_8);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown kind");
        };
    }

    @Transactional
    public Map<String, Object> dryRun(UUID tenantId, UUID actorId, String kind, MultipartFile file) {
        String k = kind.toUpperCase(Locale.ROOT);
        List<Map<String, String>> rows = parseFile(file);
        String region = tenantRepository.findById(tenantId).map(TenantEntity::getRegion).orElse("IN");
        List<Map<String, Object>> rowErrors = new ArrayList<>();
        List<Map<String, Object>> validRows = new ArrayList<>();
        Set<String> seenCodes = new LinkedHashSet<>();
        Set<String> seenPhones = new LinkedHashSet<>();
        int rowNum = 1;
        for (Map<String, String> row : rows) {
            rowNum++;
            List<String> errors = validateRow(tenantId, k, row, region, seenCodes, seenPhones);
            if (errors.isEmpty()) {
                Map<String, Object> vr = new LinkedHashMap<>(row);
                vr.put("_row", rowNum);
                validRows.add(vr);
            } else {
                Map<String, Object> er = new LinkedHashMap<>();
                er.put("row", rowNum);
                er.put("errors", errors);
                er.put("data", row);
                rowErrors.add(er);
            }
        }
        DirectoryImportEntity imp = new DirectoryImportEntity();
        imp.setId(UUID.randomUUID());
        imp.setTenantId(tenantId);
        imp.setUploadedBy(actorId);
        imp.setFilename(file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename());
        imp.setKind(k);
        imp.setStatus("DRY_RUN");
        imp.setRowCount(rows.size());
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("rowErrors", rowErrors);
        report.put("validRows", validRows);
        report.put("errorCount", rowErrors.size());
        report.put("validCount", validRows.size());
        imp.setErrorReport(report);
        imp.setCreatedAt(Instant.now());
        importRepository.save(imp);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("importId", imp.getId());
        out.put("kind", k);
        out.put("rowCount", rows.size());
        out.put("validCount", validRows.size());
        out.put("errorCount", rowErrors.size());
        out.put("rowErrors", rowErrors);
        out.put("canCommit", rowErrors.isEmpty() && !validRows.isEmpty());
        return out;
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> commit(UUID tenantId, UUID actorId, UUID importId) {
        DirectoryImportEntity imp = importRepository.findByIdAndTenantId(importId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "import not found"));
        if (!"DRY_RUN".equals(imp.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "import already finalised");
        }
        Map<String, Object> report = imp.getErrorReport();
        int errorCount = report.get("errorCount") instanceof Number n ? n.intValue() : 0;
        if (errorCount > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "fix row errors before commit");
        }
        List<Map<String, Object>> validRows = (List<Map<String, Object>>) report.getOrDefault("validRows", List.of());
        String region = tenantRepository.findById(tenantId).map(TenantEntity::getRegion).orElse("IN");
        int created = 0;
        for (Map<String, Object> rowObj : validRows) {
            Map<String, String> row = new LinkedHashMap<>();
            rowObj.forEach((k, v) -> {
                if (!k.startsWith("_")) {
                    row.put(k, v == null ? "" : String.valueOf(v));
                }
            });
            applyRow(tenantId, imp.getKind(), row, region);
            created++;
        }
        imp.setStatus("COMMITTED");
        report.put("committedCount", created);
        imp.setErrorReport(report);
        importRepository.save(imp);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entity", "directory_import");
        payload.put("entityId", imp.getId().toString());
        payload.put("kind", imp.getKind());
        payload.put("committedCount", created);
        auditLedgerService.append(tenantId, null, AuditEventType.DIRECTORY_IMPORTED, "USER",
                actorId == null ? null : actorId.toString(), payload);

        return Map.of(
                "importId", imp.getId(),
                "status", "COMMITTED",
                "committedCount", created
        );
    }

    @Transactional(readOnly = true)
    public Page<Map<String, Object>> history(UUID tenantId, int page, int size) {
        return importRepository.findByTenantIdOrderByCreatedAtDesc(
                tenantId, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 50))
        ).map(imp -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", imp.getId());
            m.put("filename", imp.getFilename());
            m.put("kind", imp.getKind());
            m.put("status", imp.getStatus());
            m.put("rowCount", imp.getRowCount());
            m.put("errorCount", imp.getErrorReport().get("errorCount"));
            m.put("createdAt", imp.getCreatedAt());
            return m;
        });
    }

    private List<String> validateRow(
            UUID tenantId,
            String kind,
            Map<String, String> row,
            String region,
            Set<String> seenCodes,
            Set<String> seenPhones
    ) {
        List<String> errors = new ArrayList<>();
        switch (kind) {
            case "EMPLOYEES" -> {
                String code = row.get("employee_code");
                if (blank(code)) {
                    errors.add("employee_code required");
                } else {
                    String codeKey = code.trim().toLowerCase(Locale.ROOT);
                    if (!seenCodes.add(codeKey)) {
                        errors.add("duplicate employee_code in file");
                    } else if (employeeRepository.findByTenantIdAndEmployeeCodeIgnoreCase(tenantId, code).isPresent()) {
                        errors.add("duplicate employee_code");
                    }
                }
                if (blank(row.get("full_name"))) {
                    errors.add("full_name required");
                }
                String dept = row.get("department_name");
                if (!blank(dept) && departmentRepository.findByTenantIdAndNameIgnoreCase(tenantId, dept).isEmpty()) {
                    errors.add("unknown department_name: " + dept);
                }
                String mgr = row.get("manager_code");
                if (!blank(mgr)) {
                    String mgrKey = mgr.trim().toLowerCase(Locale.ROOT);
                    boolean inDb = employeeRepository
                            .findByTenantIdAndEmployeeCodeIgnoreCase(tenantId, mgr).isPresent();
                    boolean earlierInFile = seenCodes.contains(mgrKey);
                    if (!inDb && !earlierInFile) {
                        errors.add("unknown manager_code: " + mgr);
                    }
                }
                validateOptionalPhone(tenantId, row, region, seenPhones, errors);
            }
            case "PHONES" -> {
                if (blank(row.get("employee_code"))) {
                    errors.add("employee_code required");
                } else if (employeeRepository.findByTenantIdAndEmployeeCodeIgnoreCase(tenantId, row.get("employee_code")).isEmpty()) {
                    errors.add("unknown employee_code");
                }
                if (blank(row.get("e164"))) {
                    errors.add("e164 required");
                } else {
                    var e164 = phoneNormaliser.toE164(row.get("e164"), region);
                    if (e164.isEmpty()) {
                        errors.add("invalid E.164: " + row.get("e164"));
                    } else if (!seenPhones.add(e164.get()) || phoneRepository.existsByTenantIdAndE164(tenantId, e164.get())) {
                        errors.add("duplicate phone " + e164.get());
                    }
                }
            }
            case "AUTHORITY" -> {
                if (blank(row.get("employee_code"))) {
                    errors.add("employee_code required");
                } else if (employeeRepository.findByTenantIdAndEmployeeCodeIgnoreCase(tenantId, row.get("employee_code")).isEmpty()) {
                    errors.add("unknown employee_code");
                }
                if (blank(row.get("action_type"))) {
                    errors.add("action_type required");
                }
            }
            case "EXTERNAL_PARTIES" -> {
                if (blank(row.get("name"))) {
                    errors.add("name required");
                }
                String type = row.getOrDefault("type", "").toUpperCase(Locale.ROOT);
                if (!List.of("VENDOR", "CUSTOMER", "REGULATOR", "PARTNER", "OTHER").contains(type)) {
                    errors.add("invalid type");
                }
                if (!blank(row.get("phone")) && phoneNormaliser.toE164(row.get("phone"), region).isEmpty()) {
                    errors.add("invalid phone E.164");
                }
            }
            default -> errors.add("unknown kind");
        }
        return errors;
    }

    private void validateOptionalPhone(
            UUID tenantId,
            Map<String, String> row,
            String region,
            Set<String> seenPhones,
            List<String> errors
    ) {
        if (blank(row.get("e164"))) {
            return;
        }
        var e164 = phoneNormaliser.toE164(row.get("e164"), region);
        if (e164.isEmpty()) {
            errors.add("invalid E.164: " + row.get("e164"));
            return;
        }
        if (!seenPhones.add(e164.get()) || phoneRepository.existsByTenantIdAndE164(tenantId, e164.get())) {
            errors.add("duplicate phone " + e164.get());
        }
    }

    private void applyRow(UUID tenantId, String kind, Map<String, String> row, String region) {
        Instant now = Instant.now();
        switch (kind) {
            case "EMPLOYEES" -> {
                EmployeeEntity e = new EmployeeEntity();
                e.setId(UUID.randomUUID());
                e.setTenantId(tenantId);
                e.setEmployeeCode(row.get("employee_code").trim());
                e.setFullName(row.get("full_name").trim());
                e.setEmail(blank(row.get("email")) ? null : row.get("email").trim());
                e.setJobTitle(blank(row.get("job_title")) ? null : row.get("job_title").trim());
                e.setRoleKey(blank(row.get("role_key")) ? null : NameSimilarity.normaliseRole(row.get("role_key")));
                e.setHighAuthority(Boolean.parseBoolean(row.getOrDefault("high_authority", "false")));
                String status = blank(row.get("status")) ? "ACTIVE" : row.get("status").trim().toUpperCase(Locale.ROOT);
                e.setStatus(status);
                if (!blank(row.get("department_name"))) {
                    departmentRepository.findByTenantIdAndNameIgnoreCase(tenantId, row.get("department_name"))
                            .ifPresent(d -> e.setDepartmentId(d.getId()));
                }
                if (!blank(row.get("manager_code"))) {
                    employeeRepository.findByTenantIdAndEmployeeCodeIgnoreCase(tenantId, row.get("manager_code"))
                            .ifPresent(m -> e.setManagerId(m.getId()));
                }
                e.setCreatedAt(now);
                e.setUpdatedAt(now);
                employeeRepository.save(e);
                if (!blank(row.get("e164"))) {
                    String e164 = phoneNormaliser.toE164(row.get("e164"), region).orElseThrow();
                    EmployeePhoneEntity p = new EmployeePhoneEntity();
                    p.setId(UUID.randomUUID());
                    p.setTenantId(tenantId);
                    p.setEmployeeId(e.getId());
                    p.setE164(e164);
                    String label = blank(row.get("phone_label")) ? "MOBILE" : row.get("phone_label").trim().toUpperCase(Locale.ROOT);
                    p.setLabel(label);
                    p.setPrimary(true);
                    p.setSipExtension(blank(row.get("sip_extension")) ? null : row.get("sip_extension").trim());
                    p.setCreatedAt(now);
                    phoneRepository.save(p);
                }
            }
            case "PHONES" -> {
                EmployeeEntity emp = employeeRepository
                        .findByTenantIdAndEmployeeCodeIgnoreCase(tenantId, row.get("employee_code"))
                        .orElseThrow();
                String e164 = phoneNormaliser.toE164(row.get("e164"), region).orElseThrow();
                EmployeePhoneEntity p = new EmployeePhoneEntity();
                p.setId(UUID.randomUUID());
                p.setTenantId(tenantId);
                p.setEmployeeId(emp.getId());
                p.setE164(e164);
                p.setLabel(blank(row.get("label")) ? "MOBILE" : row.get("label").trim().toUpperCase(Locale.ROOT));
                p.setPrimary(Boolean.parseBoolean(row.getOrDefault("is_primary", "false")));
                p.setSipExtension(blank(row.get("sip_extension")) ? null : row.get("sip_extension").trim());
                p.setCreatedAt(now);
                phoneRepository.save(p);
            }
            case "AUTHORITY" -> {
                EmployeeEntity emp = employeeRepository
                        .findByTenantIdAndEmployeeCodeIgnoreCase(tenantId, row.get("employee_code"))
                        .orElseThrow();
                EmployeeAuthorityEntity a = new EmployeeAuthorityEntity();
                a.setId(UUID.randomUUID());
                a.setTenantId(tenantId);
                a.setEmployeeId(emp.getId());
                a.setActionType(row.get("action_type").trim().toUpperCase(Locale.ROOT));
                if (!blank(row.get("max_amount_inr"))) {
                    a.setMaxAmountInr(new BigDecimal(row.get("max_amount_inr").trim()));
                }
                a.setRequiresDualApproval(Boolean.parseBoolean(row.getOrDefault("requires_dual_approval", "false")));
                String channels = row.getOrDefault("allowed_channels", "VOICE");
                a.setAllowedChannels(Arrays.stream(channels.split("[;|,]"))
                        .map(String::trim).filter(s -> !s.isEmpty()).toList());
                a.setCreatedAt(now);
                authorityRepository.save(a);
            }
            case "EXTERNAL_PARTIES" -> {
                ExternalPartyEntity e = new ExternalPartyEntity();
                e.setId(UUID.randomUUID());
                e.setTenantId(tenantId);
                e.setName(row.get("name").trim());
                e.setType(row.get("type").trim().toUpperCase(Locale.ROOT));
                e.setVerified(Boolean.parseBoolean(row.getOrDefault("verified", "false")));
                e.setNotes(blank(row.get("notes")) ? null : row.get("notes"));
                List<Map<String, Object>> phones = new ArrayList<>();
                if (!blank(row.get("phone"))) {
                    phoneNormaliser.toE164(row.get("phone"), region)
                            .ifPresent(p -> phones.add(Map.of("e164", p, "label", "OFFICE")));
                }
                e.setPhones(phones);
                e.setCreatedAt(now);
                e.setUpdatedAt(now);
                externalPartyRepository.save(e);
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown kind");
        }
    }

    private List<Map<String, String>> parseFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file required");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        try {
            byte[] bytes = file.getBytes();
            if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
                return parseXlsx(bytes);
            }
            return parseCsv(bytes);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "failed to parse file: " + e.getMessage());
        }
    }

    private List<Map<String, String>> parseCsv(byte[] bytes) throws Exception {
        try (Reader reader = new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .setIgnoreEmptyLines(true)
                     .setTrim(true)
                     .build()
                     .parse(reader)) {
            List<Map<String, String>> rows = new ArrayList<>();
            for (CSVRecord rec : parser) {
                Map<String, String> row = new LinkedHashMap<>();
                for (String h : parser.getHeaderNames()) {
                    row.put(normaliseHeader(h), rec.isMapped(h) ? rec.get(h) : "");
                }
                if (row.values().stream().allMatch(v -> v == null || v.isBlank())) {
                    continue;
                }
                rows.add(row);
            }
            return rows;
        }
    }

    private List<Map<String, String>> parseXlsx(byte[] bytes) throws Exception {
        DataFormatter fmt = new DataFormatter();
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            Row header = sheet.getRow(0);
            if (header == null) {
                return List.of();
            }
            List<String> headers = new ArrayList<>();
            for (Cell cell : header) {
                headers.add(normaliseHeader(fmt.formatCellValue(cell)));
            }
            List<Map<String, String>> rows = new ArrayList<>();
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row r = sheet.getRow(i);
                if (r == null) {
                    continue;
                }
                Map<String, String> row = new LinkedHashMap<>();
                boolean any = false;
                for (int c = 0; c < headers.size(); c++) {
                    Cell cell = r.getCell(c);
                    String v = cell == null ? "" : fmt.formatCellValue(cell).trim();
                    if (!v.isEmpty()) {
                        any = true;
                    }
                    row.put(headers.get(c), v);
                }
                if (any) {
                    rows.add(row);
                }
            }
            return rows;
        }
    }

    private static String normaliseHeader(String h) {
        return h == null ? "" : h.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
