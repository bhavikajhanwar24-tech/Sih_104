package com.sentinelvoice.config;

import com.sentinelvoice.actuation.AsteriskAriAdapter;
import com.sentinelvoice.actuation.CallControlPort;
import com.sentinelvoice.actuation.CoreBankingWebhookService;
import com.sentinelvoice.actuation.NoopAdapter;
import com.sentinelvoice.actuation.WebRtcAdapter;
import com.sentinelvoice.service.CallSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Selects the telephony actuation adapter and dedicated executor (Context §11.6).
 */
@Configuration
public class ActuationConfig {

    private static final Logger log = LoggerFactory.getLogger(ActuationConfig.class);

    @Bean
    @Qualifier("ariRestTemplate")
    public RestTemplate ariRestTemplate(RestTemplateBuilder builder, SentinelProperties properties) {
        SentinelProperties.Ari ari = properties.actuation().ari();
        return builder
                .setConnectTimeout(Duration.ofMillis(ari.connectTimeoutMs()))
                .setReadTimeout(Duration.ofMillis(ari.readTimeoutMs()))
                .build();
    }

    @Bean(name = "actuationExecutor")
    public Executor actuationExecutor() {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("actuation-", 0).factory());
    }

    @Bean
    public CallControlPort callControlPort(
            SentinelProperties properties,
            @Qualifier("ariRestTemplate") RestTemplate ariRestTemplate,
            @Lazy SimpMessagingTemplate messagingTemplate,
            @Lazy CallSessionManager callSessionManager
    ) {
        String adapter = properties.actuation().adapter().trim().toLowerCase();
        return switch (adapter) {
            case "asterisk", "asterisk-ari", "ari" -> {
                SentinelProperties.Ari ari = properties.actuation().ari();
                log.info(
                        "actuation_adapter=asterisk-ari baseUrl={} connectTimeoutMs={} readTimeoutMs={}",
                        ari.baseUrl(),
                        ari.connectTimeoutMs(),
                        ari.readTimeoutMs()
                );
                yield new AsteriskAriAdapter(
                        ariRestTemplate,
                        ari.baseUrl(),
                        ari.username(),
                        ari.password(),
                        properties.actuation().supervisorEndpoint()
                );
            }
            case "webrtc" -> {
                log.info("actuation_adapter=webrtc");
                yield new WebRtcAdapter(messagingTemplate, callSessionManager);
            }
            default -> {
                log.info("actuation_adapter=noop (configured as '{}')", adapter);
                yield new NoopAdapter();
            }
        };
    }

    @Bean
    public CoreBankingWebhookService coreBankingWebhookService(
            RestTemplate restTemplate,
            Clock clock,
            SentinelProperties properties
    ) {
        return new CoreBankingWebhookService(
                restTemplate,
                clock,
                properties.actuation().cbsFreezeUrl()
        );
    }
}
