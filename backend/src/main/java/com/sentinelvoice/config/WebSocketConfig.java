package com.sentinelvoice.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final String STOMP_ENDPOINT = "/ws-sentinel";
    private static final String[] ALLOWED_ORIGIN_PATTERNS = SecurityConfig.ALLOWED_ORIGINS.toArray(String[]::new);

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint(STOMP_ENDPOINT)
                .setAllowedOriginPatterns(ALLOWED_ORIGIN_PATTERNS);
        registry.addEndpoint(STOMP_ENDPOINT)
                .setAllowedOriginPatterns(ALLOWED_ORIGIN_PATTERNS)
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
