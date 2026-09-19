package com.sentinelvoice.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelvoice.model.FeatureFrame;
import com.sentinelvoice.security.MlServiceProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.List;
import java.util.Map;

@Component
public class FeatureFrameSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(FeatureFrameSocketHandler.class);

    private final FeatureFrameIngestService ingestService;
    private final ObjectMapper objectMapper;
    private final MlServiceProperties mlServiceProperties;
    private final Counter authRejected;

    public FeatureFrameSocketHandler(
            FeatureFrameIngestService ingestService,
            ObjectMapper objectMapper,
            MlServiceProperties mlServiceProperties,
            MeterRegistry meterRegistry
    ) {
        this.ingestService = ingestService;
        this.objectMapper = objectMapper;
        this.mlServiceProperties = mlServiceProperties;
        this.authRejected = Counter.builder("sentinel.ml.ws.auth_rejected")
                .description("ML feature WS connections rejected for bad/missing service token")
                .register(meterRegistry);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if (!authorize(session)) {
            authRejected.increment();
            log.warn("feature_ws_auth_rejected session={}", session.getId());
            session.close(CloseStatus.NOT_ACCEPTABLE.withReason("invalid service token"));
            return;
        }
        log.info("feature_ws_open session={}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        if (!Boolean.TRUE.equals(session.getAttributes().get("mlAuthed"))) {
            return;
        }
        String payload = message.getPayload();
        try {
            FeatureFrame frame = objectMapper.readValue(payload, FeatureFrame.class);
            ingestService.ingest(frame);
        } catch (FrameValidationException ex) {
            log.warn("feature_frame_invalid reason={}", ex.getMessage());
        } catch (Exception ex) {
            ingestService.rejectInvalid(ex);
            log.warn("feature_frame_invalid reason={} bytes={}", ex.getClass().getSimpleName(), payload.length());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("feature_ws_close session={} status={}", session.getId(), status);
    }

    private boolean authorize(WebSocketSession session) {
        if (!mlServiceProperties.isConfigured()) {
            log.error("ML_SERVICE_TOKEN / sentinelvoice.ml.service-token is not configured");
            return false;
        }
        String token = null;
        List<String> header = session.getHandshakeHeaders().get("X-ML-Service-Token");
        if (header != null && !header.isEmpty()) {
            token = header.get(0);
        }
        if (token == null) {
            URI uri = session.getUri();
            if (uri != null && uri.getQuery() != null) {
                for (String part : uri.getQuery().split("&")) {
                    int eq = part.indexOf('=');
                    if (eq > 0 && "token".equals(part.substring(0, eq))) {
                        token = part.substring(eq + 1);
                    }
                }
            }
        }
        boolean ok = mlServiceProperties.matches(token);
        if (ok) {
            session.getAttributes().put("mlAuthed", true);
        }
        return ok;
    }
}
