package com.sentinelvoice.actuation;

import com.sentinelvoice.config.SentinelProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Posts a beneficiary/account freeze instruction to a configurable CBS webhook.
 * Lab default: {@code http://127.0.0.1:8080/mock-cbs/freeze}.
 */
@Service
public class CoreBankingWebhookService {

    private static final Logger log = LoggerFactory.getLogger(CoreBankingWebhookService.class);

    private final String freezeUrl;
    private final HttpClient httpClient;

    public CoreBankingWebhookService(SentinelProperties properties) {
        this.freezeUrl = properties.actuation().cbsFreezeUrl();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    public ActuationResult freezeBeneficiary(String sessionId, String reason) {
        try {
            String body = """
                    {"sessionId":"%s","action":"FREEZE","reason":"%s","source":"SentinelVoice"}
                    """.formatted(
                    escape(sessionId),
                    escape(reason == null ? "L5_TERMINATE" : reason)
            ).trim();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(freezeUrl))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("cbs_freeze_ok sessionId={} url={} status={}", sessionId, freezeUrl, response.statusCode());
                return ActuationResult.success("cbs-freeze-" + response.statusCode());
            }
            log.warn(
                    "cbs_freeze_failed sessionId={} status={} body={}",
                    sessionId,
                    response.statusCode(),
                    response.body()
            );
            return ActuationResult.failure("CBS " + response.statusCode());
        } catch (Exception ex) {
            log.warn("cbs_freeze_exception sessionId={} cause={}", sessionId, ex.toString());
            return ActuationResult.failure(ex.getMessage());
        }
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public Map<String, Object> describeConfig() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cbsFreezeUrl", freezeUrl);
        return m;
    }
}
