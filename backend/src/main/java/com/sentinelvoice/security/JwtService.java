package com.sentinelvoice.security;

import com.sentinelvoice.auth.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    private final SecretKey key;
    private final long accessTtlSeconds;

    public JwtService(AuthProperties properties) {
        byte[] secret = properties.jwtSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("JWT_SECRET must be at least 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(secret);
        this.accessTtlSeconds = properties.accessTokenTtlSeconds();
    }

    public String issueAccessToken(UUID userId, UUID tenantId, Role role, int tokenVersion) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(accessTtlSeconds);
        return Jwts.builder()
                .subject(userId.toString())
                .claim("tid", tenantId.toString())
                .claim("role", role.name())
                .claim("ver", tokenVersion)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(key)
                .compact();
    }

    public AccessClaims parseAccessToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        Object verObj = claims.get("ver");
        int ver = verObj instanceof Number n ? n.intValue() : 0;
        return new AccessClaims(
                UUID.fromString(claims.getSubject()),
                UUID.fromString(claims.get("tid", String.class)),
                Role.from(claims.get("role", String.class)),
                ver
        );
    }

    public record AccessClaims(UUID userId, UUID tenantId, Role role, int tokenVersion) {
    }
}
