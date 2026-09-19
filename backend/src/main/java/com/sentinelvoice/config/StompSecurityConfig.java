package com.sentinelvoice.config;

import com.sentinelvoice.auth.AuthService;
import com.sentinelvoice.auth.Role;
import com.sentinelvoice.security.JwtService;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * STOMP CONNECT authenticates via {@code sv_access} JWT cookie; SUBSCRIBE is tenant-scoped.
 */
@Configuration
@Order(Ordered.HIGHEST_PRECEDENCE + 99)
public class StompSecurityConfig implements WebSocketMessageBrokerConfigurer {

    private static final Pattern TENANT_TOPIC = Pattern.compile(
            "^/topic/tenant/([0-9a-fA-F-]{36})/(telemetry|actuation)/.+$"
    );

    private final JwtService jwtService;

    public StompSecurityConfig(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(
                        message, StompHeaderAccessor.class);
                if (accessor == null) {
                    return message;
                }
                if (StompCommand.CONNECT.equals(accessor.getCommand())) {
                    authenticateConnect(accessor);
                } else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
                    authorizeSubscribe(accessor);
                }
                return message;
            }
        });
    }

    private void authenticateConnect(StompHeaderAccessor accessor) {
        String cookieHeader = firstHeader(accessor, "cookie");
        String token = readCookie(cookieHeader, AuthService.ACCESS_COOKIE);
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("STOMP CONNECT requires access cookie");
        }
        JwtService.AccessClaims claims = jwtService.parseAccessToken(token);
        var auth = new UsernamePasswordAuthenticationToken(
                claims.userId().toString(),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + claims.role().name()))
        );
        accessor.setUser(auth);
        Map<String, Object> attrs = accessor.getSessionAttributes();
        if (attrs != null) {
            attrs.put("tenantId", claims.tenantId().toString());
            attrs.put("role", claims.role().name());
            attrs.put("userId", claims.userId().toString());
        }
    }

    private void authorizeSubscribe(StompHeaderAccessor accessor) {
        String dest = accessor.getDestination();
        if (dest == null) {
            throw new IllegalArgumentException("SUBSCRIBE destination required");
        }
        Matcher m = TENANT_TOPIC.matcher(dest);
        if (!m.matches()) {
            throw new IllegalArgumentException("SUBSCRIBE denied: invalid topic");
        }
        UUID topicTenant = UUID.fromString(m.group(1));
        Map<String, Object> attrs = accessor.getSessionAttributes();
        if (attrs == null || attrs.get("tenantId") == null) {
            throw new IllegalArgumentException("SUBSCRIBE denied: not authenticated");
        }
        UUID authTenant = UUID.fromString(attrs.get("tenantId").toString());
        if (!authTenant.equals(topicTenant)) {
            throw new IllegalArgumentException("SUBSCRIBE denied: tenant mismatch");
        }
        String roleName = String.valueOf(attrs.get("role"));
        Role role = Role.from(roleName);
        if (role != Role.TENANT_ADMIN && role != Role.ANALYST && role != Role.AUDITOR) {
            throw new IllegalArgumentException("SUBSCRIBE denied: role not permitted");
        }
    }

    private static String firstHeader(StompHeaderAccessor accessor, String name) {
        List<String> values = accessor.getNativeHeader(name);
        if (values == null || values.isEmpty()) {
            values = accessor.getNativeHeader(name.substring(0, 1).toUpperCase() + name.substring(1));
        }
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static String readCookie(String cookieHeader, String name) {
        if (cookieHeader == null) {
            return null;
        }
        for (String part : cookieHeader.split(";")) {
            String trimmed = part.trim();
            int eq = trimmed.indexOf('=');
            if (eq > 0 && name.equals(trimmed.substring(0, eq).trim())) {
                return trimmed.substring(eq + 1).trim();
            }
        }
        return null;
    }
}
