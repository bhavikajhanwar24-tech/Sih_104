package com.sentinelvoice.policy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Domain error for policy document APIs. May carry structured details (e.g. blocking rules).
 */
public class PolicyDocumentException extends RuntimeException {

    private final String code;
    private final Map<String, Object> details;

    public PolicyDocumentException(String code, String message) {
        this(code, message, Map.of());
    }

    public PolicyDocumentException(String code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public String getCode() {
        return code;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    public static PolicyDocumentException cited(List<Map<String, Object>> blockingRules) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("blockingRules", blockingRules);
        return new PolicyDocumentException(
                "CITED_BY_RULES",
                "Document is cited by active or pending-approval rules and cannot be permanently deleted",
                details
        );
    }
}
