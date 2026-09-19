package com.sentinelvoice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;

/**
 * Demo lab users bound from {@code sv.security.*} (P13).
 * Kept off the {@code sentinelvoice.*} prefix so {@link SentinelProperties} constructor
 * binding is not wiped. Password for all demo users: {@code password}.
 */
@Validated
@ConfigurationProperties(prefix = "sv.security")
public class SecurityProperties {

    /** When false, or when profile {@code nosec} is active, HTTP/STOMP auth is off. */
    private boolean enabled = true;
    private final List<User> users = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<User> getUsers() {
        return users;
    }

    public static class User {
        private String username = "";
        private String passwordHash = "";
        private List<String> roles = List.of();

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPasswordHash() {
            return passwordHash;
        }

        public void setPasswordHash(String passwordHash) {
            this.passwordHash = passwordHash;
        }

        public List<String> getRoles() {
            return roles;
        }

        public void setRoles(List<String> roles) {
            this.roles = roles == null ? List.of() : List.copyOf(roles);
        }
    }
}
