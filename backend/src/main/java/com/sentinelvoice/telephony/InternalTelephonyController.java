package com.sentinelvoice.telephony;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Internal telephony APIs for Asterisk AGI / ml-engine.
 * Protected by {@link com.sentinelvoice.security.ServiceTokenAuthFilter}
 * ({@code X-ML-Service-Token}).
 */
@RestController
@RequestMapping("/internal/v2")
public class InternalTelephonyController {

    private final TelephonyResolveService resolveService;
    private final CallLifecycleService lifecycleService;

    public InternalTelephonyController(
            TelephonyResolveService resolveService,
            CallLifecycleService lifecycleService
    ) {
        this.resolveService = resolveService;
        this.lifecycleService = lifecycleService;
    }

    @GetMapping("/telephony/resolve")
    public Map<String, Object> resolve(
            @RequestParam(required = false) String extension,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String callerUsername,
            @RequestParam(required = false) String number,
            @RequestParam(required = false) UUID tenantId
    ) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (extension != null && !extension.isBlank()) {
            TelephonyModels.ExtensionResolveResult r = resolveService
                    .resolveExtensionForCaller(extension, callerUsername)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "extension not found or cross-tenant dial denied"
                    ));
            out.put("tenantId", r.tenantId().toString());
            out.put("employeeId", r.employeeId() == null ? null : r.employeeId().toString());
            out.put("endpointId", r.endpointId().toString());
            out.put("username", r.username());
            out.put("extension", r.extension());
            out.put("status", r.status());
            out.put("destEndpoint", r.username());
            return out;
        }
        if (username != null && !username.isBlank()) {
            TelephonyModels.ExtensionResolveResult r = resolveService.resolveUsername(username)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "username not found"));
            out.put("tenantId", r.tenantId().toString());
            out.put("employeeId", r.employeeId() == null ? null : r.employeeId().toString());
            out.put("endpointId", r.endpointId().toString());
            out.put("username", r.username());
            out.put("extension", r.extension());
            out.put("status", r.status());
            out.put("destEndpoint", r.username());
            return out;
        }
        if (number != null && tenantId != null) {
            TelephonyModels.NumberClassifyResult cls = com.sentinelvoice.security.TenantContext.runAs(
                    tenantId,
                    () -> resolveService.classifyNumber(tenantId, number)
            );
            out.put("classification", cls.classification().name());
            out.put("tenantId", cls.tenantId().toString());
            out.put("employeeId", cls.employeeId() == null ? null : cls.employeeId().toString());
            out.put("endpointId", cls.endpointId() == null ? null : cls.endpointId().toString());
            out.put("trunkId", cls.trunkId() == null ? null : cls.trunkId().toString());
            out.put("matchedPrefix", cls.matchedPrefix());
            return out;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "extension, username, or number+tenantId required");
    }

    @PostMapping("/sessions/ringing")
    public Map<String, Object> ringing(@RequestBody Map<String, Object> body) {
        return lifecycleService.onRinging(new CallLifecycleService.StartRequest(
                null,
                str(body.get("callerNumber")),
                str(body.get("callerUsername")),
                firstNonBlank(str(body.get("calleeExtension")), str(body.get("extension"))),
                str(body.get("sipCallId")),
                str(body.get("direction")),
                str(body.get("pAssertedIdentity")),
                str(body.get("trunkHint"))
        ));
    }

    @PostMapping("/sessions/start")
    public Map<String, Object> startSession(@RequestBody Map<String, Object> body) {
        UUID svSession = parseUuid(body.get("svSessionUuid"));
        String callerNumber = str(body.get("callerNumber"));
        String callerUsername = str(body.get("callerUsername"));
        String calleeExtension = firstNonBlank(str(body.get("calleeExtension")), str(body.get("extension")));
        String sipCallId = str(body.get("sipCallId"));
        String direction = str(body.get("direction"));
        return lifecycleService.onAnswer(new CallLifecycleService.StartRequest(
                svSession,
                callerNumber,
                callerUsername,
                calleeExtension,
                sipCallId,
                direction,
                str(body.get("pAssertedIdentity")),
                str(body.get("trunkHint"))
        ));
    }

    @PostMapping("/sessions/end")
    public Map<String, Object> endSession(@RequestBody Map<String, Object> body) {
        UUID svSession = parseUuid(body.get("svSessionUuid"));
        if (svSession == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "svSessionUuid required");
        }
        return lifecycleService.onEnd(svSession, str(body.get("outcome")));
    }

    /** End every active call_sessions row + in-memory sessions (lab ghost-call cleanup). */
    @PostMapping("/sessions/clear-active")
    public Map<String, Object> clearActive(@RequestBody(required = false) Map<String, Object> body) {
        String outcome = body == null ? null : str(body.get("outcome"));
        return lifecycleService.clearAllActive(outcome);
    }

    private static UUID parseUuid(Object raw) {
        if (raw == null) {
            return null;
        }
        String s = String.valueOf(raw).trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid uuid");
        }
    }

    private static String str(Object raw) {
        if (raw == null) {
            return null;
        }
        String s = String.valueOf(raw).trim();
        return s.isEmpty() || "null".equalsIgnoreCase(s) ? null : s;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null ? a : b;
    }
}
