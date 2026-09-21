package com.sentinelvoice.response.execute;

import com.sentinelvoice.actuation.CallControlPort;
import com.sentinelvoice.actuation.CoreBankingWebhookService;
import com.sentinelvoice.actuation.OobMfaService;
import com.sentinelvoice.challenge.ChallengeService;
import com.sentinelvoice.config.SentinelProperties;
import com.sentinelvoice.response.ResponseActionKey;
import com.sentinelvoice.response.integration.TenantIntegrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registry of per-action executors. Telephony goes through {@link CallControlPort}.
 */
@Component
public class ActionExecutorRegistry {

    private static final Logger log = LoggerFactory.getLogger(ActionExecutorRegistry.class);

    private final Map<ResponseActionKey, ActionExecutor> executors = new EnumMap<>(ResponseActionKey.class);

    public ActionExecutorRegistry(
            CallControlPort callControl,
            OobMfaService oobMfaService,
            CoreBankingWebhookService coreBankingWebhookService,
            TenantIntegrationService integrations,
            ObjectProvider<ChallengeService> challengeService,
            ObjectProvider<SimpMessagingTemplate> messaging,
            SentinelProperties properties,
            Clock clock,
            RestTemplateBuilder restTemplateBuilder
    ) {
        RestTemplate rest = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(3))
                .setReadTimeout(Duration.ofSeconds(5))
                .build();
        String supervisorEndpoint = properties.actuation().supervisorEndpoint();

        register(ResponseActionKey.LOG_ONLY, ctx ->
                ActionExecutor.ActionOutcome.success("logged"));

        register(ResponseActionKey.OPERATOR_ADVISORY, ctx -> {
            pushUi(messaging, ctx, "OPERATOR_ADVISORY", ctx.params());
            return ActionExecutor.ActionOutcome.success("advisory published");
        });

        register(ResponseActionKey.WHISPER_WARNING, ctx -> {
            if (!callControl.capabilities().contains("WHISPER")) {
                return ActionExecutor.ActionOutcome.degraded("whisper unsupported", "OPERATOR_ADVISORY");
            }
            try {
                String sound = str(ctx.params().get("soundId"), "whisper-caution");
                callControl.whisperToAgent(ctx.sessionId(), sound);
                return ActionExecutor.ActionOutcome.success("whisper=" + sound);
            } catch (Exception e) {
                return ActionExecutor.ActionOutcome.failed(e.getMessage());
            }
        });

        register(ResponseActionKey.REQUIRE_CALLBACK_VERIFICATION, ctx -> {
            pushUi(messaging, ctx, "REQUIRE_CALLBACK_VERIFICATION", ctx.params());
            return ActionExecutor.ActionOutcome.awaiting("callback checklist shown");
        });

        register(ResponseActionKey.REQUIRE_LIVENESS_CHALLENGE, ctx -> {
            ChallengeService cs = challengeService.getIfAvailable();
            if (cs == null) {
                return ActionExecutor.ActionOutcome.degraded("challenge service unavailable", "OPERATOR_ADVISORY");
            }
            try {
                cs.issue(ctx.sessionId(), "en");
                return ActionExecutor.ActionOutcome.success("challenge issued");
            } catch (IllegalStateException ex) {
                if ("active_challenge_exists".equals(ex.getMessage())) {
                    return ActionExecutor.ActionOutcome.skipped("active challenge exists");
                }
                return ActionExecutor.ActionOutcome.failed(ex.getMessage());
            } catch (Exception e) {
                return ActionExecutor.ActionOutcome.failed(e.getMessage());
            }
        });

        register(ResponseActionKey.LOCK_APPROVAL, ctx -> {
            // Lock is enforced by TransactionLockService from session level; arm UI + audit trail.
            pushUi(messaging, ctx, "LOCK_APPROVAL", Map.of());
            return ActionExecutor.ActionOutcome.success("approval lock armed");
        });

        register(ResponseActionKey.SEND_OOB_MFA, ctx -> {
            boolean configured = integrations.isConfigured(ctx.tenantId(), "SMS_NOTIFICATION");
            try {
                oobMfaService.sendChallenge(ctx.sessionId());
                if (!configured) {
                    return ActionExecutor.ActionOutcome.degraded(
                            "SMS provider not configured — log-only MFA", "OPERATOR_ADVISORY");
                }
                return ActionExecutor.ActionOutcome.success("oob mfa sent");
            } catch (Exception e) {
                return ActionExecutor.ActionOutcome.failed(e.getMessage());
            }
        });

        register(ResponseActionKey.NOTIFY_SUPERVISOR, ctx -> {
            pushUi(messaging, ctx, "NOTIFY_SUPERVISOR", ctx.params());
            var secrets = integrations.loadSecrets(ctx.tenantId(), "SUPERVISOR_NOTIFY");
            if (secrets.isEmpty() || !secrets.get().enabled()) {
                return ActionExecutor.ActionOutcome.degraded(
                        "supervisor notify integration missing — in-app only", "OPERATOR_ADVISORY");
            }
            try {
                fireWebhook(rest, secrets.get(), Map.of(
                        "type", "SUPERVISOR_NOTIFY",
                        "sessionId", ctx.sessionId(),
                        "level", ctx.levelKey(),
                        "ts", clock.instant().toString()
                ));
                return ActionExecutor.ActionOutcome.success("supervisor notified");
            } catch (Exception e) {
                return ActionExecutor.ActionOutcome.degraded(e.getMessage(), "OPERATOR_ADVISORY");
            }
        });

        register(ResponseActionKey.BRIDGE_SUPERVISOR, ctx -> {
            if (!callControl.capabilities().contains("BRIDGE_SUPERVISOR")) {
                return ActionExecutor.ActionOutcome.degraded("bridge unsupported", "NOTIFY_SUPERVISOR");
            }
            String endpoint = supervisorEndpoint;
            var secrets = integrations.loadSecrets(ctx.tenantId(), "SUPERVISOR_BRIDGE");
            if (secrets.isPresent()) {
                Object ext = secrets.get().config().getOrDefault("extension",
                        secrets.get().secrets().get("extension"));
                if (ext != null && !String.valueOf(ext).isBlank()) {
                    endpoint = String.valueOf(ext);
                }
            }
            try {
                callControl.bridgeSupervisor(ctx.sessionId(), endpoint);
                return ActionExecutor.ActionOutcome.success("bridged=" + endpoint);
            } catch (Exception e) {
                return ActionExecutor.ActionOutcome.failed(e.getMessage());
            }
        });

        register(ResponseActionKey.HOLD_CALL, ctx -> {
            if (!callControl.capabilities().contains("HOLD")) {
                return ActionExecutor.ActionOutcome.degraded("hold unsupported", "OPERATOR_ADVISORY");
            }
            try {
                callControl.hold(ctx.sessionId());
                return ActionExecutor.ActionOutcome.success("held");
            } catch (Exception e) {
                return ActionExecutor.ActionOutcome.failed(e.getMessage());
            }
        });

        register(ResponseActionKey.TERMINATE_CALL, ctx -> {
            if (!callControl.capabilities().contains("TERMINATE")) {
                return ActionExecutor.ActionOutcome.degraded("terminate unsupported", "HOLD_CALL");
            }
            try {
                String reason = str(ctx.params().get("reason"), "response_plan");
                callControl.terminate(ctx.sessionId(), reason);
                return ActionExecutor.ActionOutcome.success("terminated");
            } catch (Exception e) {
                return ActionExecutor.ActionOutcome.failed(e.getMessage());
            }
        });

        register(ResponseActionKey.FREEZE_BENEFICIARY, ctx -> {
            if (!integrations.isConfigured(ctx.tenantId(), "CORE_BANKING")) {
                return ActionExecutor.ActionOutcome.degraded(
                        "core banking not configured", "OPERATOR_ADVISORY");
            }
            try {
                boolean ok = coreBankingWebhookService.freezeBeneficiary(
                        ctx.sessionId(), "plan-beneficiary", "RESPONSE_PLAN");
                return ok
                        ? ActionExecutor.ActionOutcome.success("beneficiary freeze requested")
                        : ActionExecutor.ActionOutcome.failed("freeze webhook failed");
            } catch (Exception e) {
                return ActionExecutor.ActionOutcome.failed(e.getMessage());
            }
        });

        register(ResponseActionKey.CREATE_INCIDENT, ctx -> {
            var secrets = integrations.loadSecrets(ctx.tenantId(), "ITSM");
            if (secrets.isEmpty() || !secrets.get().enabled()) {
                return ActionExecutor.ActionOutcome.degraded("ITSM not configured", "NOTIFY_SUPERVISOR");
            }
            try {
                fireWebhook(rest, secrets.get(), Map.of(
                        "type", "CREATE_INCIDENT",
                        "sessionId", ctx.sessionId(),
                        "level", ctx.levelKey(),
                        "title", str(ctx.params().get("title"), "SentinelVoice incident"),
                        "severity", str(ctx.params().get("severity"), ctx.levelKey()),
                        "ts", clock.instant().toString()
                ));
                return ActionExecutor.ActionOutcome.success("incident created");
            } catch (Exception e) {
                return ActionExecutor.ActionOutcome.failed(e.getMessage());
            }
        });

        register(ResponseActionKey.CUSTOM_WEBHOOK, ctx -> {
            var secrets = integrations.loadSecrets(ctx.tenantId(), "CUSTOM_WEBHOOK");
            if (secrets.isEmpty() || !secrets.get().enabled()) {
                return ActionExecutor.ActionOutcome.degraded("custom webhook not configured", "OPERATOR_ADVISORY");
            }
            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("type", "CUSTOM_WEBHOOK");
                payload.put("sessionId", ctx.sessionId());
                payload.put("level", ctx.levelKey());
                payload.put("params", ctx.params());
                payload.put("ts", clock.instant().toString());
                if (ctx.params().get("template") instanceof Map<?, ?> tmpl) {
                    payload.put("template", tmpl);
                }
                fireWebhook(rest, secrets.get(), payload);
                return ActionExecutor.ActionOutcome.success("webhook fired");
            } catch (Exception e) {
                return ActionExecutor.ActionOutcome.failed(e.getMessage());
            }
        });
    }

    private void register(ResponseActionKey key, ActionExecutor executor) {
        executors.put(key, executor);
    }

    public ActionExecutor get(String actionKey) {
        try {
            ResponseActionKey key = ResponseActionKey.parse(actionKey);
            return executors.get(key);
        } catch (Exception e) {
            return null;
        }
    }

    private static void pushUi(
            ObjectProvider<SimpMessagingTemplate> messaging,
            ActionExecutor.ActionContext ctx,
            String type,
            Map<String, Object> params
    ) {
        SimpMessagingTemplate t = messaging.getIfAvailable();
        if (t == null) {
            return;
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("type", type);
            body.put("sessionId", ctx.sessionId());
            body.put("level", ctx.levelKey());
            body.put("params", params == null ? Map.of() : params);
            t.convertAndSend("/topic/session." + ctx.sessionId() + ".actions", body);
        } catch (Exception e) {
            log.debug("ui_push_failed type={} err={}", type, e.toString());
        }
    }

    private static void fireWebhook(
            RestTemplate rest,
            TenantIntegrationService.IntegrationSecrets secrets,
            Map<String, Object> payload
    ) {
        String url = first(
                str(secrets.config().get("webhookUrl"), null),
                str(secrets.secrets().get("webhookUrl"), null)
        );
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("webhookUrl missing");
        }
        String hmacSecret = first(
                str(secrets.secrets().get("hmacSecret"), null),
                str(secrets.config().get("hmacSecret"), null)
        );
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (hmacSecret != null && !hmacSecret.isBlank()) {
            try {
                String body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload);
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(hmacSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                String sig = HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
                headers.set("X-SentinelVoice-Signature", sig);
                rest.postForEntity(url, new HttpEntity<>(body, headers), String.class);
                return;
            } catch (Exception e) {
                throw new IllegalStateException("signed webhook failed: " + e.getMessage(), e);
            }
        }
        rest.postForEntity(url, new HttpEntity<>(payload, headers), String.class);
    }

    private static String str(Object o, String def) {
        if (o == null) {
            return def;
        }
        String s = String.valueOf(o);
        return s.isBlank() || "null".equals(s) ? def : s;
    }

    private static String first(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b;
    }
}
