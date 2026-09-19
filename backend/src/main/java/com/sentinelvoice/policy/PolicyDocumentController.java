package com.sentinelvoice.policy;

import com.sentinelvoice.security.TenantContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Policy document APIs under {@code /api/v2/policies/documents}
 * (canonical F5 path; addendum text said {@code /policy/} singular — same resource).
 */
@RestController
@RequestMapping("/api/v2/policies/documents")
public class PolicyDocumentController {

    private final PolicyDocumentService policyDocumentService;

    public PolicyDocumentController(PolicyDocumentService policyDocumentService) {
        this.policyDocumentService = policyDocumentService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','POLICY_APPROVER','AUDITOR')")
    public Map<String, Object> list(
            @RequestParam(value = "includeArchived", defaultValue = "false") boolean includeArchived,
            @RequestParam(value = "archived", required = false) Boolean archivedOnly
    ) {
        TenantContext ctx = TenantContext.require();
        // Legacy ?archived=true → archived-only list
        if (Boolean.TRUE.equals(archivedOnly)) {
            return Map.of("items", policyDocumentService.listArchivedOnly(ctx.tenantId()));
        }
        return Map.of("items", policyDocumentService.list(ctx.tenantId(), includeArchived));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "docType", defaultValue = "POLICY") String docType
    ) {
        TenantContext ctx = TenantContext.require();
        Map<String, Object> created = policyDocumentService.upload(ctx.tenantId(), ctx.userId(), file, title, docType);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','POLICY_APPROVER','AUDITOR')")
    public Map<String, Object> get(@PathVariable UUID id) {
        TenantContext ctx = TenantContext.require();
        return policyDocumentService.get(ctx.tenantId(), id);
    }

    @GetMapping("/{id}/chunks")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','POLICY_APPROVER','AUDITOR')")
    public Map<String, Object> chunks(@PathVariable UUID id) {
        TenantContext ctx = TenantContext.require();
        List<Map<String, Object>> items = policyDocumentService.chunks(ctx.tenantId(), id);
        return Map.of("items", items);
    }

    @GetMapping("/{id}/download")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','AUDITOR')")
    public ResponseEntity<byte[]> download(@PathVariable UUID id) {
        TenantContext ctx = TenantContext.require();
        PolicyDocumentEntity doc = policyDocumentService.requireForDownload(ctx.tenantId(), id);
        policyDocumentService.auditDownload(ctx.tenantId(), ctx.userId(), id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + doc.getOriginalFilename() + "\"")
                .contentType(MediaType.parseMediaType(doc.getMimeType()))
                .body(doc.getContent());
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public Map<String, Object> restore(@PathVariable UUID id) {
        TenantContext ctx = TenantContext.require();
        return policyDocumentService.restore(ctx.tenantId(), ctx.userId(), id);
    }

    @PostMapping("/{id}/reextract")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public Map<String, Object> reextract(@PathVariable UUID id) {
        TenantContext ctx = TenantContext.require();
        return policyDocumentService.reextract(ctx.tenantId(), ctx.userId(), id);
    }

    /**
     * Preview blockers for permanent delete (does not delete).
     */
    @GetMapping("/{id}/delete-blockers")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public Map<String, Object> deleteBlockers(@PathVariable UUID id) {
        TenantContext ctx = TenantContext.require();
        return policyDocumentService.deleteBlockers(ctx.tenantId(), id);
    }

    /**
     * Soft-archive by default; {@code ?permanent=true} hard-deletes an ARCHIVED document.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public Map<String, Object> delete(
            @PathVariable UUID id,
            @RequestParam(value = "permanent", defaultValue = "false") boolean permanent
    ) {
        TenantContext ctx = TenantContext.require();
        return policyDocumentService.delete(ctx.tenantId(), ctx.userId(), id, permanent);
    }

    /** Preferred permanent-delete path (avoids DELETE+query-string issues via some proxies). */
    @PostMapping("/{id}/permanent-delete")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public Map<String, Object> permanentDeleteOne(@PathVariable UUID id) {
        TenantContext ctx = TenantContext.require();
        return policyDocumentService.permanentDelete(ctx.tenantId(), ctx.userId(), id);
    }

    public record BulkPermanentDeleteBody(List<UUID> ids) {
    }

    @PostMapping("/bulk-permanent-delete")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public Map<String, Object> permanentDeleteBulk(@RequestBody BulkPermanentDeleteBody body) {
        TenantContext ctx = TenantContext.require();
        List<UUID> ids = body == null || body.ids() == null ? List.of() : body.ids();
        return policyDocumentService.permanentDeleteBulk(ctx.tenantId(), ctx.userId(), new ArrayList<>(ids));
    }

    @ExceptionHandler(PolicyDocumentException.class)
    public ResponseEntity<Map<String, Object>> handle(PolicyDocumentException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", ex.getCode());
        body.put("message", ex.getMessage());
        body.putAll(ex.getDetails());
        HttpStatus status = switch (ex.getCode()) {
            case "NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "DUPLICATE", "NOT_ARCHIVED", "CITED_BY_RULES" -> HttpStatus.CONFLICT;
            case "MIME_MISMATCH" -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(body);
    }
}
