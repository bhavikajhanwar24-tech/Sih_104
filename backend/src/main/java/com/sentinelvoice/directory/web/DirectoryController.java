package com.sentinelvoice.directory.web;

import com.sentinelvoice.directory.DirectoryMatch;
import com.sentinelvoice.directory.DirectoryService;
import com.sentinelvoice.directory.ExternalDirectoryService;
import com.sentinelvoice.directory.DirectoryImportService;
import com.sentinelvoice.directory.RelationshipService;
import com.sentinelvoice.directory.RelationshipView;
import com.sentinelvoice.security.TenantContext;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v2/directory")
public class DirectoryController {

    private final DirectoryService directoryService;
    private final ExternalDirectoryService externalDirectoryService;
    private final RelationshipService relationshipService;
    private final DirectoryImportService importService;

    public DirectoryController(
            DirectoryService directoryService,
            ExternalDirectoryService externalDirectoryService,
            RelationshipService relationshipService,
            DirectoryImportService importService
    ) {
        this.directoryService = directoryService;
        this.externalDirectoryService = externalDirectoryService;
        this.relationshipService = relationshipService;
        this.importService = importService;
    }

    // --- Departments ---

    @GetMapping("/departments")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','ANALYST','AUDITOR')")
    public Map<String, Object> listDepartments() {
        TenantContext ctx = TenantContext.require();
        return Map.of("items", directoryService.listDepartments(ctx.tenantId()));
    }

    @PostMapping("/departments")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<Map<String, Object>> createDepartment(@RequestBody Map<String, Object> body) {
        TenantContext ctx = TenantContext.require();
        UUID parentId = body.get("parentId") == null || String.valueOf(body.get("parentId")).isBlank()
                ? null : UUID.fromString(String.valueOf(body.get("parentId")));
        return ResponseEntity.status(HttpStatus.CREATED).body(
                directoryService.createDepartment(ctx.tenantId(), ctx.userId(),
                        String.valueOf(body.getOrDefault("name", "")), parentId)
        );
    }

    @PutMapping("/departments/{id}")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public Map<String, Object> updateDepartment(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        TenantContext ctx = TenantContext.require();
        UUID parentId = body.containsKey("parentId")
                ? (body.get("parentId") == null || String.valueOf(body.get("parentId")).isBlank()
                ? null : UUID.fromString(String.valueOf(body.get("parentId"))))
                : null;
        return directoryService.updateDepartment(ctx.tenantId(), ctx.userId(), id,
                body.containsKey("name") ? String.valueOf(body.get("name")) : null, parentId);
    }

    @DeleteMapping("/departments/{id}")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<Void> deleteDepartment(@PathVariable UUID id) {
        TenantContext ctx = TenantContext.require();
        directoryService.deleteDepartment(ctx.tenantId(), ctx.userId(), id);
        return ResponseEntity.noContent().build();
    }

    // --- Employees ---

    @GetMapping("/employees")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','ANALYST','AUDITOR')")
    public Map<String, Object> listEmployees(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID departmentId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String roleKey,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        TenantContext ctx = TenantContext.require();
        Page<Map<String, Object>> result = directoryService.searchEmployees(
                ctx.tenantId(), q, departmentId, status, roleKey, page, size);
        return pageResponse(result);
    }

    @GetMapping("/employees/{id}")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','ANALYST','AUDITOR')")
    public Map<String, Object> getEmployee(@PathVariable UUID id) {
        return directoryService.getEmployee(TenantContext.require().tenantId(), id);
    }

    @PostMapping("/employees")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<Map<String, Object>> createEmployee(@RequestBody Map<String, Object> body) {
        TenantContext ctx = TenantContext.require();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(directoryService.createEmployee(ctx.tenantId(), ctx.userId(), body));
    }

    @PutMapping("/employees/{id}")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public Map<String, Object> updateEmployee(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        TenantContext ctx = TenantContext.require();
        return directoryService.updateEmployee(ctx.tenantId(), ctx.userId(), id, body);
    }

    @DeleteMapping("/employees/{id}")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<Void> deleteEmployee(@PathVariable UUID id) {
        TenantContext ctx = TenantContext.require();
        directoryService.deleteEmployee(ctx.tenantId(), ctx.userId(), id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/employees/{id}/status")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public Map<String, Object> setStatus(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        TenantContext ctx = TenantContext.require();
        Instant until = null;
        if (body.get("statusUntil") != null && !String.valueOf(body.get("statusUntil")).isBlank()) {
            until = Instant.parse(String.valueOf(body.get("statusUntil")));
        }
        return directoryService.setStatus(
                ctx.tenantId(),
                ctx.userId(),
                id,
                String.valueOf(body.get("status")),
                until,
                body.get("statusNote") == null ? null : String.valueOf(body.get("statusNote"))
        );
    }

    @GetMapping("/employees/{id}/phones")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','ANALYST','AUDITOR')")
    public Map<String, Object> listPhones(@PathVariable UUID id) {
        return Map.of("items", directoryService.listPhones(TenantContext.require().tenantId(), id));
    }

    @PostMapping("/employees/{id}/phones")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<Map<String, Object>> addPhone(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        TenantContext ctx = TenantContext.require();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(directoryService.addPhone(ctx.tenantId(), ctx.userId(), id, body));
    }

    @DeleteMapping("/employees/{id}/phones/{phoneId}")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<Void> deletePhone(@PathVariable UUID id, @PathVariable UUID phoneId) {
        TenantContext ctx = TenantContext.require();
        directoryService.deletePhone(ctx.tenantId(), ctx.userId(), id, phoneId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/employees/{id}/authority")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','ANALYST','AUDITOR')")
    public Map<String, Object> listAuthority(@PathVariable UUID id) {
        return Map.of("items", directoryService.listAuthority(TenantContext.require().tenantId(), id));
    }

    @PostMapping("/employees/{id}/authority")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<Map<String, Object>> addAuthority(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        TenantContext ctx = TenantContext.require();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(directoryService.addAuthority(ctx.tenantId(), ctx.userId(), id, body));
    }

    @DeleteMapping("/employees/{id}/authority/{authId}")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<Void> deleteAuthority(@PathVariable UUID id, @PathVariable UUID authId) {
        TenantContext ctx = TenantContext.require();
        directoryService.deleteAuthority(ctx.tenantId(), ctx.userId(), id, authId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/employees/{id}/relationships")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','ANALYST','AUDITOR')")
    public Map<String, Object> listRelationships(@PathVariable UUID id) {
        return Map.of("items", relationshipService.listForEmployee(TenantContext.require().tenantId(), id));
    }

    // --- Externals / Beneficiaries ---

    @GetMapping("/external-parties")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','ANALYST','AUDITOR')")
    public Map<String, Object> listExternals(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        return pageResponse(externalDirectoryService.searchExternals(
                TenantContext.require().tenantId(), q, type, page, size));
    }

    @PostMapping("/external-parties")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<Map<String, Object>> createExternal(@RequestBody Map<String, Object> body) {
        TenantContext ctx = TenantContext.require();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(externalDirectoryService.createExternal(ctx.tenantId(), ctx.userId(), body));
    }

    @PutMapping("/external-parties/{id}")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public Map<String, Object> updateExternal(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        TenantContext ctx = TenantContext.require();
        return externalDirectoryService.updateExternal(ctx.tenantId(), ctx.userId(), id, body);
    }

    @DeleteMapping("/external-parties/{id}")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<Void> deleteExternal(@PathVariable UUID id) {
        TenantContext ctx = TenantContext.require();
        externalDirectoryService.deleteExternal(ctx.tenantId(), ctx.userId(), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/beneficiaries")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','ANALYST','AUDITOR')")
    public Map<String, Object> listBeneficiaries(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        return pageResponse(externalDirectoryService.listBeneficiaries(
                TenantContext.require().tenantId(), page, size));
    }

    @PostMapping("/beneficiaries")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<Map<String, Object>> createBeneficiary(@RequestBody Map<String, Object> body) {
        TenantContext ctx = TenantContext.require();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(externalDirectoryService.createBeneficiary(ctx.tenantId(), ctx.userId(), body));
    }

    @PutMapping("/beneficiaries/{id}")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public Map<String, Object> updateBeneficiary(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        TenantContext ctx = TenantContext.require();
        return externalDirectoryService.updateBeneficiary(ctx.tenantId(), ctx.userId(), id, body);
    }

    @DeleteMapping("/beneficiaries/{id}")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<Void> deleteBeneficiary(@PathVariable UUID id) {
        TenantContext ctx = TenantContext.require();
        externalDirectoryService.deleteBeneficiary(ctx.tenantId(), ctx.userId(), id);
        return ResponseEntity.noContent().build();
    }

    // --- Relationships / resolve / org ---

    @GetMapping("/relationships")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','ANALYST','AUDITOR')")
    public RelationshipView queryRelationship(
            @RequestParam UUID fromEmployeeId,
            @RequestParam(required = false) UUID toEmployeeId,
            @RequestParam(required = false) UUID toExternalId
    ) {
        return relationshipService.query(
                TenantContext.require().tenantId(), fromEmployeeId, toEmployeeId, toExternalId);
    }

    @PostMapping("/relationships")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<Map<String, Object>> createRelationship(@RequestBody Map<String, Object> body) {
        TenantContext ctx = TenantContext.require();
        UUID from = UUID.fromString(String.valueOf(body.get("fromEmployeeId")));
        UUID toEmp = body.get("toEmployeeId") == null || String.valueOf(body.get("toEmployeeId")).isBlank()
                ? null : UUID.fromString(String.valueOf(body.get("toEmployeeId")));
        UUID toExt = body.get("toExternalId") == null || String.valueOf(body.get("toExternalId")).isBlank()
                ? null : UUID.fromString(String.valueOf(body.get("toExternalId")));
        @SuppressWarnings("unchecked")
        List<String> topics = body.get("typicalTopics") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : List.of();
        return ResponseEntity.status(HttpStatus.CREATED).body(
                relationshipService.create(ctx.tenantId(), ctx.userId(), from, toEmp, toExt,
                        body.get("relationshipType") == null ? null : String.valueOf(body.get("relationshipType")),
                        topics)
        );
    }

    @PostMapping("/resolve")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','ANALYST','AUDITOR')")
    public DirectoryMatch resolve(@RequestBody Map<String, Object> body) {
        TenantContext ctx = TenantContext.require();
        return directoryService.resolve(
                ctx.tenantId(),
                body.get("callerNumber") == null ? null : String.valueOf(body.get("callerNumber")),
                body.get("claimedName") == null ? null : String.valueOf(body.get("claimedName")),
                body.get("claimedRole") == null ? null : String.valueOf(body.get("claimedRole"))
        );
    }

    @GetMapping("/org-chart")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','ANALYST','AUDITOR')")
    public Map<String, Object> orgChart() {
        return directoryService.orgChart(TenantContext.require().tenantId());
    }

    // --- Imports ---

    @GetMapping("/imports/template")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<byte[]> template(@RequestParam String kind) {
        byte[] csv = importService.templateCsv(kind);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + kind.toLowerCase() + "_template.csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv);
    }

    @PostMapping(value = "/imports/dry-run", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public Map<String, Object> dryRun(
            @RequestParam String kind,
            @RequestParam("file") MultipartFile file
    ) {
        TenantContext ctx = TenantContext.require();
        return importService.dryRun(ctx.tenantId(), ctx.userId(), kind, file);
    }

    @PostMapping("/imports/{id}/commit")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public Map<String, Object> commit(@PathVariable UUID id) {
        TenantContext ctx = TenantContext.require();
        return importService.commit(ctx.tenantId(), ctx.userId(), id);
    }

    @GetMapping("/imports")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','ANALYST','AUDITOR')")
    public Map<String, Object> importHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return pageResponse(importService.history(TenantContext.require().tenantId(), page, size));
    }

    private static Map<String, Object> pageResponse(Page<?> page) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", page.getContent());
        m.put("page", page.getNumber());
        m.put("size", page.getSize());
        m.put("totalElements", page.getTotalElements());
        m.put("totalPages", page.getTotalPages());
        return m;
    }
}
