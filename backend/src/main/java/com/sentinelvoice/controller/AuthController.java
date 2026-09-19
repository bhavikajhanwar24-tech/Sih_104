package com.sentinelvoice.controller;

import com.sentinelvoice.auth.AuthService;
import com.sentinelvoice.auth.AuthService.AuthException;
import com.sentinelvoice.auth.AuthService.LoginOutcome;
import com.sentinelvoice.auth.AuthService.SessionTokens;
import com.sentinelvoice.security.TenantContext;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v2/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of(
                "headerName", token == null ? "X-XSRF-TOKEN" : token.getHeaderName(),
                "parameterName", token == null ? "_csrf" : token.getParameterName(),
                "token", token == null ? "" : token.getToken()
        );
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(
            @Valid @RequestBody LoginBody body,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        String ip = clientIp(request);
        LoginOutcome outcome = authService.login(
                body.tenantSlug(),
                body.email(),
                body.password(),
                body.mfaCode(),
                ip
        );
        Map<String, Object> resp = new LinkedHashMap<>();
        if (outcome.mfaRequired()) {
            resp.put("mfaRequired", true);
            return ResponseEntity.ok(resp);
        }
        SessionTokens tokens = outcome.tokens();
        response.addHeader(HttpHeaders.SET_COOKIE, authService.accessCookie(tokens.accessToken()).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, authService.refreshCookie(tokens.refreshToken()).toString());
        resp.put("mfaRequired", false);
        resp.put("ok", true);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refresh(
            @CookieValue(value = AuthService.REFRESH_COOKIE, required = false) String refresh,
            HttpServletResponse response
    ) {
        SessionTokens tokens = authService.refresh(refresh);
        response.addHeader(HttpHeaders.SET_COOKIE, authService.accessCookie(tokens.accessToken()).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, authService.refreshCookie(tokens.refreshToken()).toString());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(
            @CookieValue(value = AuthService.REFRESH_COOKIE, required = false) String refresh,
            HttpServletResponse response
    ) {
        TenantContext ctx = TenantContext.get();
        authService.logout(
                refresh,
                ctx == null ? null : ctx.tenantId(),
                ctx == null ? null : ctx.userId()
        );
        response.addHeader(HttpHeaders.SET_COOKIE, authService.clearAccessCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, authService.clearRefreshCookie().toString());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/mfa/enroll")
    public Map<String, Object> enrollMfa() {
        TenantContext ctx = TenantContext.require();
        var result = authService.enrollMfa(ctx.userId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("otpauthUri", result.otpauthUri());
        body.put("secret", result.secret());
        body.put("qrData", result.otpauthUri());
        return body;
    }

    @PostMapping("/mfa/verify")
    public Map<String, Object> verifyMfa(@RequestBody MfaBody body) {
        authService.verifyMfa(TenantContext.require().userId(), body.code());
        return Map.of("mfaEnabled", true);
    }

    @PostMapping("/mfa/disable")
    public Map<String, Object> disableMfa(@RequestBody MfaBody body) {
        authService.disableMfa(TenantContext.require().userId(), body.code());
        return Map.of("mfaEnabled", false);
    }

    private static String clientIp(HttpServletRequest request) {
        String fwd = request.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            return fwd.split(",")[0].trim();
        }
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }

    public record LoginBody(
            @NotBlank String tenantSlug,
            @NotBlank String email,
            @NotBlank String password,
            String mfaCode
    ) {
    }

    public record MfaBody(@NotBlank String code) {
    }
}
