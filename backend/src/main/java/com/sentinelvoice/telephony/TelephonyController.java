package com.sentinelvoice.telephony;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelvoice.security.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v2/telephony")
public class TelephonyController {

    private final SipEndpointRepository endpointRepository;
    private final TrunkRepository trunkRepository;
    private final CallSessionRepository callSessionRepository;
    private final SipProvisioningService provisioningService;
    private final TelephonyHealthService healthService;
    private final SipRegistrationService registrationService;
    private final ObjectMapper objectMapper;
    private final String sipExternalIp;
    private final boolean labMode;

    public TelephonyController(
            SipEndpointRepository endpointRepository,
            TrunkRepository trunkRepository,
            CallSessionRepository callSessionRepository,
            SipProvisioningService provisioningService,
            TelephonyHealthService healthService,
            SipRegistrationService registrationService,
            ObjectMapper objectMapper,
            @Value("${SIP_EXTERNAL_IP:}") String sipExternalIp,
            @Value("${LAB_MODE:false}") boolean labMode
    ) {
        this.endpointRepository = endpointRepository;
        this.trunkRepository = trunkRepository;
        this.callSessionRepository = callSessionRepository;
        this.provisioningService = provisioningService;
        this.healthService = healthService;
        this.registrationService = registrationService;
        this.objectMapper = objectMapper;
        this.sipExternalIp = sipExternalIp == null ? "" : sipExternalIp.trim();
        this.labMode = labMode;
    }

    @GetMapping("/endpoints")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','POLICY_APPROVER','ANALYST','AUDITOR')")
    public List<Map<String, Object>> listEndpoints(
            @RequestParam(value = "employeeId", required = false) UUID employeeId
    ) {
        UUID tenantId = TenantContext.require().tenantId();
        var onCall = callSessionRepository.activeEmployeeIds(tenantId);
        List<TelephonyModels.SipEndpointView> endpoints;
        if (employeeId != null) {
            endpoints = endpointRepository.findByEmployee(tenantId, employeeId).stream().toList();
        } else {
            endpoints = endpointRepository.list(tenantId);
        }
        var registered = registrationService.refreshAndProbe(tenantId, endpoints);
        return endpoints.stream().map(e -> endpointJson(e, onCall, registered)).toList();
    }

    @GetMapping("/softphone-defaults")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','POLICY_APPROVER','ANALYST','AUDITOR')")
    public Map<String, Object> softphoneDefaults() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("domain", sipExternalIp.isBlank() ? "127.0.0.1" : sipExternalIp);
        m.put("port", 5060);
        m.put("transport", "UDP");
        m.put("labMode", labMode);
        return m;
    }

    @PostMapping("/endpoints/for-employee/{employeeId}")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public Map<String, Object> createForEmployee(@PathVariable UUID employeeId) {
        UUID tenantId = TenantContext.require().tenantId();
        TelephonyModels.SipEndpointProvisionResult result =
                provisioningService.createForEmployee(tenantId, employeeId);
        return provisionJson(result);
    }

    @PostMapping("/endpoints/lab-attacker")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public Map<String, Object> createLabAttacker(@RequestBody(required = false) Map<String, Object> body) {
        UUID tenantId = TenantContext.require().tenantId();
        String label = body == null ? null : String.valueOf(body.getOrDefault("label", "attacker"));
        return provisionJson(provisioningService.createLabAttacker(tenantId, label));
    }

    @PostMapping("/endpoints/{id}/reset-password")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public Map<String, Object> resetPassword(@PathVariable UUID id) {
        UUID tenantId = TenantContext.require().tenantId();
        return provisionJson(provisioningService.resetPassword(tenantId, id));
    }

    @PutMapping("/endpoints/{id}/status")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public Map<String, Object> setStatus(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        UUID tenantId = TenantContext.require().tenantId();
        String status = body == null ? null : String.valueOf(body.get("status"));
        provisioningService.setStatus(tenantId, id, status);
        return endpointJson(endpointRepository.findById(tenantId, id).orElseThrow());
    }

    @DeleteMapping("/endpoints/{id}")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<Void> deleteEndpoint(@PathVariable UUID id) {
        UUID tenantId = TenantContext.require().tenantId();
        provisioningService.delete(tenantId, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/trunks")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','POLICY_APPROVER','ANALYST','AUDITOR')")
    public List<Map<String, Object>> listTrunks() {
        UUID tenantId = TenantContext.require().tenantId();
        return trunkRepository.list(tenantId).stream().map(this::trunkJson).toList();
    }

    @PostMapping("/trunks")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public Map<String, Object> createTrunk(@RequestBody Map<String, Object> body) {
        UUID tenantId = TenantContext.require().tenantId();
        String name = requiredString(body, "name");
        String type = requiredString(body, "type").toUpperCase();
        if (!List.of("INTERNAL", "CARRIER", "CCAAS").contains(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid trunk type");
        }
        @SuppressWarnings("unchecked")
        List<String> prefixes = body.get("cliPrefixes") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of();
        String metaJson = "{}";
        if (body.get("providerMetadata") != null) {
            try {
                metaJson = objectMapper.writeValueAsString(body.get("providerMetadata"));
            } catch (JsonProcessingException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid providerMetadata");
            }
        }
        return trunkJson(trunkRepository.insert(tenantId, name, type, prefixes, metaJson));
    }

    @GetMapping("/asterisk/health")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','POLICY_APPROVER','AUDITOR')")
    public Map<String, Object> asteriskHealth() {
        UUID tenantId = TenantContext.require().tenantId();
        return healthService.diagnose(
                endpointRepository.list(tenantId).size(),
                callSessionRepository.countActive(tenantId)
        );
    }

    private Map<String, Object> provisionJson(TelephonyModels.SipEndpointProvisionResult result) {
        Map<String, Object> m = endpointJson(result.endpoint(), java.util.Set.of(), java.util.Map.of());
        m.put("plaintextPassword", result.plaintextPassword());
        m.put("domain", sipExternalIp.isBlank() ? "127.0.0.1" : sipExternalIp);
        m.put("port", 5060);
        m.put("transport", "UDP");
        return m;
    }

    private Map<String, Object> endpointJson(
            TelephonyModels.SipEndpointView e,
            java.util.Set<UUID> onCall,
            java.util.Map<String, java.time.Instant> registered
    ) {
        java.time.Instant lastReg = registered.getOrDefault(e.username(), e.lastRegisteredAt());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id().toString());
        m.put("tenantId", e.tenantId().toString());
        m.put("employeeId", e.employeeId() == null ? null : e.employeeId().toString());
        m.put("extension", e.extension());
        m.put("username", e.username());
        m.put("status", e.status());
        m.put("registered", lastReg != null
                && lastReg.isAfter(java.time.Instant.now().minusSeconds(120)));
        m.put("onCall", e.employeeId() != null && onCall.contains(e.employeeId()));
        m.put("lastRegisteredAt", lastReg == null ? null : lastReg.toString());
        m.put("createdAt", e.createdAt() == null ? null : e.createdAt().toString());
        m.put("updatedAt", e.updatedAt() == null ? null : e.updatedAt().toString());
        return m;
    }

    private Map<String, Object> endpointJson(TelephonyModels.SipEndpointView e) {
        return endpointJson(e, java.util.Set.of(), java.util.Map.of());
    }

    private Map<String, Object> trunkJson(TelephonyModels.TrunkView t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id().toString());
        m.put("tenantId", t.tenantId().toString());
        m.put("name", t.name());
        m.put("type", t.type());
        m.put("cliPrefixes", t.cliPrefixes());
        m.put("providerMetadata", t.providerMetadata());
        m.put("createdAt", t.createdAt() == null ? null : t.createdAt().toString());
        m.put("updatedAt", t.updatedAt() == null ? null : t.updatedAt().toString());
        return m;
    }

    private static String requiredString(Map<String, Object> body, String key) {
        if (body == null || body.get(key) == null || String.valueOf(body.get(key)).isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " required");
        }
        return String.valueOf(body.get(key)).trim();
    }
}
