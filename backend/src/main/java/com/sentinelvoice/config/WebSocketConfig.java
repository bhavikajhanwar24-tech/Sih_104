package com.sentinelvoice.config;

import com.sentinelvoice.ingest.FeatureFrameSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

@Configuration
@EnableWebSocket
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer, WebSocketConfigurer {

    private static final String STOMP_ENDPOINT = "/ws-sentinel";
    /** Dev-friendly origins — localhost and 127.0.0.1 on any port (Vite may shift). */
    private static final String[] ALLOWED_ORIGIN_PATTERNS = {
            "http://localhost:*",
            "http://127.0.0.1:*",
            "https://localhost:*",
            "https://127.0.0.1:*",
    };

    private final FeatureFrameSocketHandler featureFrameSocketHandler;
    private final JwtHandshakeInterceptor jwtHandshakeInterceptor;
    private final com.sentinelvoice.security.AuthProperties authProperties;

    public WebSocketConfig(
            FeatureFrameSocketHandler featureFrameSocketHandler,
            JwtHandshakeInterceptor jwtHandshakeInterceptor,
            com.sentinelvoice.security.AuthProperties authProperties
    ) {
        this.featureFrameSocketHandler = featureFrameSocketHandler;
        this.jwtHandshakeInterceptor = jwtHandshakeInterceptor;
        this.authProperties = authProperties;
    }

    private String[] resolveAllowedOrigins() {
        java.util.LinkedHashSet<String> patterns = new java.util.LinkedHashSet<>(java.util.List.of(
                "http://localhost:*",
                "http://127.0.0.1:*",
                "https://localhost:*",
                "https://127.0.0.1:*",
                "https://*.vercel.app"
        ));
        String configured = authProperties.corsOrigins();
        if (configured != null && !configured.isBlank()) {
            for (String origin : configured.split(",")) {
                String trimmed = origin.trim();
                if (!trimmed.isEmpty()) {
                    patterns.add(trimmed);
                }
            }
        }
        return patterns.toArray(new String[0]);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(featureFrameSocketHandler, "/ws/features")
                .setAllowedOriginPatterns("*");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        String[] patterns = resolveAllowedOrigins();
        registry.addEndpoint(STOMP_ENDPOINT)
                .addInterceptors(jwtHandshakeInterceptor)
                .setAllowedOriginPatterns(patterns);
        registry.addEndpoint(STOMP_ENDPOINT)
                .addInterceptors(jwtHandshakeInterceptor)
                .setAllowedOriginPatterns(patterns)
                .withSockJS();
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(256 * 1024);
        registration.setSendBufferSizeLimit(1024 * 1024);
        registration.setSendTimeLimit(20000);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }
}
