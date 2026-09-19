package com.sentinelvoice.policy.dsl;

import java.util.List;
import java.util.Map;

/**
 * F6 policy rule DTO (wire + persistence body).
 */
public record PolicyRuleDsl(
        String ruleId,
        String title,
        String description,
        Map<String, Object> source,
        Map<String, Object> appliesTo,
        Map<String, Object> when,
        Map<String, Object> then,
        String severity,
        String status,
        String origin,
        List<Map<String, Object>> warnings
) {
}
