package com.sentinelvoice.response.execute;

import java.util.Map;

/**
 * Executes one catalogue action. Never throws into the fusion pipeline.
 */
@FunctionalInterface
public interface ActionExecutor {

    ActionOutcome execute(ActionContext ctx);

    record ActionContext(
            java.util.UUID tenantId,
            String sessionId,
            String levelKey,
            int stepIndex,
            Map<String, Object> params,
            Map<String, Object> factsSnapshot
    ) {
    }

    record ActionOutcome(
            Status status,
            String detail,
            boolean degraded,
            String degradedTo,
            Map<String, Object> result
    ) {
        public enum Status {
            SUCCESS, FAILED, SKIPPED, AWAITING_OPERATOR, UNSUPPORTED
        }

        public static ActionOutcome success(String detail) {
            return new ActionOutcome(Status.SUCCESS, detail, false, null, Map.of("detail", detail == null ? "" : detail));
        }

        public static ActionOutcome success(String detail, Map<String, Object> result) {
            return new ActionOutcome(Status.SUCCESS, detail, false, null, result == null ? Map.of() : result);
        }

        public static ActionOutcome failed(String detail) {
            return new ActionOutcome(Status.FAILED, detail, false, null, Map.of("detail", detail == null ? "" : detail));
        }

        public static ActionOutcome skipped(String detail) {
            return new ActionOutcome(Status.SKIPPED, detail, false, null, Map.of("detail", detail == null ? "" : detail));
        }

        public static ActionOutcome awaiting(String detail) {
            return new ActionOutcome(Status.AWAITING_OPERATOR, detail, false, null,
                    Map.of("detail", detail == null ? "" : detail));
        }

        public static ActionOutcome degraded(String detail, String fallbackAction) {
            return new ActionOutcome(Status.SUCCESS, detail, true, fallbackAction,
                    Map.of("detail", detail == null ? "" : detail, "degradedTo", fallbackAction));
        }

        public static ActionOutcome unsupported(String detail) {
            return new ActionOutcome(Status.UNSUPPORTED, detail, false, null,
                    Map.of("detail", detail == null ? "" : detail));
        }
    }
}
