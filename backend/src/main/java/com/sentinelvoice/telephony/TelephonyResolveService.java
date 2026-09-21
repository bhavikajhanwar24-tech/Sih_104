package com.sentinelvoice.telephony;

import com.sentinelvoice.directory.DirectoryMatch;
import com.sentinelvoice.directory.PhoneNormaliser;
import com.sentinelvoice.directory.entity.EmployeePhoneEntity;
import com.sentinelvoice.directory.repo.EmployeePhoneRepository;
import com.sentinelvoice.tenant.TenantEntity;
import com.sentinelvoice.tenant.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves dialed extensions / CLIs for AGI dialplan and directory provenance (F10).
 */
@Service
public class TelephonyResolveService {

    private final SipEndpointRepository endpointRepository;
    private final TrunkRepository trunkRepository;
    private final EmployeePhoneRepository phoneRepository;
    private final PhoneNormaliser phoneNormaliser;
    private final TenantRepository tenantRepository;

    public TelephonyResolveService(
            SipEndpointRepository endpointRepository,
            TrunkRepository trunkRepository,
            EmployeePhoneRepository phoneRepository,
            PhoneNormaliser phoneNormaliser,
            TenantRepository tenantRepository
    ) {
        this.endpointRepository = endpointRepository;
        this.trunkRepository = trunkRepository;
        this.phoneRepository = phoneRepository;
        this.phoneNormaliser = phoneNormaliser;
        this.tenantRepository = tenantRepository;
    }

    @Transactional(readOnly = true)
    public Optional<TelephonyModels.ExtensionResolveResult> resolveExtension(String extension) {
        if (extension == null || extension.isBlank()) {
            return Optional.empty();
        }
        String digits = phoneNormaliser.extensionDigits(extension);
        return endpointRepository.resolveExtensionCrossTenant(digits);
    }

    /**
     * Dial-time resolve: callee extension must belong to the same tenant as the caller
     * SIP endpoint (cross-tenant dials fail closed).
     */
    @Transactional(readOnly = true)
    public Optional<TelephonyModels.ExtensionResolveResult> resolveExtensionForCaller(
            String extension,
            String callerUsername
    ) {
        Optional<TelephonyModels.ExtensionResolveResult> callee = resolveExtension(extension);
        if (callee.isEmpty()) {
            return Optional.empty();
        }
        if (callerUsername == null || callerUsername.isBlank()) {
            // Softphones always present CHANNEL(endpoint); without it refuse to bridge.
            return Optional.empty();
        }
        Optional<TelephonyModels.ExtensionResolveResult> caller = resolveUsername(callerUsername);
        if (caller.isEmpty()) {
            return Optional.empty();
        }
        if (!caller.get().tenantId().equals(callee.get().tenantId())) {
            return Optional.empty();
        }
        return callee;
    }

    @Transactional(readOnly = true)
    public Optional<TelephonyModels.ExtensionResolveResult> resolveUsername(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        return endpointRepository.resolveUsernameCrossTenant(username.trim());
    }

    /**
     * Classify a CLI / dialed number within a tenant (INTERNAL_EXT | KNOWN_MOBILE |
     * EXTERNAL_UNKNOWN | SUSPECT_TRUNK).
     */
    @Transactional(readOnly = true)
    public TelephonyModels.NumberClassifyResult classifyNumber(UUID tenantId, String number) {
        if (number == null || number.isBlank()) {
            return new TelephonyModels.NumberClassifyResult(
                    TelephonyModels.NumberClass.EXTERNAL_UNKNOWN, tenantId, null, null, null, null
            );
        }

        if (phoneNormaliser.looksLikeExtension(number)) {
            String ext = phoneNormaliser.extensionDigits(number);
            Optional<TelephonyModels.SipEndpointView> ep = endpointRepository.findByExtension(tenantId, ext);
            if (ep.isPresent()) {
                return new TelephonyModels.NumberClassifyResult(
                        TelephonyModels.NumberClass.INTERNAL_EXT,
                        tenantId,
                        ep.get().employeeId(),
                        ep.get().id(),
                        null,
                        null
                );
            }
            Optional<EmployeePhoneEntity> byExt =
                    phoneRepository.findFirstByTenantIdAndSipExtension(tenantId, ext);
            if (byExt.isPresent()) {
                return new TelephonyModels.NumberClassifyResult(
                        TelephonyModels.NumberClass.INTERNAL_EXT,
                        tenantId,
                        byExt.get().getEmployeeId(),
                        null,
                        null,
                        null
                );
            }
        }

        String region = tenantRepository.findById(tenantId).map(TenantEntity::getRegion).orElse("IN");
        Optional<String> e164 = phoneNormaliser.toE164(number, region);
        if (e164.isPresent()) {
            Optional<EmployeePhoneEntity> byPhone =
                    phoneRepository.findByTenantIdAndE164(tenantId, e164.get());
            if (byPhone.isPresent()) {
                return new TelephonyModels.NumberClassifyResult(
                        TelephonyModels.NumberClass.KNOWN_MOBILE,
                        tenantId,
                        byPhone.get().getEmployeeId(),
                        null,
                        null,
                        null
                );
            }
        }

        Optional<TrunkRepository.MatchedPrefix> trunk =
                trunkRepository.findMatchingPrefix(tenantId, number);
        if (trunk.isPresent()) {
            return new TelephonyModels.NumberClassifyResult(
                    TelephonyModels.NumberClass.SUSPECT_TRUNK,
                    tenantId,
                    null,
                    null,
                    trunk.get().trunkId(),
                    trunk.get().prefix()
            );
        }

        return new TelephonyModels.NumberClassifyResult(
                TelephonyModels.NumberClass.EXTERNAL_UNKNOWN, tenantId, null, null, null, null
        );
    }

    public DirectoryMatch.NumberProvenance toProvenance(TelephonyModels.NumberClass classification) {
        return switch (classification) {
            case INTERNAL_EXT -> DirectoryMatch.NumberProvenance.INTERNAL_EXT;
            case KNOWN_MOBILE -> DirectoryMatch.NumberProvenance.KNOWN_MOBILE;
            case SUSPECT_TRUNK -> DirectoryMatch.NumberProvenance.SUSPECT_TRUNK;
            case EXTERNAL_UNKNOWN -> DirectoryMatch.NumberProvenance.EXTERNAL_UNKNOWN;
        };
    }
}
