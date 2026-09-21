package com.sentinelvoice.telephony;

import com.sentinelvoice.config.SentinelProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * F10 telephony health — explains each failing link with a fix hint (no secrets).
 */
@Service
public class TelephonyHealthService {

    private static final Logger log = LoggerFactory.getLogger(TelephonyHealthService.class);

    private static final String[] REALTIME_TABLES = {
            "asterisk.ps_endpoints",
            "asterisk.ps_auths",
            "asterisk.ps_aors"
    };

    private final JdbcTemplate jdbc;
    private final JdbcTemplate asteriskJdbc;
    private final SentinelProperties properties;
    private final String sipExternalIp;
    private final boolean labMode;
    private final String amiHost;
    private final int amiPort;
    private final String amiUser;
    private final String amiSecret;

    public TelephonyHealthService(
            JdbcTemplate jdbc,
            @Autowired(required = false) AsteriskMirrorJdbc asteriskMirror,
            SentinelProperties properties,
            @Value("${SIP_EXTERNAL_IP:}") String sipExternalIp,
            @Value("${LAB_MODE:false}") boolean labMode,
            @Value("${ASTERISK_AMI_HOST:127.0.0.1}") String amiHost,
            @Value("${ASTERISK_AMI_PORT:5038}") int amiPort,
            @Value("${ASTERISK_AMI_USER:sentinel}") String amiUser,
            @Value("${ASTERISK_AMI_SECRET:sentineldemo}") String amiSecret
    ) {
        this.jdbc = jdbc;
        this.asteriskJdbc = asteriskMirror != null ? asteriskMirror.jdbc() : null;
        this.properties = properties;
        this.sipExternalIp = sipExternalIp == null ? "" : sipExternalIp.trim();
        this.labMode = labMode;
        this.amiHost = amiHost;
        this.amiPort = amiPort;
        this.amiUser = amiUser;
        this.amiSecret = amiSecret;
    }

    public Map<String, Object> diagnose(int tenantEndpointCount, int activeChannels) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> checks = new ArrayList<>();

        Map<String, Object> postgres = checkPostgresRealtime();
        checks.add(postgres);

        Map<String, Object> asteriskDb = checkAsteriskViaAmi();
        checks.add(asteriskDb);

        Map<String, Object> ari = checkAri();
        checks.add(ari);

        Map<String, Object> sipIp = checkSipExternalIp();
        checks.add(sipIp);

        boolean allOk = checks.stream().allMatch(c -> Boolean.TRUE.equals(c.get("ok")));
        out.put("ok", allOk);
        out.put("realtimeReachable", Boolean.TRUE.equals(postgres.get("ok")));
        out.put("mode", "PJSIP_REALTIME");
        out.put("sipExternalIp", sipExternalIp.isBlank() ? null : sipExternalIp);
        out.put("labMode", labMode);
        out.put("endpointCount", tenantEndpointCount);
        out.put("activeChannels", activeChannels);
        out.put("audioSocketConfigured", true);
        out.put("checks", checks);
        out.put("summary", buildSummary(checks));
        return out;
    }

    private Map<String, Object> checkPostgresRealtime() {
        Map<String, Object> c = base("postgresRealtime", "Backend → Postgres asterisk.ps_*");
        JdbcTemplate probe = asteriskJdbc != null ? asteriskJdbc : jdbc;
        String which = asteriskJdbc != null ? "ASTERISK_SYNC_URL (local mirror)" : "primary spring.datasource (app DB)";
        c.put("probe", which);
        c.put("tables", List.of(REALTIME_TABLES));
        try {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (String table : REALTIME_TABLES) {
                Integer n = probe.queryForObject("SELECT count(*)::int FROM " + table, Integer.class);
                counts.put(table, n == null ? 0 : n);
            }
            c.put("ok", true);
            c.put("counts", counts);
            c.put("detail", "SELECT count(*) succeeded on " + String.join(", ", REALTIME_TABLES));
        } catch (Exception e) {
            String msg = safeMsg(e);
            c.put("ok", false);
            c.put("error", msg);
            c.put("fixHint", asteriskJdbc == null
                    ? "Ensure V017 applied (schema asterisk + ps_endpoints/ps_auths/ps_aors) and sv_app has GRANT on asterisk.*. Or set ASTERISK_SYNC_URL to local Docker Postgres and restart backend."
                    : "Local Asterisk mirror DB unreachable. Check ASTERISK_SYNC_URL (jdbc:postgresql://127.0.0.1:15432/sentinelvoice), seed .sv-run/seed-asterisk-local.sql, and that postgres container is healthy.");
            log.warn("telephony_health postgresRealtime failed probe={} err={}", which, msg);
        }
        return c;
    }

    private Map<String, Object> checkAsteriskViaAmi() {
        Map<String, Object> c = base("asteriskRealtime", "Asterisk → DB (AMI: pjsip show endpoints)");
        c.put("amiHost", amiHost);
        c.put("amiPort", amiPort);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(amiHost, amiPort), 2000);
            socket.setSoTimeout(4000);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            // Banner is typically one line ("Asterisk Call Manager/x.y") without a trailing blank.
            String banner = in.readLine();
            c.put("amiBanner", banner == null ? "" : banner.trim());
            out.print("Action: Login\r\nUsername: " + amiUser + "\r\nSecret: " + amiSecret + "\r\n\r\n");
            out.flush();
            String login = readAmiUntilEmpty(in);
            if (!login.toLowerCase().contains("success")) {
                c.put("ok", false);
                c.put("error", "AMI login failed (check ASTERISK_AMI_USER/SECRET; no secret logged)");
                c.put("fixHint", "manager.conf [sentinel] must match ASTERISK_AMI_*; publish 127.0.0.1:5038:5038 when backend runs on the host.");
                log.warn("telephony_health asteriskRealtime AMI login failed host={}:{}", amiHost, amiPort);
                return c;
            }
            out.print("Action: Command\r\nCommand: pjsip show endpoints\r\n\r\n");
            out.flush();
            String cmd = readAmiUntilEmpty(in);
            // Command responses can be multi-packet; read one more block if needed.
            if (!cmd.toLowerCase().contains("endpoint") && !cmd.toLowerCase().contains("no objects")) {
                String more = readAmiUntilEmpty(in);
                if (!more.isBlank()) {
                    cmd = cmd + more;
                }
            }
            out.print("Action: Logoff\r\n\r\n");
            out.flush();
            int endpointLines = 0;
            for (String line : cmd.split("\n")) {
                if (line.trim().startsWith("Endpoint:")) {
                    endpointLines++;
                }
            }
            boolean noObjects = cmd.toLowerCase().contains("no objects found");
            c.put("ok", true);
            c.put("endpointLines", endpointLines);
            c.put("detail", noObjects
                    ? "AMI OK; pjsip show endpoints → No objects found (provision SIP accounts)"
                    : "AMI OK; pjsip show endpoints reported " + endpointLines + " Endpoint: lines");
            if (noObjects) {
                c.put("fixHint", "Create SIP accounts in Directory → Telephony; backend mirrors into asterisk.ps_* then softphones can register.");
            }
        } catch (Exception e) {
            String msg = safeMsg(e);
            c.put("ok", false);
            c.put("error", msg);
            c.put("fixHint",
                    "Cannot reach AMI at " + amiHost + ":" + amiPort
                            + ". When Decision Plane runs on the host, docker-compose must publish 127.0.0.1:5038:5038 "
                            + "(not the container hostname). Also confirm asterisk container is healthy and manager.conf enabled=yes.");
            log.warn("telephony_health asteriskRealtime AMI unreachable host={}:{} err={}", amiHost, amiPort, msg);
        }
        return c;
    }

    private Map<String, Object> checkAri() {
        Map<String, Object> c = base("ari", "ARI reachable");
        SentinelProperties.Ari ari = properties.actuation().ari();
        String baseUrl = ari.baseUrl();
        c.put("baseUrl", baseUrl);
        c.put("usernameConfigured", ari.username() != null && !ari.username().isBlank());
        c.put("passwordConfigured", ari.password() != null && !ari.password().isBlank());
        try {
            URI uri = URI.create(baseUrl.endsWith("/") ? baseUrl + "asterisk/info" : baseUrl + "/asterisk/info");
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setConnectTimeout(ari.connectTimeoutMs());
            conn.setReadTimeout(ari.readTimeoutMs());
            conn.setRequestMethod("GET");
            String token = Base64.getEncoder().encodeToString(
                    (ari.username() + ":" + ari.password()).getBytes(StandardCharsets.UTF_8));
            conn.setRequestProperty("Authorization", "Basic " + token);
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                c.put("ok", true);
                c.put("httpStatus", code);
                c.put("detail", "GET " + uri.getPath() + " → " + code);
            } else if (code == 401 || code == 403) {
                c.put("ok", false);
                c.put("httpStatus", code);
                c.put("error", "ARI HTTP " + code + " (credentials mismatch)");
                c.put("fixHint", "Align sentinelvoice.actuation.ari.username/password with ari.conf [sentinel] (lab: sentinel / sentineldemo). Host backend must use http://127.0.0.1:8088/ari.");
                log.warn("telephony_health ari auth failed status={} baseUrl={}", code, baseUrl);
            } else {
                c.put("ok", false);
                c.put("httpStatus", code);
                c.put("error", "ARI HTTP " + code);
                c.put("fixHint", "Check Asterisk http.conf/ari.conf enabled and port 8088 published to 127.0.0.1.");
                log.warn("telephony_health ari http status={} baseUrl={}", code, baseUrl);
            }
        } catch (Exception e) {
            String msg = safeMsg(e);
            c.put("ok", false);
            c.put("error", msg);
            c.put("fixHint",
                    "ARI not reachable at " + baseUrl
                            + ". Host-run backend must use 127.0.0.1:8088 (published port), not the Docker service name.");
            log.warn("telephony_health ari unreachable baseUrl={} err={}", baseUrl, msg);
        }
        return c;
    }

    private Map<String, Object> checkSipExternalIp() {
        Map<String, Object> c = base("sipExternalIp", "SIP_EXTERNAL_IP");
        if (sipExternalIp.isBlank()) {
            c.put("ok", false);
            c.put("error", "SIP_EXTERNAL_IP is empty");
            c.put("fixHint",
                    "Set SIP_EXTERNAL_IP to your LAN IPv4 in repo .env (run .\\scripts\\detect-lan-ip.ps1), then restart backend "
                            + "(scripts/sv.ps1 loads .env; IDE runs also load .env via DotEnvEnvironmentPostProcessor). Rebuild/recreate asterisk after changing it.");
            log.warn("telephony_health SIP_EXTERNAL_IP empty — softphones will fail NAT / registration");
        } else {
            c.put("ok", true);
            c.put("value", sipExternalIp);
            c.put("detail", "SIP_EXTERNAL_IP=" + sipExternalIp);
        }
        return c;
    }

    private static Map<String, Object> base(String id, String label) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("label", label);
        return m;
    }

    private static String buildSummary(List<Map<String, Object>> checks) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> c : checks) {
            boolean ok = Boolean.TRUE.equals(c.get("ok"));
            sb.append(ok ? "[OK] " : "[FAIL] ");
            sb.append(c.get("label"));
            if (!ok) {
                if (c.get("error") != null) {
                    sb.append(" — ").append(c.get("error"));
                }
                if (c.get("fixHint") != null) {
                    sb.append(" | fix: ").append(c.get("fixHint"));
                }
            } else if (c.get("detail") != null) {
                sb.append(" — ").append(c.get("detail"));
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    private static String readAmiUntilEmpty(BufferedReader in) throws Exception {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isEmpty()) {
                break;
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static String safeMsg(Throwable e) {
        String m = e.getMessage();
        if (m == null || m.isBlank()) {
            return e.getClass().getSimpleName();
        }
        // Strip possible password= fragments
        return m.replaceAll("(?i)(password|secret|dbpass)=\\S+", "$1=***");
    }
}
