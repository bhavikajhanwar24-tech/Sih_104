package com.sentinelvoice.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Authenticates machine clients (AudioSocket bridge / ml-engine) with shared
 * {@code X-ML-Service-Token}.
 * <ul>
 *   <li>{@code /internal/v2/**} — token required</li>
 *   <li>{@code /api/v1/session/**}, {@code /api/v1/actuation/**} — token optional;
 *       when present authenticates as ML_SERVICE (+ optional {@code X-Tenant-Id});
 *       when absent, JWT/cookie auth proceeds as usual</li>
 * </ul>
 */
@Component
public class ServiceTokenAuthFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-ML-Service-Token";
    public static final String TENANT_HEADER = "X-Tenant-Id";

    private final MlServiceProperties mlServiceProperties;

    public ServiceTokenAuthFilter(MlServiceProperties mlServiceProperties) {
        this.mlServiceProperties = mlServiceProperties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) {
            return true;
        }
        return !(path.startsWith("/internal/v2/")
                || path.startsWith("/api/v1/session")
                || path.startsWith("/api/v1/actuation"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean internal = path != null && path.startsWith("/internal/v2/");
        String token = request.getHeader(HEADER);

        if (token == null || token.isBlank()) {
            if (internal) {
                writeUnauthorized(response);
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }

        if (!mlServiceProperties.matches(token)) {
            writeUnauthorized(response);
            return;
        }

        var auth = new UsernamePasswordAuthenticationToken(
                "ml-service",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_ML_SERVICE"))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);

        UUID tenantId = parseTenant(request.getHeader(TENANT_HEADER));
        boolean tenantSet = false;
        if (tenantId != null) {
            TenantContext.set(new TenantContext(tenantId, null, null, null, 0));
            tenantSet = true;
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (tenantSet) {
                TenantContext.clear();
            }
            SecurityContextHolder.clearContext();
        }
    }

    private static void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        byte[] body = "{\"error\":\"unauthorized\",\"message\":\"Invalid or missing X-ML-Service-Token\"}"
                .getBytes(StandardCharsets.UTF_8);
        response.getOutputStream().write(body);
    }

    private static UUID parseTenant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
