package com.sentinelvoice.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Java client for the Inference-plane LLM gateway (F5). Never used on the 500 ms fast path.
 */
@Component
@EnableConfigurationProperties(LlmGatewayProperties.class)
public class LlmGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(LlmGatewayClient.class);

    private final RestTemplate restTemplate;
    private final LlmGatewayProperties properties;
    private final ObjectMapper objectMapper;

    public LlmGatewayClient(
            RestTemplateBuilder builder,
            LlmGatewayProperties properties,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofMillis(properties.connectTimeoutMs()))
                .setReadTimeout(Duration.ofMillis(properties.readTimeoutMs()))
                .build();
    }

    @CircuitBreaker(name = "llmGateway", fallbackMethod = "healthFallback")
    public Map<String, Object> health() {
        HttpHeaders headers = authHeaders();
        ResponseEntity<Map> resp = restTemplate.exchange(
                properties.baseUrl() + "/llm/v1/health",
                org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class
        );
        @SuppressWarnings("unchecked")
        Map<String, Object> body = resp.getBody() == null ? Map.of() : resp.getBody();
        return body;
    }

    @CircuitBreaker(name = "llmGateway", fallbackMethod = "selftestFallback")
    public Map<String, Object> selftest() {
        HttpHeaders headers = authHeaders();
        ResponseEntity<Map> resp = restTemplate.exchange(
                properties.baseUrl() + "/llm/v1/selftest",
                org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(headers),
                Map.class
        );
        @SuppressWarnings("unchecked")
        Map<String, Object> body = resp.getBody() == null ? Map.of() : resp.getBody();
        return body;
    }

    @CircuitBreaker(name = "llmGateway", fallbackMethod = "runFallback")
    public Map<String, Object> run(String task, UUID tenantId, Map<String, Object> payload) {
        long start = System.nanoTime();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("task", task);
            body.put("tenantId", tenantId == null ? null : tenantId.toString());
            body.put("payload", payload == null ? Map.of() : payload);
            HttpHeaders headers = authHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<Map> resp = restTemplate.exchange(
                    properties.baseUrl() + "/llm/v1/run",
                    org.springframework.http.HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    Map.class
            );
            @SuppressWarnings("unchecked")
            Map<String, Object> out = resp.getBody() == null ? Map.of("ok", false) : resp.getBody();
            long ms = (System.nanoTime() - start) / 1_000_000L;
            log.info(
                    "llm_gateway_run task={} tenantId={} ok={} provider={} latencyMs={}",
                    task,
                    tenantId,
                    out.get("ok"),
                    out.get("provider"),
                    ms
            );
            return out;
        } catch (Exception ex) {
            long ms = (System.nanoTime() - start) / 1_000_000L;
            log.warn("llm_gateway_run_failed task={} latencyMs={} cause={}", task, ms, ex.toString());
            throw ex;
        }
    }

    @SuppressWarnings("unused")
    private Map<String, Object> healthFallback(Throwable t) {
        Map<String, Object> ollama = new LinkedHashMap<>();
        ollama.put("reachable", false);
        ollama.put("modelPresent", false);
        ollama.put("error", t.getMessage() == null ? "gateway_unreachable" : t.getMessage());
        Map<String, Object> openai = new LinkedHashMap<>();
        openai.put("enabled", false);
        openai.put("reachable", null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("gateway", "down");
        out.put("ollama", ollama);
        out.put("openaiCompat", openai);
        out.put("activeProvider", "mock");
        out.put("degraded", true);
        out.put("provider", "mock");
        out.put("model", "mock");
        out.put("error", t.getMessage() == null ? "circuit_open" : t.getMessage());
        return out;
    }

    @SuppressWarnings("unused")
    private Map<String, Object> selftestFallback(Throwable t) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider", "unreachable");
        out.put("model", "mock");
        out.put("latencyMs", 0);
        out.put("schemaValid", false);
        out.put("tokensPerSecond", null);
        out.put("error", t.getMessage() == null ? "circuit_open" : t.getMessage());
        return out;
    }

    @SuppressWarnings("unused")
    private Map<String, Object> runFallback(String task, UUID tenantId, Map<String, Object> payload, Throwable t) {
        return Map.of(
                "ok", false,
                "provider", "unreachable",
                "error", "CIRCUIT_OPEN",
                "detail", t.getMessage() == null ? "" : t.getMessage()
        );
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        if (properties.serviceToken() != null && !properties.serviceToken().isBlank()) {
            headers.set("X-ML-Service-Token", properties.serviceToken());
        }
        return headers;
    }

    public JsonNode parseJson(Map<String, Object> map) {
        return objectMapper.valueToTree(map);
    }
}
