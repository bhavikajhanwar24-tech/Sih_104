package com.sentinelvoice.auth;

import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.audit.AuditLedgerService;
import com.sentinelvoice.security.AuthProperties;
import com.sentinelvoice.security.JwtService;
import com.sentinelvoice.tenant.TenantEntity;
import com.sentinelvoice.tenant.TenantRepository;
import com.warrenstrange.googleauth.GoogleAuthenticator;
import com.warrenstrange.googleauth.GoogleAuthenticatorKey;
import com.warrenstrange.googleauth.GoogleAuthenticatorQRGenerator;
import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {

    public static final String ACCESS_COOKIE = "sv_access";
    public static final String REFRESH_COOKIE = "sv_refresh";

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final LoginFailureRepository loginFailureRepository;
    private final LoginLockRepository loginLockRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthProperties authProperties;
    private final AuditLedgerService auditLedgerService;
    private final GoogleAuthenticator googleAuthenticator = new GoogleAuthenticator();
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthService(
            TenantRepository tenantRepository,
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            LoginFailureRepository loginFailureRepository,
            LoginLockRepository loginLockRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            AuthProperties authProperties,
            AuditLedgerService auditLedgerService
    ) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.loginFailureRepository = loginFailureRepository;
        this.loginLockRepository = loginLockRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.authProperties = authProperties;
        this.auditLedgerService = auditLedgerService;
    }

    @Transactional
    public LoginOutcome login(String tenantSlug, String email, String password, String mfaCode, String ip) {
        TenantEntity tenant = tenantRepository.findBySlugIgnoreCase(tenantSlug)
                .orElseThrow(() -> new AuthException("Invalid credentials"));
        String emailLower = email.trim().toLowerCase(Locale.ROOT);
        assertNotLocked(tenant.getId(), emailLower, ip);

        Optional<UserEntity> userOpt = userRepository.findByTenantIdAndEmailIgnoreCase(tenant.getId(), emailLower);
        if (userOpt.isEmpty() || !"ACTIVE".equals(userOpt.get().getStatus())
                || !passwordEncoder.matches(password, userOpt.get().getPasswordHash())) {
            recordFailure(tenant.getId(), emailLower, ip);
            throw new AuthException("Invalid credentials");
        }
        UserEntity user = userOpt.get();
        if (user.isMfaEnabled()) {
            if (mfaCode == null || mfaCode.isBlank()) {
                return LoginOutcome.requireMfa();
            }
            if (!googleAuthenticator.authorize(user.getMfaSecret(), parseCode(mfaCode))) {
                recordFailure(tenant.getId(), emailLower, ip);
                throw new AuthException("Invalid MFA code");
            }
        }
        clearFailures(tenant.getId(), emailLower, ip);
        user.setLastLoginAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);

        auditLedgerService.append(
                tenant.getId(),
                null,
                AuditEventType.LOGIN_SUCCESS,
                "USER",
                user.getId().toString(),
                Map.of("email", emailLower, "ip", ip)
        );
        return LoginOutcome.success(issueSession(user, tenant.getId()));
    }

    @Transactional
    public SessionTokens refresh(String refreshRaw) {
        if (refreshRaw == null || refreshRaw.isBlank()) {
            throw new AuthException("Refresh token missing");
        }
        String hash = sha256(refreshRaw);
        RefreshTokenEntity existing = refreshTokenRepository.findByTokenHashAndRevokedAtIsNull(hash)
                .orElseThrow(() -> new AuthException("Invalid refresh token"));
        if (existing.getExpiresAt().isBefore(Instant.now())) {
            throw new AuthException("Refresh token expired");
        }
        UserEntity user = userRepository.findById(existing.getUserId())
                .orElseThrow(() -> new AuthException("User gone"));
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new AuthException("User disabled");
        }
        existing.setRevokedAt(Instant.now());
        SessionTokens rotated = issueSession(user, user.getTenantId());
        existing.setReplacedBy(rotated.refreshTokenId());
        refreshTokenRepository.save(existing);
        return rotated;
    }

    @Transactional
    public void logout(String refreshRaw, UUID tenantId, UUID userId) {
        if (refreshRaw != null && !refreshRaw.isBlank()) {
            refreshTokenRepository.findByTokenHashAndRevokedAtIsNull(sha256(refreshRaw)).ifPresent(tok -> {
                tok.setRevokedAt(Instant.now());
                refreshTokenRepository.save(tok);
            });
        }
        if (tenantId != null && userId != null) {
            auditLedgerService.append(
                    tenantId,
                    null,
                    AuditEventType.AUTH_LOGOUT,
                    "USER",
                    userId.toString(),
                    Map.of()
            );
        }
    }

    @Transactional
    public MfaEnrollResult enrollMfa(UUID userId) {
        UserEntity user = userRepository.findById(userId).orElseThrow();
        GoogleAuthenticatorKey key = googleAuthenticator.createCredentials();
        user.setMfaSecret(key.getKey());
        user.setMfaEnabled(false);
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);
        String otpauth = GoogleAuthenticatorQRGenerator.getOtpAuthTotpURL(
                "SentinelVoice",
                user.getEmail(),
                key
        );
        return new MfaEnrollResult(otpauth, key.getKey());
    }

    @Transactional
    public void verifyMfa(UUID userId, String code) {
        UserEntity user = userRepository.findById(userId).orElseThrow();
        if (user.getMfaSecret() == null) {
            throw new IllegalArgumentException("MFA not enrolled");
        }
        if (!googleAuthenticator.authorize(user.getMfaSecret(), parseCode(code))) {
            throw new AuthException("Invalid MFA code");
        }
        user.setMfaEnabled(true);
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);
        auditLedgerService.append(
                user.getTenantId(),
                null,
                AuditEventType.MFA_ENROLLED,
                "USER",
                user.getId().toString(),
                Map.of()
        );
    }

    @Transactional
    public void disableMfa(UUID userId, String code) {
        UserEntity user = userRepository.findById(userId).orElseThrow();
        if (user.isMfaEnabled() && !googleAuthenticator.authorize(user.getMfaSecret(), parseCode(code))) {
            throw new AuthException("Invalid MFA code");
        }
        user.setMfaEnabled(false);
        user.setMfaSecret(null);
        user.setTokenVersion(user.getTokenVersion() + 1);
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);
        auditLedgerService.append(
                user.getTenantId(),
                null,
                AuditEventType.MFA_DISABLED,
                "USER",
                user.getId().toString(),
                Map.of()
        );
    }

    public ResponseCookie accessCookie(String token) {
        return baseCookie(ACCESS_COOKIE, token, Duration.ofSeconds(authProperties.accessTokenTtlSeconds()));
    }

    public ResponseCookie refreshCookie(String token) {
        return baseCookie(REFRESH_COOKIE, token, Duration.ofSeconds(authProperties.refreshTokenTtlSeconds()));
    }

    public ResponseCookie clearAccessCookie() {
        return baseCookie(ACCESS_COOKIE, "", Duration.ZERO);
    }

    public ResponseCookie clearRefreshCookie() {
        return baseCookie(REFRESH_COOKIE, "", Duration.ZERO);
    }

    private ResponseCookie baseCookie(String name, String value, Duration maxAge) {
        ResponseCookie.ResponseCookieBuilder b = ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(authProperties.cookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge);
        if (authProperties.cookieDomain() != null && !authProperties.cookieDomain().isBlank()) {
            b.domain(authProperties.cookieDomain());
        }
        return b.build();
    }

    private SessionTokens issueSession(UserEntity user, UUID tenantId) {
        Role role = Role.from(user.getRole());
        String access = jwtService.issueAccessToken(user.getId(), tenantId, role, user.getTokenVersion());
        String refreshRaw = randomToken();
        Instant now = Instant.now();
        RefreshTokenEntity refresh = new RefreshTokenEntity();
        refresh.setId(UUID.randomUUID());
        refresh.setTenantId(tenantId);
        refresh.setUserId(user.getId());
        refresh.setTokenHash(sha256(refreshRaw));
        refresh.setExpiresAt(now.plusSeconds(authProperties.refreshTokenTtlSeconds()));
        refresh.setCreatedAt(now);
        refreshTokenRepository.save(refresh);
        return new SessionTokens(access, refreshRaw, refresh.getId());
    }

    private void assertNotLocked(UUID tenantId, String emailLower, String ip) {
        loginLockRepository.findByTenantIdAndEmailLowerAndIpAddress(tenantId, emailLower, ip)
                .ifPresent(lock -> {
                    if (lock.getLockedUntil().isAfter(Instant.now())) {
                        throw new AuthException("Account temporarily locked. Try again later.");
                    }
                });
    }

    private void recordFailure(UUID tenantId, String emailLower, String ip) {
        LoginFailureEntity fail = new LoginFailureEntity();
        fail.setId(UUID.randomUUID());
        fail.setTenantId(tenantId);
        fail.setEmailLower(emailLower);
        fail.setIpAddress(ip);
        fail.setFailedAt(Instant.now());
        loginFailureRepository.save(fail);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("email", emailLower);
        payload.put("ip", ip);
        auditLedgerService.append(tenantId, null, AuditEventType.LOGIN_FAILED, "SYSTEM", null, payload);

        Instant since = Instant.now().minus(Duration.ofMinutes(15));
        long count = loginFailureRepository.countRecent(tenantId, emailLower, ip, since);
        if (count >= 5) {
            LoginLockEntity lock = loginLockRepository
                    .findByTenantIdAndEmailLowerAndIpAddress(tenantId, emailLower, ip)
                    .orElseGet(LoginLockEntity::new);
            if (lock.getId() == null) {
                lock.setId(UUID.randomUUID());
                lock.setTenantId(tenantId);
                lock.setEmailLower(emailLower);
                lock.setIpAddress(ip);
                lock.setCreatedAt(Instant.now());
            }
            lock.setLockedUntil(Instant.now().plus(Duration.ofMinutes(15)));
            loginLockRepository.save(lock);
            auditLedgerService.append(
                    tenantId,
                    null,
                    AuditEventType.LOGIN_LOCK,
                    "SYSTEM",
                    null,
                    Map.of("email", emailLower, "ip", ip, "failures", count)
            );
        }
    }

    private void clearFailures(UUID tenantId, String emailLower, String ip) {
        loginLockRepository.findByTenantIdAndEmailLowerAndIpAddress(tenantId, emailLower, ip)
                .ifPresent(loginLockRepository::delete);
    }

    private static int parseCode(String code) {
        try {
            return Integer.parseInt(code.trim());
        } catch (NumberFormatException e) {
            throw new AuthException("Invalid MFA code");
        }
    }

    private String randomToken() {
        byte[] buf = new byte[32];
        secureRandom.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }

    static String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public record SessionTokens(String accessToken, String refreshToken, UUID refreshTokenId) {
    }

    public record MfaEnrollResult(String otpauthUri, String secret) {
    }

    public record LoginOutcome(boolean mfaRequired, SessionTokens tokens) {
        public static LoginOutcome requireMfa() {
            return new LoginOutcome(true, null);
        }

        public static LoginOutcome success(SessionTokens tokens) {
            return new LoginOutcome(false, tokens);
        }
    }

    public static class AuthException extends RuntimeException {
        public AuthException(String message) {
            super(message);
        }
    }
}
