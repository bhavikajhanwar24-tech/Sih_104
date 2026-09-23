package com.sentinelvoice.governance;

import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.audit.AuditLedgerService;
import com.sentinelvoice.auth.UserEntity;
import com.sentinelvoice.auth.UserRepository;
import com.sentinelvoice.security.TenantContext;
import com.sentinelvoice.tenant.TenantSettingsEntity;
import com.sentinelvoice.tenant.TenantSettingsRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * F14 — tenant kill-switch / emergency modes.
 * MONITOR_ONLY suppresses automatic actuation to advisory+notify.
 * SUSPEND_MONITORING stops analysing new/in-flight FeatureFrames immediately.
 */
@Service
public class EmergencyModeService {

    public static final String MONITOR_ONLY = "MONITOR_ONLY";
    public static final String SUSPEND_MONITORING = "SUSPEND_MONITORING";

    private final TenantSettingsRepository settingsRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLedgerService auditLedgerService;
    /** Hot path cache — invalidated on write. */
    private final ConcurrentHashMap<UUID, CachedMode> cache = new ConcurrentHashMap<>();

    public EmergencyModeService(
            TenantSettingsRepository settingsRepository,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            AuditLedgerService auditLedgerService
    ) {
        this.settingsRepository = settingsRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditLedgerService = auditLedgerService;
    }

    public record ModeSnapshot(
            String mode,
            Instant setAt,
            Instant expiresAt,
            UUID setBy,
            boolean active
    ) {
        public boolean monitorOnly() {
            return active && MONITOR_ONLY.equals(mode);
        }

        public boolean suspended() {
            return active && SUSPEND_MONITORING.equals(mode);
        }
    }

    private record CachedMode(ModeSnapshot snap, long loadedAtMs) {
    }

    public ModeSnapshot current(UUID tenantId) {
        CachedMode cached = cache.get(tenantId);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.loadedAtMs() < 2_000L) {
            return refreshIfExpired(tenantId, cached.snap());
        }
        TenantSettingsEntity s = settingsRepository.findById(tenantId).orElse(null);
        ModeSnapshot snap = fromEntity(s);
        cache.put(tenantId, new CachedMode(snap, now));
        return refreshIfExpired(tenantId, snap);
    }

    public boolean isMonitorOnly(UUID tenantId) {
        return current(tenantId).monitorOnly();
    }

    public boolean isSuspended(UUID tenantId) {
        return current(tenantId).suspended();
    }

    @Transactional
    public Map<String, Object> enable(String mode, String password, Integer ttlMinutes) {
        TenantContext ctx = TenantContext.require();
        requirePassword(ctx, password);
        if (!MONITOR_ONLY.equals(mode) && !SUSPEND_MONITORING.equals(mode)) {
            throw new IllegalArgumentException("mode must be MONITOR_ONLY or SUSPEND_MONITORING");
        }
        TenantSettingsEntity s = settingsRepository.findById(ctx.tenantId())
                .orElseThrow(() -> new IllegalStateException("tenant settings missing"));
        Instant now = Instant.now();
        s.setEmergencyMode(mode);
        s.setEmergencyModeSetAt(now);
        s.setEmergencyModeSetBy(ctx.userId());
        if (MONITOR_ONLY.equals(mode)) {
            int ttl = ttlMinutes != null && ttlMinutes > 0 ? ttlMinutes : s.getMonitorOnlyTtlMinutes();
            s.setMonitorOnlyTtlMinutes(ttl);
            s.setEmergencyModeExpiresAt(now.plus(ttl, ChronoUnit.MINUTES));
        } else {
            s.setEmergencyModeExpiresAt(null);
        }
        s.setUpdatedAt(now);
        settingsRepository.save(s);
        cache.remove(ctx.tenantId());
        Map<String, Object> change = Map.of(
                "area", "emergency",
                "before", Map.of("mode", (Object) null),
                "after", Map.of(
                        "mode", mode,
                        "expiresAt", s.getEmergencyModeExpiresAt() == null
                                ? null : s.getEmergencyModeExpiresAt().toString()
                )
        );
        auditLedgerService.append(
                ctx.tenantId(),
                null,
                AuditEventType.MODE_CHANGED,
                "USER",
                ctx.userId().toString(),
                Map.of(
                        "change", change,
                        "mode", mode,
                        "action", "ENABLE",
                        "legacyEvent", "EMERGENCY_MODE_ENABLED"
                )
        );
        return toBody(current(ctx.tenantId()));
    }

    @Transactional
    public Map<String, Object> disable(String password) {
        TenantContext ctx = TenantContext.require();
        requirePassword(ctx, password);
        TenantSettingsEntity s = settingsRepository.findById(ctx.tenantId())
                .orElseThrow(() -> new IllegalStateException("tenant settings missing"));
        String prev = s.getEmergencyMode();
        s.setEmergencyMode(null);
        s.setEmergencyModeExpiresAt(null);
        s.setEmergencyModeSetAt(null);
        s.setEmergencyModeSetBy(null);
        s.setUpdatedAt(Instant.now());
        settingsRepository.save(s);
        cache.remove(ctx.tenantId());
        auditLedgerService.append(
                ctx.tenantId(),
                null,
                AuditEventType.MODE_CHANGED,
                "USER",
                ctx.userId().toString(),
                Map.of(
                        "change", Map.of(
                                "area", "emergency",
                                "before", Map.of("mode", prev == null ? "" : prev),
                                "after", Map.of("mode", "")
                        ),
                        "action", "DISABLE",
                        "legacyEvent", "EMERGENCY_MODE_DISABLED"
                )
        );
        return toBody(current(ctx.tenantId()));
    }

    public Map<String, Object> status() {
        return toBody(current(TenantContext.require().tenantId()));
    }

    private ModeSnapshot refreshIfExpired(UUID tenantId, ModeSnapshot snap) {
        if (!snap.active() || snap.expiresAt() == null) {
            return snap;
        }
        if (snap.expiresAt().isAfter(Instant.now())) {
            return snap;
        }
        TenantSettingsEntity s = settingsRepository.findById(tenantId).orElse(null);
        if (s == null || s.getEmergencyMode() == null) {
            cache.remove(tenantId);
            return new ModeSnapshot(null, null, null, null, false);
        }
        s.setEmergencyMode(null);
        s.setEmergencyModeExpiresAt(null);
        s.setUpdatedAt(Instant.now());
        settingsRepository.save(s);
        cache.remove(tenantId);
        auditLedgerService.append(
                tenantId,
                null,
                AuditEventType.EMERGENCY_MODE_EXPIRED,
                "SYSTEM",
                "emergency-ttl",
                Map.of("mode", snap.mode() == null ? "" : snap.mode())
        );
        return new ModeSnapshot(null, null, null, null, false);
    }

    private void requirePassword(TenantContext ctx, String password) {
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("password re-entry required");
        }
        UserEntity user = userRepository.findByTenantIdAndId(ctx.tenantId(), ctx.userId())
                .orElseThrow(() -> new IllegalArgumentException("user not found"));
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new IllegalArgumentException("password confirmation failed");
        }
    }

    private static ModeSnapshot fromEntity(TenantSettingsEntity s) {
        if (s == null || s.getEmergencyMode() == null || s.getEmergencyMode().isBlank()) {
            return new ModeSnapshot(null, null, null, null, false);
        }
        boolean expired = s.getEmergencyModeExpiresAt() != null
                && s.getEmergencyModeExpiresAt().isBefore(Instant.now());
        if (expired) {
            return new ModeSnapshot(s.getEmergencyMode(), s.getEmergencyModeSetAt(),
                    s.getEmergencyModeExpiresAt(), s.getEmergencyModeSetBy(), false);
        }
        return new ModeSnapshot(
                s.getEmergencyMode(),
                s.getEmergencyModeSetAt(),
                s.getEmergencyModeExpiresAt(),
                s.getEmergencyModeSetBy(),
                true
        );
    }

    public static Map<String, Object> toBody(ModeSnapshot snap) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemaVersion", "2");
        m.put("active", snap.active());
        m.put("mode", snap.mode());
        m.put("setAt", snap.setAt() == null ? null : snap.setAt().toString());
        m.put("expiresAt", snap.expiresAt() == null ? null : snap.expiresAt().toString());
        m.put("setBy", snap.setBy() == null ? null : snap.setBy().toString());
        m.put("monitorOnly", snap.monitorOnly());
        m.put("suspended", snap.suspended());
        return m;
    }
}
