package com.sentinelvoice.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelvoice.model.FeatureFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class FeatureFrameSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(FeatureFrameSocketHandler.class);

    private final FeatureFrameIngestService ingestService;
    private final ObjectMapper objectMapper;

    public FeatureFrameSocketHandler(FeatureFrameIngestService ingestService, ObjectMapper objectMapper) {
        this.ingestService = ingestService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("feature_ws_open session={}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
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
}
