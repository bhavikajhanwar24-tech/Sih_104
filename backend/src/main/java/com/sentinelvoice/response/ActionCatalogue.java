package com.sentinelvoice.response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Code-defined action catalogue exposed via GET /api/v2/response/action-catalogue.
 */
public final class ActionCatalogue {

    private ActionCatalogue() {
    }

    public record ActionDef(
            String key,
            String label,
            String description,
            Map<String, Object> paramsSchema,
            boolean reversible,
            boolean needsIntegration,
            String capability,
            String integrationKind
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", key);
            m.put("label", label);
            m.put("description", description);
            m.put("paramsSchema", paramsSchema);
            m.put("reversible", reversible);
            m.put("needsIntegration", needsIntegration);
            m.put("capability", capability);
            m.put("integrationKind", integrationKind);
            return m;
        }
    }

    public static List<ActionDef> all() {
        List<ActionDef> list = new ArrayList<>();
        list.add(def(ResponseActionKey.LOG_ONLY, "Log only",
                "Record the level entry in audit; no operator or call change.",
                Map.of("type", "object", "properties", Map.of()),
                true, false, null, null));
        list.add(def(ResponseActionKey.OPERATOR_ADVISORY, "Operator advisory",
                "Show a banner with configurable text and recommended verification steps.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "text", Map.of("type", "string"),
                                "verificationSteps", Map.of("type", "array", "items", Map.of("type", "string"))
                        ),
                        "required", List.of("text")
                ),
                true, false, null, null));
        list.add(def(ResponseActionKey.WHISPER_WARNING, "Whisper warning",
                "Play an audio prompt to the agent leg only (Asterisk).",
                Map.of(
                        "type", "object",
                        "properties", Map.of("soundId", Map.of("type", "string")),
                        "required", List.of("soundId")
                ),
                true, false, "WHISPER", null));
        list.add(def(ResponseActionKey.REQUIRE_CALLBACK_VERIFICATION, "Callback verification",
                "Show the operator a hang-up-and-call-back checklist using the directory number.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "checklist", Map.of("type", "array", "items", Map.of("type", "string"))
                        )
                ),
                true, false, null, null));
        list.add(def(ResponseActionKey.REQUIRE_LIVENESS_CHALLENGE, "Liveness challenge",
                "Start the challenge-response (liveness) flow for the caller.",
                Map.of("type", "object", "properties", Map.of()),
                false, false, null, null));
        list.add(def(ResponseActionKey.LOCK_APPROVAL, "Lock approval",
                "Lock approve/transfer controls in the transaction panel (or via core-banking webhook).",
                Map.of("type", "object", "properties", Map.of()),
                true, false, null, "CORE_BANKING"));
        list.add(def(ResponseActionKey.SEND_OOB_MFA, "Send out-of-band MFA",
                "Send an OOB code to the employee’s registered contact via the SMS/notification provider.",
                Map.of("type", "object", "properties", Map.of()),
                false, true, null, "SMS_NOTIFICATION"));
        list.add(def(ResponseActionKey.NOTIFY_SUPERVISOR, "Notify supervisor",
                "Notify a supervisor in-app and/or via email/SMS/Slack webhook.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "channels", Map.of("type", "array", "items", Map.of("type", "string"))
                        )
                ),
                true, true, null, "SUPERVISOR_NOTIFY"));
        list.add(def(ResponseActionKey.BRIDGE_SUPERVISOR, "Bridge supervisor",
                "Join a supervisor extension into the call (muted/whisper or full).",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "mode", Map.of("type", "string", "enum", List.of("whisper", "muted", "full"))
                        )
                ),
                true, true, "BRIDGE_SUPERVISOR", "SUPERVISOR_BRIDGE"));
        list.add(def(ResponseActionKey.HOLD_CALL, "Hold call",
                "Mute both legs / play hold audio.",
                Map.of("type", "object", "properties", Map.of()),
                true, false, "HOLD", null));
        list.add(def(ResponseActionKey.TERMINATE_CALL, "Terminate call",
                "End the call immediately.",
                Map.of(
                        "type", "object",
                        "properties", Map.of("reason", Map.of("type", "string"))
                ),
                false, false, "TERMINATE", null));
        list.add(def(ResponseActionKey.FREEZE_BENEFICIARY, "Freeze beneficiary",
                "Webhook to core banking to freeze the beneficiary.",
                Map.of("type", "object", "properties", Map.of()),
                false, true, null, "CORE_BANKING"));
        list.add(def(ResponseActionKey.CREATE_INCIDENT, "Create incident",
                "Open a ticket in the tenant’s ITSM via webhook.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "title", Map.of("type", "string"),
                                "severity", Map.of("type", "string")
                        )
                ),
                false, true, null, "ITSM"));
        list.add(def(ResponseActionKey.CUSTOM_WEBHOOK, "Custom webhook",
                "Signed HMAC webhook with a templated payload.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "urlKey", Map.of("type", "string"),
                                "template", Map.of("type", "object")
                        )
                ),
                false, true, null, "CUSTOM_WEBHOOK"));
        return List.copyOf(list);
    }

    public static Map<String, ActionDef> byKey() {
        Map<String, ActionDef> map = new LinkedHashMap<>();
        for (ActionDef d : all()) {
            map.put(d.key(), d);
        }
        return Map.copyOf(map);
    }

    public static List<Map<String, Object>> toMaps() {
        return all().stream().map(ActionDef::toMap).toList();
    }

    private static ActionDef def(
            ResponseActionKey key,
            String label,
            String description,
            Map<String, Object> schema,
            boolean reversible,
            boolean needsIntegration,
            String capability,
            String integrationKind
    ) {
        return new ActionDef(
                key.name(), label, description, schema,
                reversible, needsIntegration, capability, integrationKind
        );
    }
}
