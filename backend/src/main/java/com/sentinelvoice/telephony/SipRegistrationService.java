package com.sentinelvoice.telephony;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads Asterisk {@code ps_contacts} (local mirror when {@code ASTERISK_SYNC_URL} is set)
 * so Directory / Settings can show Registered without hard-coding IPs.
 */
@Service
public class SipRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(SipRegistrationService.class);

    private final JdbcTemplate jdbc;
    private final JdbcTemplate contactsJdbc;

    public SipRegistrationService(
            JdbcTemplate jdbc,
            @Autowired(required = false) AsteriskMirrorJdbc asteriskMirror
    ) {
        this.jdbc = jdbc;
        this.contactsJdbc = asteriskMirror != null ? asteriskMirror.jdbc() : jdbc;
    }

    /**
     * @return username → approximate last-seen instant when a non-expired contact exists
     */
    public Map<String, Instant> registeredAtByUsername(Collection<String> usernames) {
        Map<String, Instant> out = new LinkedHashMap<>();
        if (usernames == null || usernames.isEmpty()) {
            return out;
        }
        List<String> names = usernames.stream().filter(u -> u != null && !u.isBlank()).distinct().toList();
        if (names.isEmpty()) {
            return out;
        }
        try {
            long nowSec = Instant.now().getEpochSecond();
            String placeholders = String.join(",", names.stream().map(n -> "?").toList());
            Object[] args = names.toArray();
            List<Map<String, Object>> rows = contactsJdbc.queryForList(
                    """
                    SELECT endpoint, MAX(expiration_time) AS exp
                    FROM asterisk.ps_contacts
                    WHERE endpoint IN (%s)
                    GROUP BY endpoint
                    """.formatted(placeholders),
                    args
            );
            for (Map<String, Object> row : rows) {
                String endpoint = row.get("endpoint") == null ? null : String.valueOf(row.get("endpoint"));
                Object expObj = row.get("exp");
                if (endpoint == null || expObj == null) {
                    continue;
                }
                long exp = ((Number) expObj).longValue();
                if (exp > nowSec) {
                    // Contact is live; treat "now" as last seen for the green badge window.
                    out.put(endpoint, Instant.now());
                } else if (exp > 0) {
                    out.put(endpoint, Instant.ofEpochSecond(exp));
                }
            }
        } catch (Exception e) {
            log.debug("sip_registration_probe_failed err={}", e.toString());
        }
        return out;
    }

    /** Persist last_registered_at for UI when contacts are present. */
    public void touchRegistered(UUID tenantId, String username, Instant at) {
        if (tenantId == null || username == null || username.isBlank() || at == null) {
            return;
        }
        try {
            jdbc.update(
                    """
                    UPDATE sip_endpoints
                    SET last_registered_at = ?, updated_at = now()
                    WHERE tenant_id = ? AND username = ?
                    """,
                    java.sql.Timestamp.from(at),
                    tenantId,
                    username
            );
        } catch (Exception e) {
            log.debug("sip_registration_touch_failed user={} err={}", username, e.toString());
        }
    }

    public Map<String, Instant> refreshAndProbe(UUID tenantId, List<TelephonyModels.SipEndpointView> endpoints) {
        Map<String, Instant> probed = registeredAtByUsername(
                endpoints.stream().map(TelephonyModels.SipEndpointView::username).toList()
        );
        for (TelephonyModels.SipEndpointView e : endpoints) {
            Instant at = probed.get(e.username());
            if (at != null) {
                touchRegistered(tenantId, e.username(), at);
            }
        }
        // Merge DB column for endpoints without a live contact
        Map<String, Instant> merged = new HashMap<>(probed);
        for (TelephonyModels.SipEndpointView e : endpoints) {
            if (!merged.containsKey(e.username()) && e.lastRegisteredAt() != null) {
                merged.put(e.username(), e.lastRegisteredAt());
            }
        }
        return merged;
    }
}
