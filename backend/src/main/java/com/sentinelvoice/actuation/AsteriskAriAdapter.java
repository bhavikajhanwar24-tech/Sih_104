package com.sentinelvoice.actuation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Full Asterisk REST Interface actuation (Context §11.6).
 *
 * <p>Channel map: {@code sessionId → channelId}. The AudioSocket / SV_SESSION UUID is the
 * session key; the bridged PJSIP channel id is bound by the gateway bridge (or defaults to
 * sessionId when the lab uses UNIQUEID-as-UUID).
 */
public class AsteriskAriAdapter implements CallControlPort {

    private static final Logger log = LoggerFactory.getLogger(AsteriskAriAdapter.class);

    public static final String SOUND_HOLD = "sentinel-hold";
    public static final String SOUND_WHISPER = "sentinel-whisper-warning";

    private final RestTemplate restTemplate;
    private final String ariBaseUrl;
    private final String authHeader;
    private final String supervisorEndpoint;
    private final ConcurrentMap<String, String> sessionToChannel = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> sessionToAgentChannel = new ConcurrentHashMap<>();
    private final AsteriskAmiClient amiClient;

    public AsteriskAriAdapter(
            RestTemplate ariRestTemplate,
            String ariBaseUrl,
            String username,
            String password,
            String supervisorEndpoint
    ) {
        this.restTemplate = ariRestTemplate;
        this.ariBaseUrl = ariBaseUrl.endsWith("/") ? ariBaseUrl.substring(0, ariBaseUrl.length() - 1) : ariBaseUrl;
        String token = Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        this.authHeader = "Basic " + token;
        this.supervisorEndpoint = supervisorEndpoint == null || supervisorEndpoint.isBlank()
                ? "PJSIP/agent"
                : supervisorEndpoint.trim();
        this.amiClient = new AsteriskAmiClient("127.0.0.1", 5038, username, password, 8000);
    }

    /** Bind Decision Plane session to an Asterisk channel id (caller / bridged leg). */
    public void bindChannel(String sessionId, String channelId) {
        if (sessionId == null || sessionId.isBlank() || channelId == null || channelId.isBlank()) {
            return;
        }
        sessionToChannel.put(sessionId.trim(), channelId.trim());
        log.info("ari_channel_bound sessionId={} channelId={}", sessionId, channelId);
    }

    /** Optional agent-leg binding for agent-only whisper. */
    public void bindAgentChannel(String sessionId, String agentChannelId) {
        if (sessionId == null || sessionId.isBlank() || agentChannelId == null || agentChannelId.isBlank()) {
            return;
        }
        sessionToAgentChannel.put(sessionId.trim(), agentChannelId.trim());
        log.info("ari_agent_channel_bound sessionId={} channelId={}", sessionId, agentChannelId);
    }

    public void unbind(String sessionId) {
        if (sessionId == null) {
            return;
        }
        sessionToChannel.remove(sessionId);
        sessionToAgentChannel.remove(sessionId);
    }

    public Optional<String> channelFor(String sessionId) {
        return Optional.ofNullable(sessionToChannel.get(sessionId));
    }

    @Override
    public void hold(String sessionId) {
        // ConfBridge mute silences both softphones without hangup (no AMI / no redirect).
        String conf = conferenceName(sessionId);
        try {
            amiClient.muteConference(conf, true);
            log.info("ari_hold ok sessionId={} conf={} via=confbridge_mute", sessionId, conf);
        } catch (Exception ex) {
            String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
            // Fixture WAV replay has no Asterisk conference — Decision Plane L4 still stands.
            if (msg.contains("no conference") || msg.contains("not found")) {
                log.info(
                        "ari_hold skipped sessionId={} conf={} reason=no_live_conference (replay/UI-only hold)",
                        sessionId,
                        conf
                );
                return;
            }
            if (ex instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(
                    "Hold/mute failed sessionId=" + sessionId + " conf=" + conf,
                    ex
            );
        }
    }

    /** Dialplan CONF = {@code sv} + session UUID with dashes stripped. */
    static String conferenceName(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId required for ConfBridge hold");
        }
        String hex = sessionId.trim().replace("-", "");
        return "sv" + hex;
    }

    @Override
    public void unhold(String sessionId) {
        String conf = conferenceName(sessionId);
        try {
            amiClient.muteConference(conf, false);
            log.info("ari_unhold ok sessionId={} conf={} via=confbridge_unmute", sessionId, conf);
        } catch (Exception ex) {
            log.warn("ari_unhold_failed sessionId={} conf={} err={}", sessionId, conf, ex.toString());
        }
    }

    @Override
    public void whisperToAgent(String sessionId, String soundId) {
        String media = "sound:" + (soundId == null || soundId.isBlank() ? SOUND_WHISPER : soundId);
        String agentChannel = sessionToAgentChannel.get(sessionId);
        if (agentChannel == null) {
            agentChannel = findChannelByNamePrefix("PJSIP/agent").orElse(null);
        }
        String spyTarget = agentChannel != null ? agentChannel : resolveChannel(sessionId);

        // Snoop with whisper=out so Playback on the snoop is heard by the AGENT leg only.
        String snoopId = "sv-whisper-" + UUID.randomUUID().toString().substring(0, 8);
        URI snoopUri = UriComponentsBuilder
                .fromHttpUrl(ariBaseUrl + "/channels/" + spyTarget + "/snoop/" + snoopId)
                .queryParam("spy", "none")
                .queryParam("whisper", "out")
                .queryParam("app", "sentinel-whisper")
                .build(true)
                .toUri();

        ResponseEntity<Map> snoopResp = exchangeUri(HttpMethod.POST, snoopUri, null);
        String snoopChannelId = extractId(snoopResp.getBody(), snoopId);
        playOnChannel(snoopChannelId, media);
        log.info(
                "ari_whisper ok sessionId={} target={} snoop={} media={}",
                sessionId,
                spyTarget,
                snoopChannelId,
                media
        );
    }

    @Override
    public void bridgeSupervisor(String sessionId, String supervisorEndpoint) {
        String endpoint = supervisorEndpoint == null || supervisorEndpoint.isBlank()
                ? this.supervisorEndpoint
                : supervisorEndpoint;
        String channelId = resolveChannel(sessionId);

        try {
            // Best-effort: create a mixing bridge, add the live channel, originate supervisor into it.
            Map<?, ?> bridge = exchange(HttpMethod.POST, "/bridges?type=mixing", null).getBody();
            String bridgeId = extractId(bridge, null);
            if (bridgeId == null) {
                throw new IllegalStateException("ARI create bridge returned no id");
            }
            exchange(HttpMethod.POST, "/bridges/" + bridgeId + "/addChannel?channel=" + channelId, null);

            URI originate = UriComponentsBuilder
                    .fromHttpUrl(ariBaseUrl + "/channels")
                    .queryParam("endpoint", endpoint)
                    .queryParam("app", "sentinel-supervisor")
                    .queryParam("appArgs", sessionId)
                    .build(true)
                    .toUri();
            ResponseEntity<Map> origResp = exchangeUri(HttpMethod.POST, originate, null);
            String supervisorChannel = extractId(origResp.getBody(), null);
            if (supervisorChannel != null) {
                exchange(
                        HttpMethod.POST,
                        "/bridges/" + bridgeId + "/addChannel?channel=" + supervisorChannel,
                        null
                );
            }
            log.info(
                    "ari_bridge_supervisor ok sessionId={} bridge={} supervisor={}",
                    sessionId,
                    bridgeId,
                    supervisorChannel
            );
        } catch (HttpStatusCodeException ex) {
            // Dialplan / AudioSocket legs are not in a Stasis app — ARI cannot re-bridge them.
            // Hold + announce still apply; treat as soft-success for the lab path.
            if (ex.getStatusCode().value() == 422) {
                log.warn(
                        "ari_bridge_supervisor skipped sessionId={} channelId={} reason=not_in_stasis (hold remains)",
                        sessionId,
                        channelId
                );
                return;
            }
            throw ex;
        }
    }

    @Override
    public void announce(String sessionId, String soundId) {
        String channelId = resolveChannel(sessionId);
        String media = "sound:" + (soundId == null || soundId.isBlank() ? SOUND_HOLD : soundId);
        playOnChannel(channelId, media);
    }

    @Override
    public void terminate(String sessionId, String reason) {
        String channelId = resolveChannel(sessionId);
        try {
            exchange(HttpMethod.DELETE, "/channels/" + channelId, null);
            log.info("ari_terminate ok sessionId={} channelId={} reason={}", sessionId, channelId, reason);
        } catch (HttpStatusCodeException ex) {
            if (ex.getStatusCode().value() == 404) {
                log.info("ari_terminate already_gone sessionId={} channelId={}", sessionId, channelId);
                return;
            }
            throw ex;
        } finally {
            unbind(sessionId);
        }
    }

    @Override
    public Set<String> capabilities() {
        return Set.of("HOLD", "UNHOLD", "WHISPER", "ANNOUNCE", "BRIDGE_SUPERVISOR", "TERMINATE");
    }

    @Override
    public String adapterName() {
        return "asterisk-ari";
    }

    private void playOnChannel(String channelId, String media) {
        URI playUri = UriComponentsBuilder
                .fromHttpUrl(ariBaseUrl + "/channels/" + channelId + "/play")
                .queryParam("media", media)
                .build(true)
                .toUri();
        exchangeUri(HttpMethod.POST, playUri, null);
    }

    private String resolveChannel(String sessionId) {
        String mapped = sessionToChannel.get(sessionId);
        if (mapped != null && !mapped.isBlank()) {
            return mapped;
        }
        // Task: AudioSocket UUID may equal channel UNIQUEID — try sessionId, then ARI lookup.
        Optional<String> byVar = findChannelBySessionVariable(sessionId);
        if (byVar.isPresent()) {
            sessionToChannel.put(sessionId, byVar.get());
            return byVar.get();
        }
        log.warn("ari_channel_unbound sessionId={} — using sessionId as channelId", sessionId);
        return sessionId;
    }

    @SuppressWarnings("unchecked")
    private Optional<String> findChannelBySessionVariable(String sessionId) {
        try {
            ResponseEntity<List> resp = exchange(HttpMethod.GET, "/channels", null, List.class);
            List<?> channels = resp.getBody();
            if (channels == null) {
                return Optional.empty();
            }
            for (Object raw : channels) {
                if (!(raw instanceof Map<?, ?> ch)) {
                    continue;
                }
                Object id = ch.get("id");
                if (id == null) {
                    continue;
                }
                String channelId = id.toString();
                try {
                    URI varUri = UriComponentsBuilder
                            .fromHttpUrl(ariBaseUrl + "/channels/" + channelId + "/variable")
                            .queryParam("variable", "SV_SESSION")
                            .build(true)
                            .toUri();
                    ResponseEntity<Map> varResp = exchangeUri(HttpMethod.GET, varUri, null);
                    Object value = varResp.getBody() == null ? null : varResp.getBody().get("value");
                    if (sessionId.equals(String.valueOf(value))) {
                        return Optional.of(channelId);
                    }
                } catch (RestClientException ignored) {
                    // channel may have hung up mid-scan
                }
            }
        } catch (RestClientException ex) {
            log.debug("ari_channel_scan_failed sessionId={} err={}", sessionId, ex.toString());
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private Optional<String> findChannelByNamePrefix(String prefix) {
        try {
            ResponseEntity<List> resp = exchange(HttpMethod.GET, "/channels", null, List.class);
            List<?> channels = resp.getBody();
            if (channels == null) {
                return Optional.empty();
            }
            for (Object raw : channels) {
                if (!(raw instanceof Map<?, ?> ch)) {
                    continue;
                }
                Object name = ch.get("name");
                Object id = ch.get("id");
                if (name != null && id != null && name.toString().startsWith(prefix)) {
                    return Optional.of(id.toString());
                }
            }
        } catch (RestClientException ex) {
            log.debug("ari_find_agent_failed err={}", ex.toString());
        }
        return Optional.empty();
    }

    private ResponseEntity<Map> exchange(HttpMethod method, String path, Object body) {
        return exchange(method, path, body, Map.class);
    }

    private <T> ResponseEntity<T> exchange(HttpMethod method, String path, Object body, Class<T> type) {
        URI uri = URI.create(ariBaseUrl + path);
        return exchangeUri(method, uri, body, type);
    }

    private ResponseEntity<Map> exchangeUri(HttpMethod method, URI uri, Object body) {
        return exchangeUri(method, uri, body, Map.class);
    }

    private <T> ResponseEntity<T> exchangeUri(HttpMethod method, URI uri, Object body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, authHeader);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        HttpEntity<Object> entity = new HttpEntity<>(body, headers);
        return restTemplate.exchange(uri, method, entity, type);
    }

    private static String extractId(Map<?, ?> body, String fallback) {
        if (body == null) {
            return fallback;
        }
        Object id = body.get("id");
        return id == null ? fallback : id.toString();
    }
}
