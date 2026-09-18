package com.sentinelvoice.actuation;

import com.sentinelvoice.config.SentinelProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Asterisk ARI HTTP adapter. All ARI calls run on {@code ariActuationExecutor}
 * so the fusion / ingest path never blocks on telephony I/O.
 */
public class AsteriskAriAdapter implements CallControlPort {

    private static final Logger log = LoggerFactory.getLogger(AsteriskAriAdapter.class);

    private static final Set<ActuationAction> CAPS = EnumSet.of(
            ActuationAction.CALL_HELD,
            ActuationAction.ANNOUNCE_HOLD,
            ActuationAction.WHISPER_WARNING,
            ActuationAction.SUPERVISOR_BRIDGED,
            ActuationAction.CALL_TERMINATED,
            ActuationAction.HOLD,
            ActuationAction.UNHOLD,
            ActuationAction.WHISPER,
            ActuationAction.ANNOUNCE,
            ActuationAction.BRIDGE_SUPERVISOR,
            ActuationAction.TERMINATE
    );

    private final SessionChannelRegistry channelRegistry;
    private final SentinelProperties.Actuation config;
    private final ExecutorService ariExecutor;
    private final HttpClient httpClient;
    private final String basicAuthHeader;

    public AsteriskAriAdapter(
            SessionChannelRegistry channelRegistry,
            SentinelProperties.Actuation config,
            ExecutorService ariExecutor
    ) {
        this.channelRegistry = channelRegistry;
        this.config = config;
        this.ariExecutor = ariExecutor;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1, config.connectTimeoutMs())))
                .executor(ariExecutor)
                .build();
        String token = config.ariUser() + ":" + config.ariPassword();
        this.basicAuthHeader = "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public ActuationResult hold(String sessionId) {
        return withChannel(sessionId, channelId ->
                ari("POST", "/channels/" + enc(channelId) + "/hold", null, "hold"));
    }

    @Override
    public ActuationResult unhold(String sessionId) {
        return withChannel(sessionId, channelId ->
                ari("DELETE", "/channels/" + enc(channelId) + "/hold", null, "unhold"));
    }

    @Override
    public ActuationResult whisperToAgent(String sessionId, String soundId) {
        String media = soundId == null || soundId.isBlank() ? config.whisperSoundId() : soundId;
        return withChannel(sessionId, channelId -> {
            String snoopId = UUID.randomUUID().toString();
            // whisper=out → agent leg only; caller must not hear the warning.
            ActuationResult snoop = ari(
                    "POST",
                    "/channels/" + enc(channelId) + "/snoop/" + enc(snoopId)
                            + "?spy=none&whisper=out&app=sentinelvoice",
                    null,
                    "snoop-whisper"
            );
            if (snoop.status() != ActuationResult.Status.SUCCESS) {
                return snoop;
            }
            return ari(
                    "POST",
                    "/channels/" + enc(snoopId) + "/play?media=" + enc(mediaPrefix(media)),
                    null,
                    "whisper-play"
            );
        });
    }

    @Override
    public ActuationResult bridgeSupervisor(String sessionId, String supervisorEndpoint) {
        String endpoint = supervisorEndpoint == null || supervisorEndpoint.isBlank()
                ? config.supervisorEndpoint()
                : supervisorEndpoint;
        return withChannel(sessionId, channelId -> {
            String originateBody = """
                    {"endpoint":"%s","app":"sentinelvoice","appArgs":"supervisor,%s","callerId":"SentinelSupervisor"}
                    """.formatted(endpoint, channelId).trim();
            return ari("POST", "/channels", originateBody, "bridge-supervisor");
        });
    }

    @Override
    public ActuationResult announce(String sessionId, String soundId) {
        String media = soundId == null || soundId.isBlank() ? config.holdSoundId() : soundId;
        return withChannel(sessionId, channelId ->
                ari(
                        "POST",
                        "/channels/" + enc(channelId) + "/play?media=" + enc(mediaPrefix(media)),
                        null,
                        "announce"
                ));
    }

    @Override
    public ActuationResult terminate(String sessionId, String reason) {
        return withChannel(sessionId, channelId ->
                ari("DELETE", "/channels/" + enc(channelId) + "?reason=" + enc(reason == null ? "fraud" : reason),
                        null, "terminate"));
    }

    @Override
    public Set<ActuationAction> capabilities() {
        return EnumSet.copyOf(CAPS);
    }

    @Override
    public String adapterName() {
        return "asterisk";
    }

    private ActuationResult withChannel(String sessionId, ChannelOp op) {
        Optional<String> channel = channelRegistry.findChannelId(sessionId);
        if (channel.isEmpty()) {
            log.warn("ari_missing_channel sessionId={}", sessionId);
            return ActuationResult.failure("no channel mapped for sessionId=" + sessionId);
        }
        try {
            return op.apply(channel.get());
        } catch (Exception ex) {
            log.warn("ari_op_failed sessionId={} cause={}", sessionId, ex.toString());
            return ActuationResult.failure(ex.getMessage());
        }
    }

    private ActuationResult ari(String method, String path, String jsonBody, String label) {
        try {
            CompletableFuture<ActuationResult> future = CompletableFuture.supplyAsync(
                    () -> executeSync(method, path, jsonBody, label),
                    ariExecutor
            );
            return future.get(Math.max(1, config.readTimeoutMs()) + 2_000L, TimeUnit.MILLISECONDS);
        } catch (Exception ex) {
            log.warn("ari_async_failed label={} cause={}", label, ex.toString());
            return ActuationResult.failure(ex.getMessage());
        }
    }

    private ActuationResult executeSync(String method, String path, String jsonBody, String label) {
        try {
            String base = config.ariBaseUrl().replaceAll("/$", "");
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(base + path))
                    .timeout(Duration.ofMillis(Math.max(1, config.readTimeoutMs())))
                    .header("Authorization", basicAuthHeader)
                    .header("Accept", "application/json");
            if ("GET".equals(method)) {
                builder.GET();
            } else if ("DELETE".equals(method)) {
                builder.DELETE();
            } else if (jsonBody != null) {
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            if (code >= 200 && code < 300) {
                log.info("ari_ok label={} method={} path={} status={}", label, method, path, code);
                return ActuationResult.success("ari-" + label + "-" + code);
            }
            log.warn(
                    "ari_http_error label={} method={} path={} status={} body={}",
                    label,
                    method,
                    path,
                    code,
                    truncate(response.body())
            );
            return ActuationResult.failure("ARI " + code + " " + truncate(response.body()));
        } catch (Exception ex) {
            log.warn("ari_http_exception label={} cause={}", label, ex.toString());
            return ActuationResult.failure(ex.getMessage());
        }
    }

    private static String mediaPrefix(String soundId) {
        if (soundId == null || soundId.isBlank()) {
            return "sound:sentinel-hold";
        }
        if (soundId.startsWith("sound:") || soundId.startsWith("recording:")) {
            return soundId;
        }
        return "sound:" + soundId;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 200 ? body : body.substring(0, 200);
    }

    @FunctionalInterface
    private interface ChannelOp {
        ActuationResult apply(String channelId) throws Exception;
    }
}
