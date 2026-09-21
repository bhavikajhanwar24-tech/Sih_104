package com.sentinelvoice.response;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelvoice.policy.dsl.Condition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable parsed view of a response plan JSONB document.
 */
public record ResponsePlanDocument(Map<String, LevelPlan> levels) {

    public static final List<String> LEVEL_KEYS = List.of("L1", "L2", "L3", "L4");

    public record LevelPlan(
            boolean operatorOverridePermitted,
            boolean overrideRequiresSupervisor,
            List<PlanStep> steps
    ) {
    }

    public record PlanStep(
            String action,
            Map<String, Object> params,
            String trigger,
            long delayMs,
            boolean requiresAck,
            boolean autoExecute,
            boolean operatorConfirm,
            Map<String, Object> condition
    ) {
        public Condition parsedCondition() {
            if (condition == null || condition.isEmpty()) {
                return null;
            }
            return Condition.fromMap(condition);
        }
    }

    public LevelPlan level(String key) {
        return levels.get(key);
    }

    public Map<String, Object> toCanonicalMap() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> levelsOut = new LinkedHashMap<>();
        for (String key : LEVEL_KEYS) {
            LevelPlan lp = levels.get(key);
            if (lp == null) {
                continue;
            }
            Map<String, Object> levelMap = new LinkedHashMap<>();
            levelMap.put("operatorOverridePermitted", lp.operatorOverridePermitted());
            levelMap.put("overrideRequiresSupervisor", lp.overrideRequiresSupervisor());
            List<Map<String, Object>> steps = new ArrayList<>();
            for (PlanStep s : lp.steps()) {
                Map<String, Object> step = new LinkedHashMap<>();
                step.put("action", s.action());
                step.put("params", s.params() == null ? Map.of() : s.params());
                step.put("trigger", s.trigger());
                step.put("delayMs", s.delayMs());
                step.put("requiresAck", s.requiresAck());
                step.put("autoExecute", s.autoExecute());
                step.put("operatorConfirm", s.operatorConfirm());
                if (s.condition() != null && !s.condition().isEmpty()) {
                    step.put("condition", s.condition());
                }
                steps.add(step);
            }
            levelMap.put("steps", steps);
            levelsOut.put(key, levelMap);
        }
        root.put("levels", levelsOut);
        return root;
    }

    public static ResponsePlanDocument parse(Map<String, Object> raw) {
        return parse(raw, new ObjectMapper());
    }

    @SuppressWarnings("unchecked")
    public static ResponsePlanDocument parse(Map<String, Object> raw, ObjectMapper mapper) {
        Objects.requireNonNull(raw, "plan");
        Object levelsNode = raw.get("levels");
        if (!(levelsNode instanceof Map<?, ?> levelsMap)) {
            throw new IllegalArgumentException("plan.levels is required");
        }
        Map<String, LevelPlan> levels = new LinkedHashMap<>();
        for (String key : LEVEL_KEYS) {
            Object node = levelsMap.get(key);
            if (!(node instanceof Map<?, ?> lm)) {
                continue;
            }
            Map<String, Object> levelMap = (Map<String, Object>) lm;
            boolean overrideOk = bool(levelMap.get("operatorOverridePermitted"), true);
            boolean overrideSup = bool(levelMap.get("overrideRequiresSupervisor"), false);
            List<PlanStep> steps = new ArrayList<>();
            Object stepsNode = levelMap.get("steps");
            if (stepsNode instanceof List<?> stepList) {
                for (Object item : stepList) {
                    if (!(item instanceof Map<?, ?> sm)) {
                        continue;
                    }
                    Map<String, Object> stepMap = (Map<String, Object>) sm;
                    String action = String.valueOf(stepMap.getOrDefault("action", "")).trim().toUpperCase(Locale.ROOT);
                    Map<String, Object> params = stepMap.get("params") instanceof Map<?, ?> pm
                            ? new LinkedHashMap<>((Map<String, Object>) pm)
                            : new LinkedHashMap<>();
                    String trigger = String.valueOf(stepMap.getOrDefault("trigger", "ON_ENTER"))
                            .trim().toUpperCase(Locale.ROOT);
                    long delayMs = longVal(stepMap.get("delayMs"), 0L);
                    boolean requiresAck = bool(stepMap.get("requiresAck"), false);
                    boolean autoExecute = bool(stepMap.get("autoExecute"), true);
                    boolean operatorConfirm = bool(stepMap.get("operatorConfirm"), false);
                    if (operatorConfirm) {
                        autoExecute = false;
                    }
                    Map<String, Object> condition = stepMap.get("condition") instanceof Map<?, ?> cm
                            ? new LinkedHashMap<>((Map<String, Object>) cm)
                            : null;
                    steps.add(new PlanStep(
                            action, params, trigger, delayMs, requiresAck, autoExecute, operatorConfirm, condition
                    ));
                }
            }
            levels.put(key, new LevelPlan(overrideOk, overrideSup, List.copyOf(steps)));
        }
        return new ResponsePlanDocument(Map.copyOf(levels));
    }

    /** Built-in fail-safe when no ACTIVE plan can be loaded. */
    public static ResponsePlanDocument emergencyPlan() {
        Map<String, Object> advisory = Map.of(
                "text", "Response plan unavailable — operating on emergency advisory.",
                "verificationSteps", List.of("Notify supervisor", "Do not approve transfers until plan is restored")
        );
        List<PlanStep> advisorySteps = List.of(
                new PlanStep("OPERATOR_ADVISORY", new LinkedHashMap<>(advisory),
                        "ON_ENTER", 0, false, true, false, null),
                new PlanStep("NOTIFY_SUPERVISOR", Map.of("channels", List.of("in_app")),
                        "ON_ENTER", 0, false, true, false, null)
        );
        Map<String, LevelPlan> levels = new LinkedHashMap<>();
        for (String key : LEVEL_KEYS) {
            boolean critical = key.equals("L3") || key.equals("L4");
            levels.put(key, new LevelPlan(!critical, critical, advisorySteps));
        }
        // Strengthen L3/L4 floors for emergency
        levels.put("L3", new LevelPlan(true, true, List.of(
                new PlanStep("OPERATOR_ADVISORY", new LinkedHashMap<>(advisory),
                        "ON_ENTER", 0, false, true, false, null),
                new PlanStep("LOCK_APPROVAL", Map.of(), "ON_ENTER", 0, false, true, false, null),
                new PlanStep("NOTIFY_SUPERVISOR", Map.of("channels", List.of("in_app")),
                        "ON_ENTER", 0, false, true, false, null)
        )));
        levels.put("L4", new LevelPlan(false, true, List.of(
                new PlanStep("OPERATOR_ADVISORY", new LinkedHashMap<>(advisory),
                        "ON_ENTER", 0, false, true, false, null),
                new PlanStep("NOTIFY_SUPERVISOR", Map.of("channels", List.of("in_app")),
                        "ON_ENTER", 0, false, true, false, null),
                new PlanStep("HOLD_CALL", Map.of(), "ON_ENTER", 0, false, true, false, null)
        )));
        return new ResponsePlanDocument(Map.copyOf(levels));
    }

    public static String levelKeyFor(com.sentinelvoice.model.InterventionLevel level) {
        if (level == null) {
            return "L1";
        }
        return switch (level) {
            case LEVEL_1_SILENT -> "L1";
            case LEVEL_2_SOFT_NUDGE -> "L2";
            case LEVEL_3_STEP_UP_MFA -> "L3";
            case LEVEL_4_AUTO_HOLD, LEVEL_5_TERMINATE -> "L4";
        };
    }

    private static boolean bool(Object o, boolean def) {
        if (o instanceof Boolean b) {
            return b;
        }
        if (o == null) {
            return def;
        }
        return Boolean.parseBoolean(String.valueOf(o));
    }

    private static long longVal(Object o, long def) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        if (o == null) {
            return def;
        }
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
