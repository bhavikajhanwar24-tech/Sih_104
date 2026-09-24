package com.sentinelvoice.lab;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * F18 — /api/v2/lab (ADMIN/ANALYST, LAB_MODE only). Simulated badge belongs only here.
 */
@RestController
@RequestMapping("/api/v2/lab")
public class LabController {

    private final LabSimulatorService labSimulatorService;

    public LabController(LabSimulatorService labSimulatorService) {
        this.labSimulatorService = labSimulatorService;
    }

    @GetMapping("/scenarios")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','ANALYST')")
    public Map<String, Object> list() {
        List<Map<String, Object>> items = labSimulatorService.listScenarios();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", "2");
        body.put("simulated", true);
        body.put("items", items);
        return body;
    }

    @PostMapping("/scenarios/{id}/run")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','ANALYST')")
    public Map<String, Object> run(@PathVariable String id) {
        return labSimulatorService.run(id);
    }

    @GetMapping("/runs/{sessionId}")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','ANALYST')")
    public Map<String, Object> result(@PathVariable String sessionId) {
        return labSimulatorService.result(sessionId);
    }

    @ExceptionHandler(LabException.class)
    public ResponseEntity<Map<String, Object>> handle(LabException ex) {
        HttpStatus status = "LAB_MODE_OFF".equals(ex.getCode()) ? HttpStatus.FORBIDDEN : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(Map.of("code", ex.getCode(), "message", ex.getMessage()));
    }
}
