package com.sentinelvoice.auth;

import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.audit.AuditLedgerService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class UserManagementService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLedgerService auditLedgerService;

    public UserManagementService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            AuditLedgerService auditLedgerService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditLedgerService = auditLedgerService;
    }

    @Transactional(readOnly = true)
    public List<UserView> list(UUID tenantId) {
        return userRepository.findByTenantIdOrderByCreatedAtAsc(tenantId).stream()
                .map(UserView::from)
                .toList();
    }

    @Transactional
    public UserView invite(UUID tenantId, UUID actorId, InviteRequest req) {
        TenantRegistrationService.validatePassword(req.temporaryPassword());
        Role role = Role.from(req.role());
        String email = req.email().trim().toLowerCase(Locale.ROOT);
        if (userRepository.findByTenantIdAndEmailIgnoreCase(tenantId, email).isPresent()) {
            throw new IllegalArgumentException("email already exists in tenant");
        }
        Instant now = Instant.now();
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setTenantId(tenantId);
        user.setEmail(email);
        user.setDisplayName(req.displayName().trim());
        user.setRole(role.name());
        user.setPasswordHash(passwordEncoder.encode(req.temporaryPassword()));
        user.setStatus("ACTIVE");
        user.setMfaEnabled(false);
        user.setTokenVersion(0);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userRepository.save(user);
        audit(tenantId, actorId, AuditEventType.USER_CREATED, Map.of(
                "userId", user.getId().toString(),
                "email", email,
                "role", role.name()
        ));
        return UserView.from(user);
    }

    @Transactional
    public UserView changeRole(UUID tenantId, UUID actorId, UUID targetUserId, String newRole) {
        if (actorId.equals(targetUserId)) {
            throw new IllegalArgumentException("cannot change your own role via this endpoint");
        }
        UserEntity user = requireTenantUser(tenantId, targetUserId);
        Role next = Role.from(newRole);
        if (Role.TENANT_ADMIN.name().equals(user.getRole())
                && next != Role.TENANT_ADMIN
                && userRepository.countActiveAdmins(tenantId) <= 1) {
            throw new IllegalArgumentException("cannot demote the last TENANT_ADMIN");
        }
        user.setRole(next.name());
        user.setTokenVersion(user.getTokenVersion() + 1);
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);
        audit(tenantId, actorId, AuditEventType.USER_UPDATED, Map.of(
                "userId", user.getId().toString(),
                "role", next.name()
        ));
        return UserView.from(user);
    }

    @Transactional
    public UserView disable(UUID tenantId, UUID actorId, UUID targetUserId) {
        if (actorId.equals(targetUserId)) {
            throw new IllegalArgumentException("cannot disable yourself");
        }
        UserEntity user = requireTenantUser(tenantId, targetUserId);
        if (Role.TENANT_ADMIN.name().equals(user.getRole())
                && userRepository.countActiveAdmins(tenantId) <= 1) {
            throw new IllegalArgumentException("cannot disable the last TENANT_ADMIN");
        }
        user.setStatus("DISABLED");
        user.setTokenVersion(user.getTokenVersion() + 1);
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);
        audit(tenantId, actorId, AuditEventType.USER_DISABLED, Map.of("userId", user.getId().toString()));
        return UserView.from(user);
    }

    private UserEntity requireTenantUser(UUID tenantId, UUID userId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("user not found"));
        if (!tenantId.equals(user.getTenantId())) {
            throw new IllegalArgumentException("user not in tenant");
        }
        return user;
    }

    private void audit(UUID tenantId, UUID actorId, AuditEventType type, Map<String, Object> payload) {
        Map<String, Object> body = new LinkedHashMap<>(payload);
        auditLedgerService.append(tenantId, null, type, "USER", actorId.toString(), body);
    }

    public record InviteRequest(String email, String displayName, String role, String temporaryPassword) {
    }

    public record UserView(
            UUID id,
            String email,
            String displayName,
            String role,
            String status,
            boolean mfaEnabled,
            Instant lastLoginAt,
            Instant createdAt
    ) {
        static UserView from(UserEntity u) {
            return new UserView(
                    u.getId(),
                    u.getEmail(),
                    u.getDisplayName(),
                    u.getRole(),
                    u.getStatus(),
                    u.isMfaEnabled(),
                    u.getLastLoginAt(),
                    u.getCreatedAt()
            );
        }
    }
}
