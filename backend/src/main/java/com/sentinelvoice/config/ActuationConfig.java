package com.sentinelvoice.config;

import com.sentinelvoice.actuation.AsteriskAriAdapter;
import com.sentinelvoice.actuation.CallControlPort;
import com.sentinelvoice.actuation.NoopAdapter;
import com.sentinelvoice.actuation.SessionChannelRegistry;
import com.sentinelvoice.actuation.WebRtcAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Selects the call-control adapter and provides a dedicated ARI HTTP executor
 * so actuation never blocks the fusion ingest path.
 */
@Configuration
public class ActuationConfig {

    private static final Logger log = LoggerFactory.getLogger(ActuationConfig.class);

    @Bean(destroyMethod = "shutdown")
    public ExecutorService ariActuationExecutor() {
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "ari-actuation-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        return Executors.newFixedThreadPool(4, factory);
    }

    @Bean
    public CallControlPort callControlPort(
            SentinelProperties properties,
            SessionChannelRegistry channelRegistry,
            ExecutorService ariActuationExecutor,
            // ObjectProvider: do not pull STOMP/WebSocket into the cycle for asterisk/noop.
            ObjectProvider<SimpMessagingTemplate> messagingTemplate
    ) {
        String adapter = properties.actuation().adapter() == null
                ? "noop"
                : properties.actuation().adapter().trim().toLowerCase(Locale.ROOT);
        return switch (adapter) {
            case "asterisk" -> {
                log.info(
                        "actuation adapter=asterisk ariBaseUrl={}",
                        properties.actuation().ariBaseUrl()
                );
                yield new AsteriskAriAdapter(channelRegistry, properties.actuation(), ariActuationExecutor);
            }
            case "webrtc" -> {
                log.info("actuation adapter=webrtc");
                SimpMessagingTemplate template = messagingTemplate.getIfAvailable();
                if (template == null) {
                    log.warn("actuation adapter=webrtc but SimpMessagingTemplate missing — falling back to noop");
                    yield new NoopAdapter();
                }
                yield new WebRtcAdapter(template);
            }
            default -> {
                if (!"noop".equals(adapter)) {
                    log.warn("unknown actuation.adapter={} — falling back to noop", adapter);
                } else {
                    log.info("actuation adapter=noop (safe default)");
                }
                yield new NoopAdapter();
            }
        };
    }
}
