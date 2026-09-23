package com.sentinelvoice.integrations;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class IntegrationsOpenApiConfig {

    @Bean
    public OpenAPI sentinelVoiceOpenApi(
            @Value("${sentinelvoice.public-base-url:http://127.0.0.1:8081}") String baseUrl
    ) {
        return new OpenAPI()
                .info(new Info()
                        .title("SentinelVoice Integrations API")
                        .version("v2")
                        .description("""
                                Public integration surface (F17): pre-transaction gate checks,
                                cross-channel events, directory sync, session risk, and webhooks.

                                Authenticate with `Authorization: Bearer sv_live_…` or `X-API-Key`.
                                """)
                        .contact(new Contact().name("SentinelVoice").email("integrations@sentinelvoice.local"))
                        .license(new License().name("Proprietary")))
                .servers(List.of(new Server().url(baseUrl).description("API base")))
                .components(new Components()
                        .addSecuritySchemes("ApiKey", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-API-Key"))
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("sv_live_…")));
    }
}
