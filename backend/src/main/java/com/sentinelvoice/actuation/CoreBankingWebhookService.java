package com.sentinelvoice.actuation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * POSTs a beneficiary freeze instruction to a configurable CBS webhook (Context §11.6).
 * Default URL hits the in-process {@code /mock-cbs/freeze} endpoint — labelled MOCK everywhere.
 */
public class CoreBankingWebhookService {

    private static final Logger log = LoggerFactory.getLogger(CoreBankingWebhookService.class);

    private final RestTemplate restTemplate;
    private final Clock clock;
    private final String freezeUrl;
    /** In-process mock recorder (also used by MockCbsController). */
    private final CopyOnWriteArrayList<Map<String, Object>> mockFreezeLog = new CopyOnWriteArrayList<>();

    public CoreBankingWebhookService(RestTemplate restTemplate, Clock clock, String freezeUrl) {
        this.restTemplate = restTemplate;
        this.clock = clock;
        this.freezeUrl = freezeUrl;
    }

    public boolean freezeBeneficiary(String sessionId, String beneficiaryHint, String reason) {
        long started = clock.millis();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema", "sentinelvoice.MockCbsFreeze/1");
        body.put("mock", true);
        body.put("label", "MOCK_CBS — demo only, not a real core-banking system");
        body.put("sessionId", sessionId);
        body.put("beneficiary", beneficiaryHint == null ? "unknown" : beneficiaryHint);
        body.put("reason", reason == null ? "L5_TERMINATE" : reason);
        body.put("requestedAtMs", started);

        // In-process mock path: no HTTP needed (works in unit tests + same JVM).
        if (freezeUrl != null && freezeUrl.contains("/mock-cbs/freeze")) {
            recordMockFreeze(body);
            log.info("MOCK_CBS_FREEZE in-process sessionId={} url={}", sessionId, freezeUrl);
            return true;
        }

        boolean success;
        String detail;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            restTemplate.postForEntity(freezeUrl, new HttpEntity<>(body, headers), Map.class);
            success = true;
            detail = "accepted";
            log.info("MOCK_CBS_FREEZE ok sessionId={} url={}", sessionId, freezeUrl);
        } catch (RestClientException ex) {
            success = false;
            detail = ex.toString();
            log.warn("MOCK_CBS_FREEZE failed sessionId={} err={}", sessionId, ex.toString());
        }

        long latencyMs = clock.millis() - started;
        log.debug("MOCK_CBS_FREEZE detail={} latencyMs={}", detail, latencyMs);
        return success;
    }

    /** Record a freeze call received by the in-process mock endpoint. */
    public void recordMockFreeze(Map<String, Object> request) {
        Map<String, Object> copy = new LinkedHashMap<>(request);
        copy.put("receivedAtMs", clock.millis());
        mockFreezeLog.add(copy);
    }

    public List<Map<String, Object>> mockFreezeLog() {
        return Collections.unmodifiableList(new ArrayList<>(mockFreezeLog));
    }

    public void clearMockLog() {
        mockFreezeLog.clear();
    }
}
