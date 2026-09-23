package com.sentinelvoice.integrations;

import com.sentinelvoice.security.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Authenticates {@code Authorization: Bearer sv_live_…} or {@code X-API-Key}.
 */
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-API-Key";

    private final ApiKeyService apiKeyService;
    private final ApiKeyRateLimiter rateLimiter;
    private final TransactionTemplate transactionTemplate;

    public ApiKeyAuthFilter(
            ApiKeyService apiKeyService,
            ApiKeyRateLimiter rateLimiter,
            PlatformTransactionManager transactionManager
    ) {
        this.apiKeyService = apiKeyService;
        this.rateLimiter = rateLimiter;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) {
            return true;
        }
        // Only attempt API-key auth on integration surface (and docs when keyed).
        return !(path.startsWith("/api/v2/integrations/")
                || path.startsWith("/api/v2/docs"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        Authentication existing = SecurityContextHolder.getContext().getAuthentication();
        if (existing != null
                && existing.isAuthenticated()
                && !(existing instanceof org.springframework.security.authentication.AnonymousAuthenticationToken)
                && existing.getPrincipal() != null
                && !"anonymousUser".equals(existing.getPrincipal())) {
            // Cookie/JWT already authenticated — leave alone (management UI).
            filterChain.doFilter(request, response);
            return;
        }

        String secret = extractSecret(request);
        if (secret == null) {
            filterChain.doFilter(request, response);
            return;
        }

        Optional<ApiKeyService.ResolvedKey> resolved = transactionTemplate.execute(status ->
                apiKeyService.resolveBySecret(secret));
        if (resolved == null || resolved.isEmpty()) {
            writeUnauthorized(response, "Invalid API key");
            return;
        }
        ApiKeyService.ResolvedKey key = resolved.get();
        if (!rateLimiter.tryConsume(key.keyId(), key.rateLimitRps())) {
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getOutputStream().write(
                    "{\"error\":\"rate_limited\",\"message\":\"API key rate limit exceeded\"}"
                            .getBytes(StandardCharsets.UTF_8));
            return;
        }

        transactionTemplate.executeWithoutResult(status -> apiKeyService.touchUsage(key.keyId()));

        ApiKeyPrincipal principal = new ApiKeyPrincipal(
                key.keyId(), key.tenantId(), key.name(), key.prefix(), key.scopes());
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_API_KEY"));
        for (String scope : key.scopes()) {
            authorities.add(new SimpleGrantedAuthority("SCOPE_" + scope));
        }
        var auth = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);
        TenantContext.set(new TenantContext(key.tenantId(), null, null, "api-key:" + key.prefix(), 0));

        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    static String extractSecret(HttpServletRequest request) {
        String headerKey = request.getHeader(API_KEY_HEADER);
        if (headerKey != null && !headerKey.isBlank()) {
            return headerKey.trim();
        }
        String auth = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = auth.substring(7).trim();
            if (token.startsWith(ApiKeyService.SECRET_PREFIX)) {
                return token;
            }
        }
        return null;
    }

    private static void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        byte[] body = ("{\"error\":\"unauthorized\",\"message\":\"" + message + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        response.getOutputStream().write(body);
    }
}
