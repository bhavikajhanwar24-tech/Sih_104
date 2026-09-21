package com.sentinelvoice.telephony;

import com.sentinelvoice.directory.repo.EmployeeRepository;
import com.sentinelvoice.response.crypto.SecretBox;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;

/**
 * Provisions tenant SIP identities and syncs them into Asterisk PJSIP realtime.
 *
 * <p>Extension rule: {@code telephony_ordinal * 1000 + next 3-digit}
 * (e.g. ordinal 1 → 1001, 1002, …).
 */
@Service
public class SipProvisioningService {

    private static final char[] PASSWORD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789".toCharArray();

    private final SipEndpointRepository endpointRepository;
    private final AsteriskRealtimeSync realtimeSync;
    private final SecretBox secretBox;
    private final EmployeeRepository employeeRepository;
    private final Environment environment;
    private final boolean labMode;
    private final String sipExternalIp;
    private final SecureRandom random = new SecureRandom();

    public SipProvisioningService(
            SipEndpointRepository endpointRepository,
            AsteriskRealtimeSync realtimeSync,
            SecretBox secretBox,
            EmployeeRepository employeeRepository,
            Environment environment,
            @Value("${LAB_MODE:false}") boolean labMode,
            @Value("${SIP_EXTERNAL_IP:}") String sipExternalIp
    ) {
        this.endpointRepository = endpointRepository;
        this.realtimeSync = realtimeSync;
        this.secretBox = secretBox;
        this.employeeRepository = employeeRepository;
        this.environment = environment;
        this.labMode = labMode;
        this.sipExternalIp = sipExternalIp == null ? "" : sipExternalIp.trim();
    }

    @Transactional
    public TelephonyModels.SipEndpointProvisionResult createForEmployee(UUID tenantId, UUID employeeId) {
        employeeRepository.findByIdAndTenantId(employeeId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "employee not found"));
        if (endpointRepository.findByEmployee(tenantId, employeeId).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "employee already has a SIP endpoint");
        }
        String extension = allocateExtension(tenantId);
        String username = extension;
        String plaintext = randomPassword(16);
        String cipher = secretBox.encryptToBase64(plaintext);
        TelephonyModels.SipEndpointView view = endpointRepository.insert(
                tenantId, employeeId, extension, username, cipher, "ACTIVE"
        );
        realtimeSync.upsert(username, plaintext, mediaAddress());
        return new TelephonyModels.SipEndpointProvisionResult(view, plaintext);
    }

    /**
     * Lab-only attacker endpoint with spoofable CLI metadata. Refused under prod profile.
     */
    @Transactional
    public TelephonyModels.SipEndpointProvisionResult createLabAttacker(UUID tenantId, String label) {
        refuseIfProd();
        if (!labMode) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "LAB_MODE is not enabled");
        }
        String extension = allocateExtension(tenantId);
        String username = "lab-" + extension;
        String plaintext = randomPassword(16);
        String cipher = secretBox.encryptToBase64(plaintext);
        TelephonyModels.SipEndpointView view = endpointRepository.insert(
                tenantId, null, extension, username, cipher, "LAB_ATTACKER"
        );
        realtimeSync.upsert(username, plaintext, mediaAddress());
        return new TelephonyModels.SipEndpointProvisionResult(view, plaintext);
    }

    @Transactional
    public TelephonyModels.SipEndpointProvisionResult resetPassword(UUID tenantId, UUID endpointId) {
        TelephonyModels.SipEndpointView existing = endpointRepository.findById(tenantId, endpointId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "endpoint not found"));
        String plaintext = randomPassword(16);
        endpointRepository.updatePassword(tenantId, endpointId, secretBox.encryptToBase64(plaintext));
        realtimeSync.upsert(existing.username(), plaintext, mediaAddress());
        TelephonyModels.SipEndpointView refreshed = endpointRepository.findById(tenantId, endpointId).orElseThrow();
        return new TelephonyModels.SipEndpointProvisionResult(refreshed, plaintext);
    }

    @Transactional
    public void setStatus(UUID tenantId, UUID endpointId, String status) {
        if ("LAB_ATTACKER".equals(status)) {
            refuseIfProd();
            if (!labMode) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "LAB_MODE is not enabled");
            }
        }
        if (!Arrays.asList("ACTIVE", "DISABLED", "LAB_ATTACKER").contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid status");
        }
        TelephonyModels.SipEndpointView existing = endpointRepository.findById(tenantId, endpointId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "endpoint not found"));
        endpointRepository.updateStatus(tenantId, endpointId, status);
        if ("DISABLED".equals(status)) {
            realtimeSync.delete(existing.username());
        } else {
            String plaintext = secretBox.decryptFromBase64(
                    endpointRepository.findPasswordCiphertext(tenantId, endpointId).orElseThrow()
            );
            realtimeSync.upsert(existing.username(), plaintext, mediaAddress());
        }
    }

    @Transactional
    public void delete(UUID tenantId, UUID endpointId) {
        TelephonyModels.SipEndpointView existing = endpointRepository.findById(tenantId, endpointId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "endpoint not found"));
        realtimeSync.delete(existing.username());
        endpointRepository.delete(tenantId, endpointId);
    }

    private String allocateExtension(UUID tenantId) {
        int ordinal = endpointRepository.telephonyOrdinal(tenantId);
        int blockBase = ordinal * 1000;
        int max = endpointRepository.maxSuffixInBlock(tenantId, blockBase);
        int next = Math.max(max + 1, blockBase + 1);
        if (next >= blockBase + 1000) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "extension block exhausted for tenant");
        }
        return Integer.toString(next);
    }

    private void refuseIfProd() {
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "lab attacker endpoints refuse in prod");
            }
        }
    }

    private String mediaAddress() {
        return sipExternalIp.isBlank() ? null : sipExternalIp;
    }

    private String randomPassword(int len) {
        char[] buf = new char[len];
        for (int i = 0; i < len; i++) {
            buf[i] = PASSWORD_ALPHABET[random.nextInt(PASSWORD_ALPHABET.length)];
        }
        return new String(buf);
    }
}
