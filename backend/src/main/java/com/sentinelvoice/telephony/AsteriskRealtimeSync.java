package com.sentinelvoice.telephony;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Syncs tenant {@code sip_endpoints} into Asterisk PJSIP REALTIME tables
 * ({@code asterisk.ps_auths}, {@code asterisk.ps_aors}, {@code asterisk.ps_endpoints}).
 *
 * <p>Writes to the primary JDBC datasource (Supabase / app DB). When
 * {@code ASTERISK_SYNC_URL} is set, also mirrors into that DB so a local
 * Asterisk container can read realtime without TLS to the cloud pooler.
 */
@Service
public class AsteriskRealtimeSync {

    private static final Logger log = LoggerFactory.getLogger(AsteriskRealtimeSync.class);

    private final JdbcTemplate jdbc;
    private final JdbcTemplate asteriskJdbc;

    public AsteriskRealtimeSync(
            JdbcTemplate jdbc,
            @Autowired(required = false) AsteriskMirrorJdbc asteriskMirror
    ) {
        this.jdbc = jdbc;
        this.asteriskJdbc = asteriskMirror != null ? asteriskMirror.jdbc() : null;
    }

    /**
     * Upsert realtime rows for an endpoint. {@code plaintextPassword} is written
     * to ps_auths (Asterisk needs cleartext for digest auth).
     */
    public void upsert(String username, String plaintextPassword, String mediaAddress) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username required");
        }
        writeUpsert(jdbc, username, plaintextPassword, mediaAddress);
        if (asteriskJdbc != null && asteriskJdbc != jdbc) {
            try {
                writeUpsert(asteriskJdbc, username, plaintextPassword, mediaAddress);
            } catch (Exception e) {
                log.warn("asterisk_sync_mirror_failed user={} err={}", username, e.toString());
            }
        }
    }

    public void delete(String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        writeDelete(jdbc, username);
        if (asteriskJdbc != null && asteriskJdbc != jdbc) {
            try {
                writeDelete(asteriskJdbc, username);
            } catch (Exception e) {
                log.warn("asterisk_sync_delete_failed user={} err={}", username, e.toString());
            }
        }
    }

    public boolean healthCheck() {
        JdbcTemplate probe = asteriskJdbc != null ? asteriskJdbc : jdbc;
        try {
            Integer n = probe.queryForObject("SELECT count(*)::int FROM asterisk.ps_endpoints", Integer.class);
            return n != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static void writeUpsert(
            JdbcTemplate db,
            String username,
            String plaintextPassword,
            String mediaAddress
    ) {
        String id = username.trim();
        db.update(
                """
                INSERT INTO asterisk.ps_auths (id, auth_type, password, username)
                VALUES (?, 'userpass', ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                  auth_type = EXCLUDED.auth_type,
                  password = EXCLUDED.password,
                  username = EXCLUDED.username
                """,
                id, plaintextPassword, id
        );
        db.update(
                """
                INSERT INTO asterisk.ps_aors (id, max_contacts, remove_existing, qualify_frequency)
                VALUES (?, 1, 'yes', 30)
                ON CONFLICT (id) DO UPDATE SET
                  max_contacts = EXCLUDED.max_contacts,
                  remove_existing = EXCLUDED.remove_existing,
                  qualify_frequency = EXCLUDED.qualify_frequency
                """,
                id
        );
        db.update(
                """
                INSERT INTO asterisk.ps_endpoints (
                  id, transport, aors, auth, context, disallow, allow,
                  direct_media, rtp_symmetric, force_rport, rewrite_contact,
                  ice_support, media_use_received_transport, media_encryption,
                  inband_progress, media_address
                ) VALUES (
                  ?, 'transport-udp', ?, ?, 'sentinel', 'all', 'ulaw',
                  'no', 'yes', 'yes', 'yes',
                  'no', 'yes', 'no',
                  'no', ?
                )
                ON CONFLICT (id) DO UPDATE SET
                  transport = EXCLUDED.transport,
                  aors = EXCLUDED.aors,
                  auth = EXCLUDED.auth,
                  context = EXCLUDED.context,
                  disallow = EXCLUDED.disallow,
                  allow = EXCLUDED.allow,
                  direct_media = EXCLUDED.direct_media,
                  rtp_symmetric = EXCLUDED.rtp_symmetric,
                  force_rport = EXCLUDED.force_rport,
                  rewrite_contact = EXCLUDED.rewrite_contact,
                  ice_support = EXCLUDED.ice_support,
                  media_use_received_transport = EXCLUDED.media_use_received_transport,
                  media_encryption = EXCLUDED.media_encryption,
                  inband_progress = EXCLUDED.inband_progress,
                  media_address = EXCLUDED.media_address
                """,
                id, id, id, blankToNull(mediaAddress)
        );
    }

    private static void writeDelete(JdbcTemplate db, String username) {
        String id = username.trim();
        db.update("DELETE FROM asterisk.ps_endpoints WHERE id = ?", id);
        db.update("DELETE FROM asterisk.ps_auths WHERE id = ?", id);
        db.update("DELETE FROM asterisk.ps_aors WHERE id = ?", id);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
