package com.sentinelvoice.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    static final List<String> DEFAULT_ALLOWED_ORIGINS = List.of(
            "http://localhost:5173",
            "http://127.0.0.1:5173",
            "http://localhost:5174",
            "http://127.0.0.1:5174",
            "http://localhost:5175",
            "http://127.0.0.1:5175",
            "http://localhost:3000",
            "http://127.0.0.1:3000"
    );

    private final Environment environment;
    private final SecurityProperties securityProperties;

    public SecurityConfig(Environment environment, SecurityProperties securityProperties) {
        this.environment = environment;
        this.securityProperties = securityProperties;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder encoder) {
        // Demo lab users (P13) — password for all is "password".
        // Registered in code so bcrypt `$` never touches application.yml binding.
        List<UserDetails> users = new ArrayList<>();
        users.add(User.withUsername("analyst").password(encoder.encode("password")).roles("ANALYST").build());
        users.add(User.withUsername("supervisor").password(encoder.encode("password")).roles("SUPERVISOR").build());
        users.add(User.withUsername("compliance").password(encoder.encode("password")).roles("COMPLIANCE").build());
        users.add(User.withUsername("admin")
                .password(encoder.encode("password"))
                .roles("ADMIN", "ANALYST", "SUPERVISOR", "COMPLIANCE")
                .build());
        for (SecurityProperties.User u : securityProperties.getUsers()) {
            if (u.getUsername() == null || u.getUsername().isBlank()) {
                continue;
            }
            if (users.stream().anyMatch(existing -> existing.getUsername().equals(u.getUsername()))) {
                continue;
            }
            String[] roles = u.getRoles().stream()
                    .map(r -> r.startsWith("ROLE_") ? r.substring(5) : r)
                    .toArray(String[]::new);
            users.add(User.withUsername(u.getUsername())
                    .password(u.getPasswordHash() == null || u.getPasswordHash().isBlank()
                            ? encoder.encode("password")
                            : u.getPasswordHash())
                    .roles(roles)
                    .build());
        }
        return new InMemoryUserDetailsManager(users);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        boolean authOff = !securityProperties.isEnabled()
                || environment.matchesProfiles("nosec");
        if (authOff) {
            http.csrf(csrf -> csrf.disable())
                    .cors(Customizer.withDefaults())
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
            return http.build();
        }
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .httpBasic(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/h2-console", "/h2-console/**").permitAll()
                        .requestMatchers("/ws/features", "/ws/features/**").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/v1/compliance/**").hasAnyRole("COMPLIANCE", "ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/passport/**").hasAnyRole("COMPLIANCE", "ADMIN")
                        .requestMatchers("/api/v1/passport/**").hasAnyRole("ANALYST", "SUPERVISOR", "COMPLIANCE", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/session/*/transcript/approve")
                        .hasAnyRole("SUPERVISOR", "ADMIN")
                        .requestMatchers("/api/v1/session/*/transcript/**")
                        .hasAnyRole("ANALYST", "SUPERVISOR", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/intervention/*/override").hasAnyRole("ANALYST", "SUPERVISOR", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/intervention/*/confirm-l5").hasAnyRole("SUPERVISOR", "ADMIN")
                        .requestMatchers("/api/v1/**").hasAnyRole("ANALYST", "SUPERVISOR", "COMPLIANCE", "ADMIN")
                        .requestMatchers("/ws-sentinel/**", "/topic/**").authenticated()
                        .anyRequest().authenticated()
                )
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(CorsStartupLogger.allowedOrigins(environment));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /**
     * STOMP CONNECT must carry the same Basic credentials as REST (lab demo).
     */
    @Bean
    public ChannelInterceptor stompAuthChannelInterceptor(UserDetailsService users, PasswordEncoder encoder) {
        return new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
                    return message;
                }
                if (!securityProperties.isEnabled() || environment.matchesProfiles("nosec")) {
                    return message;
                }
                String auth = accessor.getFirstNativeHeader("Authorization");
                if (auth == null || !auth.startsWith("Basic ")) {
                    throw new IllegalArgumentException("STOMP CONNECT requires Authorization: Basic …");
                }
                String decoded = new String(Base64.getDecoder().decode(auth.substring(6)), StandardCharsets.UTF_8);
                int colon = decoded.indexOf(':');
                if (colon < 1) {
                    throw new IllegalArgumentException("invalid basic auth");
                }
                String username = decoded.substring(0, colon);
                String password = decoded.substring(colon + 1);
                UserDetails details = users.loadUserByUsername(username);
                if (!encoder.matches(password, details.getPassword())) {
                    throw new IllegalArgumentException("bad credentials");
                }
                Authentication authentication = new UsernamePasswordAuthenticationToken(
                        details.getUsername(),
                        details.getPassword(),
                        details.getAuthorities()
                );
                SecurityContextHolder.getContext().setAuthentication(authentication);
                accessor.setUser(authentication);
                return message;
            }
        };
    }
}
