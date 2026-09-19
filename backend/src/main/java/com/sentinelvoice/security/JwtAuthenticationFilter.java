package com.sentinelvoice.security;

import com.sentinelvoice.auth.AuthService;
import com.sentinelvoice.auth.Role;
import com.sentinelvoice.auth.UserEntity;
import com.sentinelvoice.auth.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            String token = readCookie(request, AuthService.ACCESS_COOKIE);
            if (token != null && !token.isBlank()) {
                JwtService.AccessClaims claims = jwtService.parseAccessToken(token);
                UserEntity user = userRepository.findById(claims.userId()).orElse(null);
                if (user != null
                        && "ACTIVE".equals(user.getStatus())
                        && user.getTokenVersion() == claims.tokenVersion()
                        && user.getTenantId().equals(claims.tenantId())) {
                    Role role = Role.from(user.getRole());
                    TenantContext.set(new TenantContext(
                            claims.tenantId(),
                            claims.userId(),
                            role,
                            user.getEmail(),
                            user.getTokenVersion()
                    ));
                    var auth = new UsernamePasswordAuthenticationToken(
                            claims.userId().toString(),
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))
                    );
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private static String readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (name.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}
