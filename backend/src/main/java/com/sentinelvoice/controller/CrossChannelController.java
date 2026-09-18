package com.sentinelvoice.controller;

import com.sentinelvoice.context.CrossChannelCorrelationService;
import com.sentinelvoice.context.model.CorrelationResult;
import com.sentinelvoice.context.model.CrossChannelEvent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cross-channel precursor ingest + correlation API (Context §7.3 / §8.3).
 * {@code POST /ingest} is the SIEM feed surface; {@code GET} returns correlation for a session.
 */
@RestController
@RequestMapping("/api/v1/cross-channel")
public class CrossChannelController {

    private final CrossChannelCorrelationService crossChannelCorrelationService;

    public CrossChannelController(CrossChannelCorrelationService crossChannelCorrelationService) {
        this.crossChannelCorrelationService = crossChannelCorrelationService;
    }

    @PostMapping("/ingest")
    public ResponseEntity<Map<String, Object>> ingest(@Valid @RequestBody IngestRequest request) {
        CrossChannelEvent event = new CrossChannelEvent();
        event.setChannel(request.channel());
        event.setTargetEmployeeId(request.targetEmployeeId().trim());
        event.setOccurredAt(Instant.ofEpochMilli(request.occurredAtEpochMs()));
        event.setSeverity(request.severity());
        event.setIndicator(request.indicator().trim());
        event.setCampaignId(request.campaignId() == null || request.campaignId().isBlank()
                ? null
                : request.campaignId().trim());
        event.setDescription(request.description().trim());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema", "sentinelvoice.CrossChannelEvent/1");
        body.putAll(crossChannelCorrelationService.ingest(event));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> correlate(
            @RequestParam String sessionId,
            @RequestParam(required = false) Integer hours
    ) {
        CorrelationResult result = crossChannelCorrelationService.correlateSession(sessionId, hours);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("targetEmployeeId", result.targetEmployeeId());
        body.put("windowHours", result.windowHours());
        body.put("correlationScore", result.correlationScore());
        body.put("matchingCampaign", result.matchingCampaign());
        body.put("events", result.events());
        // Backward-compatible alias used by the P1.4 stub clients.
        body.put("signals", result.events());
        return ResponseEntity.ok(body);
    }

    public record IngestRequest(
            @NotBlank String schema,
            @NotNull CrossChannelEvent.Channel channel,
            @NotBlank String targetEmployeeId,
            @NotNull Long occurredAtEpochMs,
            @NotNull CrossChannelEvent.Severity severity,
            @NotBlank String indicator,
            String campaignId,
            @NotBlank String description
    ) {
    }
}
