package com.sentinelvoice.auth;

import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.audit.AuditLedgerService;
import com.sentinelvoice.email.EmailVerificationService;
import com.sentinelvoice.security.TenantContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class TenantRegistrationService {

    private static final Set<String> INDUSTRIES = Set.of(
            "BANKING", "INSURANCE", "GOVERNMENT", "TELECOM", "OTHER"
    );
    private static final Set<String> BLOCKED_PASSWORDS = Set.of(
            "password", "password123", "password1234", "changeme", "123456789012",
            "qwertyuiopas", "letmeinletmein", "adminadmin12"
    );
    private static final Pattern SLUG_SAFE = Pattern.compile("[^a-z0-9]+");

    private final PlatformAuthRepository platformAuthRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailVerificationService emailVerificationService;
    private final AuditLedgerService auditLedgerService;

    public TenantRegistrationService(
            PlatformAuthRepository platformAuthRepository,
            PasswordEncoder passwordEncoder,
            EmailVerificationService emailVerificationService,
            AuditLedgerService auditLedgerService
    ) {
        this.platformAuthRepository = platformAuthRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailVerificationService = emailVerificationService;
        this.auditLedgerService = auditLedgerService;
    }

    @Transactional
    public RegistrationResult register(RegisterRequest req) {
        validate(req);
        String slug = uniqueSlug(req.organisationName());
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String industry = req.industry().trim().toUpperCase(Locale.ROOT);
        String email = req.adminEmail().trim().toLowerCase(Locale.ROOT);
        String passwordHash = passwordEncoder.encode(req.password());

        TenantContext.runAsPlatform(() -> {
            platformAuthRepository.registerTenant(
                    tenantId,
                    req.organisationName().trim(),
                    slug,
                    industry,
                    req.region() == null ? null : req.region().trim(),
                    userId,
                    email,
                    passwordHash,
                    req.adminDisplayName().trim()
            );
            return null;
        });

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("slug", slug);
        payload.put("adminUserId", userId.toString());
        payload.put("adminEmail", email);
        payload.put("industry", industry);
        TenantContext.runAs(tenantId, () -> auditLedgerService.append(
                tenantId,
                null,
                AuditEventType.TENANT_REGISTERED,
                "SYSTEM",
                userId.toString(),
                payload
        ));

        String verifyLink = "https://sentinelvoice.local/verify?tenant=" + slug + "&email=" + email;
        emailVerificationService.sendVerificationLink(email, req.organisationName().trim(), verifyLink);

        return new RegistrationResult(tenantId, slug);
    }

    private void validate(RegisterRequest req) {
        if (req.organisationName() == null || req.organisationName().isBlank()) {
            throw new IllegalArgumentException("organisation name is required");
        }
        if (req.industry() == null || !INDUSTRIES.contains(req.industry().trim().toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("industry must be one of " + INDUSTRIES);
        }
        if (req.adminEmail() == null || !req.adminEmail().contains("@")) {
            throw new IllegalArgumentException("valid admin email is required");
        }
        if (req.adminDisplayName() == null || req.adminDisplayName().isBlank()) {
            throw new IllegalArgumentException("admin display name is required");
        }
        if (!Boolean.TRUE.equals(req.acceptedTerms())) {
            throw new IllegalArgumentException("terms must be accepted");
        }
        validatePassword(req.password());
    }

    static void validatePassword(String password) {
        if (password == null || password.length() < 12) {
            throw new IllegalArgumentException("password must be at least 12 characters");
        }
        if (BLOCKED_PASSWORDS.contains(password.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("password is too common");
        }
    }

    private String uniqueSlug(String name) {
        String base = SLUG_SAFE.matcher(name.trim().toLowerCase(Locale.ROOT)).replaceAll("-");
        base = base.replaceAll("^-+|-+$", "");
        if (base.isBlank()) {
            base = "tenant";
        }
        if (base.length() > 48) {
            base = base.substring(0, 48);
        }
        String candidate = base;
        int i = 2;
        while (slugTaken(candidate)) {
            candidate = base + "-" + i++;
        }
        return candidate;
    }

    private boolean slugTaken(String slug) {
        return Boolean.TRUE.equals(TenantContext.runAsPlatform(() -> platformAuthRepository.slugExists(slug)));
    }

    public record RegisterRequest(
            String organisationName,
            String industry,
            String region,
            String adminEmail,
            String adminDisplayName,
            String password,
            Boolean acceptedTerms
    ) {
    }

    public record RegistrationResult(UUID tenantId, String slug) {
    }
}
