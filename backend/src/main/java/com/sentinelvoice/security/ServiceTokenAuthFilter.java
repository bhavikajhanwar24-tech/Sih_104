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

/**
 * Authenticates {@code /internal/v2/**} with shared {@code X-ML-Service-Token}
 * (same secret as ml-engine). Not permitAll — missing/invalid token → 401.
 */
@Component
public class ServiceTokenAuthFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-ML-Service-Token";

    private final MlServiceProperties mlServiceProperties;

    public ServiceTokenAuthFilter(MlServiceProperties mlServiceProperties) {
        this.mlServiceProperties = mlServiceProperties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || !path.startsWith("/internal/v2/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String token = request.getHeader(HEADER);
        if (!mlServiceProperties.matches(token)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            byte[] body = "{\"error\":\"unauthorized\",\"message\":\"Invalid or missing X-ML-Service-Token\"}"
                    .getBytes(StandardCharsets.UTF_8);
            response.getOutputStream().write(body);
            return;
        }
        var auth = new UsernamePasswordAuthenticationToken(
                "ml-service",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_ML_SERVICE"))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);
        filterChain.doFilter(request, response);
    }
}
