package com.sentinelvoice.config;

import com.sentinelvoice.auth.AuthService;
import com.sentinelvoice.auth.Role;
import com.sentinelvoice.security.JwtService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * Pulls {@code sv_access} from the HTTP Upgrade cookies (HttpOnly — not available to JS)
 * into WebSocket session attributes for {@link StompSecurityConfig}.
 */
@Component
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    private final JwtService jwtService;

    public JwtHandshakeInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes
    ) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            return true;
        }
        HttpServletRequest http = servletRequest.getServletRequest();
        String token = readCookie(http, AuthService.ACCESS_COOKIE);
        if (token == null || token.isBlank()) {
            // Allow handshake; CONNECT will reject with a clear STOMP error.
            return true;
        }
        try {
            JwtService.AccessClaims claims = jwtService.parseAccessToken(token);
            attributes.put("tenantId", claims.tenantId().toString());
            attributes.put("userId", claims.userId().toString());
            attributes.put("role", claims.role().name());
            attributes.put("tokenVersion", claims.tokenVersion());
        } catch (Exception ignored) {
            // Invalid/expired — CONNECT will fail closed.
        }
        return true;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception
    ) {
        // no-op
    }

    private static String readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
