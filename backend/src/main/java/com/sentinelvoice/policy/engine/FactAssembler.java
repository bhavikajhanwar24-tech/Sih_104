package com.sentinelvoice.policy.engine;

import com.sentinelvoice.context.model.RelationshipAssessment;
import com.sentinelvoice.directory.DirectoryMatch;
import com.sentinelvoice.directory.DirectoryService;
import com.sentinelvoice.model.Ask;
import com.sentinelvoice.model.CallSession;
import com.sentinelvoice.model.FeatureFrame;
import com.sentinelvoice.model.LinguisticFamily;
import com.sentinelvoice.tenant.TenantSettingsEntity;
import com.sentinelvoice.tenant.TenantSettingsRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Builds a {@link FactSet} from directory, relationship, session, FeatureFrame, and cross-channel facts (F7).
 * Caller-stated linguistic facts are marked {@code assertedByCaller=true} and mirrored as
 * {@code <path>.assertedByCaller} booleans so rules never treat a claim as verified truth.
 */
@Service
public class FactAssembler {

    private final DirectoryService directoryService;
    private final TenantSettingsRepository tenantSettingsRepository;
    private final CrossChannelFactService crossChannelFactService;
    private final PolicyEngineProperties props;

    public FactAssembler(
            DirectoryService directoryService,
            TenantSettingsRepository tenantSettingsRepository,
            CrossChannelFactService crossChannelFactService,
            PolicyEngineProperties props
    ) {
        this.directoryService = directoryService;
        this.tenantSettingsRepository = tenantSettingsRepository;
        this.crossChannelFactService = crossChannelFactService;
        this.props = props;
    }

    public FactSet assemble(
            UUID tenantId,
            CallSession session,
            FeatureFrame frame,
            DirectoryMatch directoryMatch,
            RelationshipAssessment relationship
    ) {
        Instant now = Instant.now();
        FactSet.Builder b = FactSet.builder();

        DirectoryMatch match = directoryMatch;
        if (match == null && session != null) {
            String claimedRole = frame != null && frame.linguistic() != null
                    ? frame.linguistic().claimedRole() : null;
            String claimedName = frame != null && frame.linguistic() != null
                    ? frame.linguistic().claimedIdentity() : null;
            match = directoryService.resolve(tenantId, session.getCallerId(), claimedName, claimedRole);
        }
        if (match != null) {
            putDirectory(b, match, now);
        }

        if (relationship != null) {
            b.put("relationship.isFirstContact",
                    FactValue.of(relationship.firstContact(), "relationship", 1.0, now, false));
            // interactionCount365d used as proxy when days-since is unavailable
            if (!relationship.firstContact() && relationship.interactionCount365d() > 0) {
                b.put("relationship.daysSinceLastContact",
                        FactValue.of(0, "relationship", 0.5, now, false));
            }
        }

        if (session != null) {
            long durSec = Math.max(0L, (now.toEpochMilli() - session.getCreatedAt().toEpochMilli()) / 1000L);
            b.put("session.durationSec", FactValue.of(durSec, "session", 1.0, now, false));
        }

        if (frame != null) {
            putVoice(b, frame, now);
            putLinguistic(b, frame.linguistic(), now);
        }

        putTime(b, tenantId, now);
        putCross(b, tenantId, match, now);

        return b.build();
    }

    /**
     * Simulation / API path: coerce a flat map of fact path → value into a FactSet.
     * Values are marked source=simulation, assertedByCaller=false unless the path ends with
     * {@code .assertedByCaller}.
     */
    public FactSet fromSimulationMap(Map<String, Object> raw) {
        FactSet.Builder b = FactSet.builder();
        Instant now = Instant.now();
        if (raw == null) {
            return b.build();
        }
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            boolean asserted = e.getKey().endsWith(".assertedByCaller")
                    || Boolean.TRUE.equals(raw.get(e.getKey() + ".assertedByCaller"));
            b.put(e.getKey(), FactValue.of(e.getValue(), "simulation", 1.0, now, asserted));
        }
        return b.build();
    }

    private void putDirectory(FactSet.Builder b, DirectoryMatch match, Instant now) {
        if (match.matchType() != null) {
            b.put("caller.matchType",
                    FactValue.of(match.matchType().name(), "directory", match.confidence(), now, false));
        }
        if (match.numberProvenance() != null) {
            b.put("caller.numberProvenance",
                    FactValue.of(match.numberProvenance().name(), "directory", match.confidence(), now, false));
        }
        if (match.status() != null) {
            b.put("caller.status", FactValue.of(match.status(), "directory", match.confidence(), now, false));
        }
        if (match.roleKey() != null) {
            b.put("caller.matchedRole", FactValue.of(match.roleKey(), "directory", match.confidence(), now, false));
        }
        b.put("caller.isHighAuthority",
                FactValue.of(match.highAuthority(), "directory", match.confidence(), now, false));
        Double limit = authorityLimit(match);
        if (limit != null) {
            b.put("caller.authorityLimitInr",
                    FactValue.of(limit, "directory", match.confidence(), now, false));
        }
    }

    private static Double authorityLimit(DirectoryMatch match) {
        if (match.authority() == null || match.authority().isEmpty()) {
            return null;
        }
        Double max = null;
        for (Map<String, Object> row : match.authority()) {
            Object v = row.get("maxAmountInr");
            if (v == null) {
                v = row.get("max_amount_inr");
            }
            if (v instanceof Number n) {
                max = max == null ? n.doubleValue() : Math.max(max, n.doubleValue());
            }
        }
        return max;
    }

    private void putVoice(FactSet.Builder b, FeatureFrame frame, Instant now) {
        FeatureFrame.VoiceFamily voice = frame.voice();
        if (voice != null && voice.available() && voice.spoofProbability() != null) {
            b.put("voice.syntheticScore",
                    FactValue.of(voice.spoofProbability(), "voice", voice.confidence(), now, false));
        }
        FeatureFrame.SpeakerFamily speaker = frame.speaker();
        if (speaker != null && speaker.available() && speaker.cosineSimilarity() != null) {
            // Mismatch when cosine is low (below typical match band)
            boolean mismatch = speaker.cosineSimilarity() < 0.55;
            b.put("voice.speakerMismatch",
                    FactValue.of(mismatch, "speaker", speaker.cosineSimilarity(), now, false));
        }
    }

    private void putLinguistic(FactSet.Builder b, LinguisticFamily ling, Instant now) {
        if (ling == null || !ling.available()) {
            return;
        }
        Double conf = 0.8;
        if (ling.claimedRole() != null && !ling.claimedRole().isBlank()) {
            putCallerAsserted(b, "caller.claimedRole", ling.claimedRole().trim(), now, conf);
        }
        if (ling.urgency() != null) {
            b.put("ask.urgencyLevel",
                    FactValue.of(ling.urgency(), "linguistic", conf, now, true));
        }
        if (ling.secrecy() != null) {
            b.put("ask.secrecyRequested",
                    FactValue.of(ling.secrecy() >= 0.5, "linguistic", ling.secrecy(), now, true));
        }
        if (ling.authorityInvocation() != null) {
            b.put("ask.authorityClaimed",
                    FactValue.of(ling.authorityInvocation() >= 0.5, "linguistic",
                            ling.authorityInvocation(), now, true));
        }
        Ask ask = ling.ask();
        if (ask != null) {
            if (ask.type() != null && !ask.type().isBlank()) {
                putCallerAsserted(b, "ask.type", normalizeAskType(ask.type()), now, conf);
            }
            if (ask.amount() != null) {
                putCallerAsserted(b, "ask.amountInr", ask.amount(), now, conf);
            }
            if (ask.beneficiaryHint() != null && !ask.beneficiaryHint().isBlank()) {
                // Presence of a hint ≠ verified; leave beneficiaryVerified unknown
                b.put("ask.beneficiaryKnown",
                        FactValue.of(false, "linguistic", conf, now, true));
            }
        }
        // Credential ask types
        String type = ask == null || ask.type() == null ? null : normalizeAskType(ask.type());
        if ("OTP_SHARE".equals(type) || "PIN_SHARE".equals(type) || "PASSWORD_RESET".equals(type)) {
            b.put("ask.sharesCredential", FactValue.of(true, "linguistic", conf, now, true));
        }
    }

    private static void putCallerAsserted(
            FactSet.Builder b,
            String path,
            Object value,
            Instant now,
            Double conf
    ) {
        b.put(path, FactValue.of(value, "linguistic", conf, now, true));
        b.put(path + ".assertedByCaller", FactValue.of(true, "linguistic", 1.0, now, false));
    }

    private static String normalizeAskType(String raw) {
        String t = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        return switch (t) {
            case "WIRE", "TRANSFER", "PAYMENT", "WIRETRANSFER" -> "WIRE_TRANSFER";
            case "OTP", "OTPSHARE" -> "OTP_SHARE";
            case "PIN", "PINSHARE" -> "PIN_SHARE";
            default -> t;
        };
    }

    private void putTime(FactSet.Builder b, UUID tenantId, Instant now) {
        ZoneId zone = ZoneId.of("Asia/Kolkata");
        if (tenantId != null) {
            zone = tenantSettingsRepository.findById(tenantId)
                    .map(TenantSettingsEntity::getTimezone)
                    .map(tz -> {
                        try {
                            return ZoneId.of(tz);
                        } catch (Exception e) {
                            return ZoneId.of("Asia/Kolkata");
                        }
                    })
                    .orElse(zone);
        }
        ZonedDateTime zdt = now.atZone(zone);
        int hour = zdt.getHour();
        b.put("time.hourLocal", FactValue.of(hour, "clock", 1.0, now, false));
        boolean biz = hour >= props.businessHourStart() && hour < props.businessHourEnd();
        b.put("time.isBusinessHours", FactValue.of(biz, "clock", 1.0, now, false));
    }

    private void putCross(FactSet.Builder b, UUID tenantId, DirectoryMatch match, Instant now) {
        CrossChannelFactService.CrossFacts cross = crossChannelFactService.factsFor(
                tenantId,
                match == null || match.matchedEmployeeId() == null
                        ? null
                        : match.matchedEmployeeId().toString()
        );
        b.put("cross.recentEmailFromSameIdentity",
                FactValue.of(cross.recentEmailFromSameIdentity(), "cross_channel", 1.0, now, false));
        b.put("cross.recentSmsLinkClicked",
                FactValue.of(cross.recentSmsLinkClicked(), "cross_channel", 1.0, now, false));
    }
}
