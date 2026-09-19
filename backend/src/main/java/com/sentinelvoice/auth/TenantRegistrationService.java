package com.sentinelvoice.auth;

import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.audit.AuditLedgerService;
import com.sentinelvoice.email.EmailVerificationService;
import com.sentinelvoice.tenant.TenantEntity;
import com.sentinelvoice.tenant.TenantRepository;
import com.sentinelvoice.tenant.TenantSettingsEntity;
import com.sentinelvoice.tenant.TenantSettingsRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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

    private final TenantRepository tenantRepository;
    private final TenantSettingsRepository settingsRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLedgerService auditLedgerService;
    private final EmailVerificationService emailVerificationService;

    public TenantRegistrationService(
            TenantRepository tenantRepository,
            TenantSettingsRepository settingsRepository,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            AuditLedgerService auditLedgerService,
            EmailVerificationService emailVerificationService
    ) {
        this.tenantRepository = tenantRepository;
        this.settingsRepository = settingsRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditLedgerService = auditLedgerService;
        this.emailVerificationService = emailVerificationService;
    }

    @Transactional
    public RegistrationResult register(RegisterRequest req) {
        validate(req);
        String slug = uniqueSlug(req.organisationName());
        Instant now = Instant.now();

        UUID tenantId = UUID.randomUUID();
        TenantEntity tenant = new TenantEntity();
        tenant.setId(tenantId);
        tenant.setName(req.organisationName().trim());
        tenant.setSlug(slug);
        tenant.setIndustry(req.industry().trim().toUpperCase(Locale.ROOT));
        tenant.setRegion(req.region() == null ? null : req.region().trim());
        tenant.setStatus("ACTIVE");
        tenant.setCreatedAt(now);
        tenantRepository.save(tenant);

        TenantSettingsEntity settings = new TenantSettingsEntity();
        settings.setTenantId(tenantId);
        settings.setRetentionDays(90);
        settings.setAllowExternalLlm(false);
        settings.setLlmFailPolicy("CONTINUE_RULES_ONLY");
        settings.setConsentNoticeText("Default SentinelVoice consent notice.");
        settings.setExtras(Map.of());
        settings.setCreatedAt(now);
        settings.setUpdatedAt(now);
        settingsRepository.save(settings);

        UUID userId = UUID.randomUUID();
        UserEntity admin = new UserEntity();
        admin.setId(userId);
        admin.setTenantId(tenantId);
        admin.setEmail(req.adminEmail().trim().toLowerCase(Locale.ROOT));
        admin.setPasswordHash(passwordEncoder.encode(req.password()));
        admin.setDisplayName(req.adminDisplayName().trim());
        admin.setRole(Role.TENANT_ADMIN.name());
        admin.setMfaEnabled(false);
        admin.setStatus("ACTIVE");
        admin.setTokenVersion(0);
        admin.setCreatedAt(now);
        admin.setUpdatedAt(now);
        userRepository.save(admin);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("slug", slug);
        payload.put("adminUserId", userId.toString());
        payload.put("adminEmail", admin.getEmail());
        payload.put("industry", tenant.getIndustry());
        auditLedgerService.append(
                tenantId,
                null,
                AuditEventType.TENANT_REGISTERED,
                "SYSTEM",
                userId.toString(),
                payload
        );

        String verifyLink = "https://sentinelvoice.local/verify?tenant=" + slug + "&email=" + admin.getEmail();
        emailVerificationService.sendVerificationLink(admin.getEmail(), tenant.getName(), verifyLink);

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
        while (tenantRepository.existsBySlugIgnoreCase(candidate)) {
            candidate = base + "-" + i++;
        }
        return candidate;
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
