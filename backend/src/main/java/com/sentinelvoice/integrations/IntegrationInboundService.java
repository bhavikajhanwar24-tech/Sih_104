package com.sentinelvoice.integrations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.audit.AuditLedgerService;
import com.sentinelvoice.directory.DirectoryService;
import com.sentinelvoice.directory.entity.EmployeeEntity;
import com.sentinelvoice.directory.repo.EmployeeRepository;
import com.sentinelvoice.policy.engine.CrossChannelFactService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Inbound integration helpers: cross-channel events + directory bulk sync.
 */
@Service
public class IntegrationInboundService {

    private final CrossChannelFactService crossChannelFactService;
    private final DirectoryService directoryService;
    private final EmployeeRepository employeeRepository;
    private final AuditLedgerService auditLedgerService;
    private final ObjectMapper objectMapper;

    public IntegrationInboundService(
            CrossChannelFactService crossChannelFactService,
            DirectoryService directoryService,
            EmployeeRepository employeeRepository,
            AuditLedgerService auditLedgerService,
            ObjectMapper objectMapper
    ) {
        this.crossChannelFactService = crossChannelFactService;
        this.directoryService = directoryService;
        this.employeeRepository = employeeRepository;
        this.auditLedgerService = auditLedgerService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> ingestCrossChannel(
            UUID tenantId,
            UUID apiKeyId,
            String type,
            String identityRef,
            Instant occurredAt,
            Map<String, Object> attributes
    ) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type required");
        }
        String channel = type.trim().toUpperCase(Locale.ROOT);
        String severity = "MEDIUM";
        String indicator = channel.toLowerCase(Locale.ROOT);
        String description = null;
        if (attributes != null) {
            if (attributes.get("severity") != null) {
                severity = String.valueOf(attributes.get("severity")).toUpperCase(Locale.ROOT);
            }
            if (attributes.get("indicator") != null) {
                indicator = String.valueOf(attributes.get("indicator"));
            }
            if (attributes.get("description") != null) {
                description = String.valueOf(attributes.get("description"));
            } else {
                try {
                    description = objectMapper.writeValueAsString(attributes);
                } catch (Exception ignored) {
                    description = attributes.toString();
                }
            }
        }
        UUID id = crossChannelFactService.insert(
                tenantId,
                channel,
                identityRef,
                occurredAt == null ? Instant.now() : occurredAt,
                severity,
                indicator,
                attributes == null || attributes.get("campaignId") == null
                        ? null
                        : String.valueOf(attributes.get("campaignId")),
                description
        );
        auditLedgerService.append(
                tenantId,
                null,
                AuditEventType.CONFIG_CHANGE,
                apiKeyId == null ? "SYSTEM" : "API_KEY",
                apiKeyId == null ? null : apiKeyId.toString(),
                Map.of("area", "cross_channel_event", "eventId", id.toString(), "type", channel)
        );
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id.toString());
        out.put("type", channel);
        out.put("identityRef", identityRef);
        out.put("status", "stored");
        return out;
    }

    @Transactional
    public Map<String, Object> directorySync(
            UUID tenantId,
            UUID actorId,
            UUID apiKeyId,
            List<Map<String, Object>> employees,
            boolean dryRun
    ) {
        if (employees == null) {
            employees = List.of();
        }
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        int errors = 0;
        List<Map<String, Object>> details = new ArrayList<>();

        for (Map<String, Object> row : employees) {
            String code = str(row.get("employeeCode"));
            if (code == null) {
                code = str(row.get("code"));
            }
            String fullName = str(row.get("fullName"));
            if (fullName == null) {
                fullName = str(row.get("name"));
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("employeeCode", code);
            if (code == null || fullName == null) {
                detail.put("status", "error");
                detail.put("error", "employeeCode and fullName required");
                errors++;
                details.add(detail);
                continue;
            }
            Optional<EmployeeEntity> existing =
                    employeeRepository.findByTenantIdAndEmployeeCodeIgnoreCase(tenantId, code);
            if (existing.isEmpty()) {
                detail.put("status", dryRun ? "would_create" : "created");
                if (!dryRun) {
                    Map<String, Object> body = new LinkedHashMap<>(row);
                    body.put("employeeCode", code);
                    body.put("fullName", fullName);
                    directoryService.createEmployee(tenantId, actorId, body);
                    if (row.get("phones") instanceof List<?> phones) {
                        // phones applied after create via optional fields in body if supported
                    }
                    if (row.get("status") != null) {
                        // status may be in applyEmployeeFields
                    }
                }
                created++;
            } else {
                detail.put("status", dryRun ? "would_update" : "updated");
                detail.put("id", existing.get().getId().toString());
                if (!dryRun) {
                    Map<String, Object> body = new LinkedHashMap<>(row);
                    body.put("fullName", fullName);
                    directoryService.updateEmployee(tenantId, actorId, existing.get().getId(), body);
                    if (row.get("status") != null) {
                        directoryService.setStatus(
                                tenantId,
                                actorId,
                                existing.get().getId(),
                                String.valueOf(row.get("status")),
                                null,
                                str(row.get("statusNote"))
                        );
                    }
                }
                updated++;
            }
            details.add(detail);
        }

        if (!dryRun) {
            auditLedgerService.append(
                    tenantId,
                    null,
                    AuditEventType.DIRECTORY_IMPORTED,
                    apiKeyId == null ? "USER" : "API_KEY",
                    apiKeyId != null ? apiKeyId.toString() : (actorId == null ? null : actorId.toString()),
                    Map.of(
                            "area", "directory_sync",
                            "created", created,
                            "updated", updated,
                            "count", employees.size()
                    )
            );
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dryRun", dryRun);
        out.put("created", created);
        out.put("updated", updated);
        out.put("unchanged", unchanged);
        out.put("errors", errors);
        out.put("total", employees.size());
        out.put("items", details);
        return out;
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }
}
