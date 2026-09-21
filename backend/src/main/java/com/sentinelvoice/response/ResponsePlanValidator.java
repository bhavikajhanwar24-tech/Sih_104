package com.sentinelvoice.response;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Server-side safety floors for response plans. UI mirrors these messages.
 */
public final class ResponsePlanValidator {

    private static final Set<ResponseActionKey> L3_FLOOR = EnumSet.of(
            ResponseActionKey.LOCK_APPROVAL,
            ResponseActionKey.REQUIRE_CALLBACK_VERIFICATION,
            ResponseActionKey.SEND_OOB_MFA
    );

    private static final Set<ResponseActionKey> L4_CALL_CONTROL = EnumSet.of(
            ResponseActionKey.HOLD_CALL,
            ResponseActionKey.TERMINATE_CALL,
            ResponseActionKey.BRIDGE_SUPERVISOR
    );

    private ResponsePlanValidator() {
    }

    public record ValidationResult(
            List<String> violations,
            Map<String, LevelStatus> levels
    ) {
        public boolean ok() {
            return violations.isEmpty();
        }
    }

    public record LevelStatus(boolean ok, List<String> reasons) {
    }

    public static ValidationResult validate(ResponsePlanDocument doc) {
        List<String> violations = new ArrayList<>();
        Map<String, LevelStatus> levels = new LinkedHashMap<>();

        for (String key : ResponsePlanDocument.LEVEL_KEYS) {
            ResponsePlanDocument.LevelPlan lp = doc.level(key);
            List<String> reasons = new ArrayList<>();
            if (lp == null || lp.steps() == null || lp.steps().isEmpty()) {
                reasons.add(key + " must not be empty");
            } else {
                validateSteps(key, lp.steps(), reasons);
                if ("L3".equals(key)) {
                    validateL3(lp.steps(), reasons);
                }
                if ("L4".equals(key)) {
                    validateL4(lp.steps(), reasons);
                }
            }
            boolean ok = reasons.isEmpty();
            levels.put(key, new LevelStatus(ok, List.copyOf(reasons)));
            violations.addAll(reasons);
        }
        return new ValidationResult(List.copyOf(violations), Map.copyOf(levels));
    }

    public static List<String> validateViolations(ResponsePlanDocument doc) {
        return validate(doc).violations();
    }

    private static void validateSteps(String level, List<ResponsePlanDocument.PlanStep> steps, List<String> reasons) {
        Map<String, ActionCatalogue.ActionDef> catalogue = ActionCatalogue.byKey();
        boolean onlyLogOnly = true;
        for (int i = 0; i < steps.size(); i++) {
            ResponsePlanDocument.PlanStep step = steps.get(i);
            if (step.action() == null || step.action().isBlank()) {
                reasons.add(level + " step " + i + ": action is required");
                onlyLogOnly = false;
                continue;
            }
            if (!catalogue.containsKey(step.action())) {
                reasons.add(level + " step " + i + ": unknown action " + step.action());
                onlyLogOnly = false;
                continue;
            }
            if (!ResponseActionKey.LOG_ONLY.name().equals(step.action())) {
                onlyLogOnly = false;
            }
            String trigger = step.trigger() == null ? "" : step.trigger();
            if (!Set.of("ON_ENTER", "WHILE_ACTIVE_EVERY_N_SEC", "ON_EXIT").contains(trigger)) {
                reasons.add(level + " step " + i + ": invalid trigger " + trigger);
            }
            if (step.delayMs() < 0) {
                reasons.add(level + " step " + i + ": delayMs must be >= 0");
            }
            if (step.condition() != null && !step.condition().isEmpty()) {
                try {
                    step.parsedCondition();
                } catch (Exception e) {
                    reasons.add(level + " step " + i + ": invalid condition — " + e.getMessage());
                }
            }
        }
        if (("L3".equals(level) || "L4".equals(level)) && onlyLogOnly && !steps.isEmpty()) {
            reasons.add(level + ": LOG_ONLY alone is invalid");
        }
    }

    private static void validateL3(List<ResponsePlanDocument.PlanStep> steps, List<String> reasons) {
        boolean hasFloor = steps.stream().anyMatch(s -> {
            try {
                return L3_FLOOR.contains(ResponseActionKey.parse(s.action()));
            } catch (Exception e) {
                return false;
            }
        });
        if (!hasFloor) {
            reasons.add("L3 must contain at least one of: LOCK_APPROVAL, REQUIRE_CALLBACK_VERIFICATION, SEND_OOB_MFA");
        }
    }

    private static void validateL4(List<ResponsePlanDocument.PlanStep> steps, List<String> reasons) {
        boolean hasCall = steps.stream().anyMatch(s -> {
            try {
                return L4_CALL_CONTROL.contains(ResponseActionKey.parse(s.action()));
            } catch (Exception e) {
                return false;
            }
        });
        boolean hasNotify = steps.stream().anyMatch(s ->
                ResponseActionKey.NOTIFY_SUPERVISOR.name().equals(s.action()));
        if (!hasCall) {
            reasons.add("L4 must contain at least one of: HOLD_CALL, TERMINATE_CALL, BRIDGE_SUPERVISOR");
        }
        if (!hasNotify) {
            reasons.add("L4 must contain NOTIFY_SUPERVISOR");
        }
    }

    /** True when L3 or L4 steps differ from the baseline (requires POLICY_APPROVER). */
    public static boolean touchesL3OrL4(ResponsePlanDocument a, ResponsePlanDocument b) {
        if (a == null || b == null) {
            return true;
        }
        return !java.util.Objects.equals(a.level("L3"), b.level("L3"))
                || !java.util.Objects.equals(a.level("L4"), b.level("L4"));
    }
}
