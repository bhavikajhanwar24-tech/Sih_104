package com.sentinelvoice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP channel config. Cookie JWT for SockJS will land in a later feature; for now
 * the broker accepts authenticated HTTP sessions only via the HTTP security chain.
 */
@Configuration
@Order(Ordered.HIGHEST_PRECEDENCE + 99)
public class StompSecurityConfig implements WebSocketMessageBrokerConfigurer {

    @Bean
    public ChannelInterceptor stompAuthChannelInterceptor() {
        return new ChannelInterceptor() {
        };
    }
}
