package com.sentinelvoice.linguistics;

import com.sentinelvoice.model.CallSession;
import com.sentinelvoice.model.InterventionLevel;
import com.sentinelvoice.service.CallSessionManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * F11 — approval-gate synchrony: wait briefly for in-flight Stage B LLM, then return
 * freshest level for operator Approve clicks.
 */
@RestController
@RequestMapping("/internal/v2/sessions")
public class GateCheckController {

    private final CallSessionManager callSessionManager;
    private final RestTemplate restTemplate;
    private final String mlBaseUrl;
    private final String mlServiceToken;

    public GateCheckController(
            CallSessionManager callSessionManager,
            @Value("${sentinel.ml.base-url:http://127.0.0.1:8000}") String mlBaseUrl,
            @Value("${ML_SERVICE_TOKEN:}") String mlServiceToken
    ) {
        this.callSessionManager = callSessionManager;
        this.restTemplate = new RestTemplate();
        this.mlBaseUrl = mlBaseUrl.endsWith("/") ? mlBaseUrl.substring(0, mlBaseUrl.length() - 1) : mlBaseUrl;
        this.mlServiceToken = mlServiceToken == null ? "" : mlServiceToken;
    }

    @PostMapping("/{id}/gate-check")
    public Map<String, Object> gateCheck(@PathVariable("id") String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "session id required");
        }
        try {
            RequestEntity<Void> req = RequestEntity
                    .post(URI.create(mlBaseUrl + "/internal/v1/sessions/" + sessionId + "/gate-wait"))
                    .header("X-ML-Service-Token", mlServiceToken)
                    .accept(MediaType.APPLICATION_JSON)
                    .build();
            restTemplate.exchange(req, Map.class);
        } catch (Exception ignored) {
            // Best-effort wait
        }

        Optional<CallSession> mem = callSessionManager.getSession(sessionId);
        if (mem.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found");
        }
        CallSession session = mem.get();
        InterventionLevel level = session.getCurrentLevel() == null
                ? InterventionLevel.LEVEL_1_SILENT
                : session.getCurrentLevel();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", sessionId);
        out.put("level", level.name());
        out.put("smoothedRisk", session.getSmoothedRisk());
        out.put("waitedMs", 800);
        out.put("linguisticSource", session.getLastLinguisticSource());
        out.put("linguisticAgeMs", session.getLastLinguisticAgeMs());
        String lingStatus = "unavailable";
        if (Boolean.TRUE.equals(session.getLastLinguisticPending())) {
            lingStatus = "pending";
        } else if (session.getLastLinguisticSource() != null && !session.getLastLinguisticSource().isBlank()) {
            lingStatus = "live";
        }
        out.put("linguisticStatus", lingStatus);
        return out;
    }
}
