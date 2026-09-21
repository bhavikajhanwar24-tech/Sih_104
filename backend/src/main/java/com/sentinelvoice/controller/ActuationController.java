package com.sentinelvoice.controller;

import com.sentinelvoice.actuation.AsteriskAriAdapter;
import com.sentinelvoice.actuation.CallControlPort;
import com.sentinelvoice.response.execute.PlanRunner;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Channel binding + analyst hold retry for ARI actuation — populated by the AudioSocket / ARI snoop bridge.
 */
@RestController
@RequestMapping("/api/v1/actuation")
public class ActuationController {

    private final CallControlPort callControlPort;
    private final PlanRunner planRunner;

    public ActuationController(CallControlPort callControlPort, PlanRunner planRunner) {
        this.callControlPort = callControlPort;
        this.planRunner = planRunner;
    }

    @PostMapping("/{sessionId}/channel")
    public ResponseEntity<Map<String, Object>> bindChannel(
            @PathVariable String sessionId,
            @Valid @RequestBody ChannelBindRequest request
    ) {
        if (!(callControlPort instanceof AsteriskAriAdapter ari)) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "IGNORED");
            body.put("adapter", callControlPort.adapterName());
            body.put("message", "Channel bind only applies to asterisk-ari adapter");
            return ResponseEntity.ok(body);
        }
        String role = request.role() == null ? "caller" : request.role().trim().toLowerCase();
        if ("agent".equals(role)) {
            ari.bindAgentChannel(sessionId, request.channelId());
        } else {
            ari.bindChannel(sessionId, request.channelId());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "BOUND");
        body.put("sessionId", sessionId);
        body.put("channelId", request.channelId());
        body.put("role", role);
        body.put("adapter", ari.adapterName());
        return ResponseEntity.ok(body);
    }

    /** Analyst Accept hold — force physical ARI hold (idempotent retry). */
    @PostMapping("/{sessionId}/hold")
    public ResponseEntity<Map<String, Object>> forceHold(@PathVariable String sessionId) {
        PlanRunner.ActionResult result = planRunner.forceHold(sessionId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", result.name());
        body.put("sessionId", sessionId);
        body.put("adapter", callControlPort.adapterName());
        body.put("action", "HOLD_CALL");
        if (result == PlanRunner.ActionResult.FAILURE) {
            return ResponseEntity.status(502).body(body);
        }
        if (result == PlanRunner.ActionResult.UNSUPPORTED) {
            return ResponseEntity.status(501).body(body);
        }
        return ResponseEntity.ok(body);
    }

    public record ChannelBindRequest(
            @NotBlank String channelId,
            String role
    ) {
    }
}
