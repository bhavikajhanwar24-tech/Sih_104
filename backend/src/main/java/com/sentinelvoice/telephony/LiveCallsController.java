package com.sentinelvoice.telephony;

import com.sentinelvoice.model.CallSession;
import com.sentinelvoice.security.TenantContext;
import com.sentinelvoice.service.CallSessionManager;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * F10 Live Calls — tenant-scoped {@code call_sessions} with directory names.
 */
@RestController
@RequestMapping("/api/v2/calls")
public class LiveCallsController {

    private final CallSessionRepository callSessionRepository;
    private final CallSessionManager callSessionManager;

    public LiveCallsController(
            CallSessionRepository callSessionRepository,
            CallSessionManager callSessionManager
    ) {
        this.callSessionRepository = callSessionRepository;
        this.callSessionManager = callSessionManager;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','ANALYST')")
    public Map<String, Object> list(
            @RequestParam(defaultValue = "40") int limit,
            @RequestParam(defaultValue = "false") boolean activeOnly
    ) {
        UUID tenantId = TenantContext.require().tenantId();
        List<TelephonyModels.CallSessionListItem> rows =
                callSessionRepository.listRecent(tenantId, limit, activeOnly);

        List<Map<String, Object>> items = rows.stream().map(row -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("schemaVersion", "2");
            m.put("id", row.id().toString());
            m.put("tenantId", row.tenantId().toString());
            m.put("active", row.active());
            m.put("startedAt", row.startedAt() == null ? null : row.startedAt().toString());
            m.put("endedAt", row.endedAt() == null ? null : row.endedAt().toString());
            m.put("direction", row.direction());
            m.put("callerNumber", row.callerNumber());
            m.put("calleeNumber", row.calleeNumber());
            m.put("callerEmployeeId", row.callerEmployeeId() == null ? null : row.callerEmployeeId().toString());
            m.put("calleeEmployeeId", row.calleeEmployeeId() == null ? null : row.calleeEmployeeId().toString());
            m.put("callerName", displayName(row.callerName(), row.callerTitle(), row.callerNumber()));
            m.put("calleeName", displayName(row.calleeName(), row.calleeTitle(), row.calleeNumber()));
            m.put("peakScore", row.peakScore());
            m.put("peakLevel", row.peakLevel());
            m.put("finalOutcome", row.finalOutcome());
            m.put("sipCallId", row.sipCallId());
            m.put("svSessionUuid", row.svSessionUuid() == null ? null : row.svSessionUuid().toString());

            if (row.active() && row.svSessionUuid() != null) {
                Optional<CallSession> mem = callSessionManager.getSession(row.svSessionUuid().toString());
                mem.ifPresent(s -> {
                    m.put("liveLevel", s.getCurrentLevel() == null ? null : s.getCurrentLevel().name());
                    m.put("liveScore", s.getSmoothedRisk());
                });
            }
            return m;
        }).toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", "2");
        body.put("tenantId", tenantId.toString());
        body.put("activeCount", callSessionRepository.countActive(tenantId));
        body.put("items", items);
        return body;
    }

    private static String displayName(String fullName, String title, String fallbackNumber) {
        if (fullName != null && !fullName.isBlank()) {
            if (title != null && !title.isBlank()) {
                return fullName + " (" + title + ")";
            }
            return fullName;
        }
        return fallbackNumber == null || fallbackNumber.isBlank() ? "Unknown" : fallbackNumber;
    }
}
