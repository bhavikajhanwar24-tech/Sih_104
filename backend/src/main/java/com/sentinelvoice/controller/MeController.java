package com.sentinelvoice.controller;

import com.sentinelvoice.auth.Role;
import com.sentinelvoice.auth.UserEntity;
import com.sentinelvoice.auth.UserRepository;
import com.sentinelvoice.security.TenantContext;
import com.sentinelvoice.tenant.TenantEntity;
import com.sentinelvoice.tenant.TenantRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v2")
public class MeController {

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;

    public MeController(UserRepository userRepository, TenantRepository tenantRepository) {
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        TenantContext ctx = TenantContext.require();
        UserEntity user = userRepository.findById(ctx.userId()).orElseThrow();
        TenantEntity tenant = tenantRepository.findById(ctx.tenantId()).orElseThrow();
        Role role = Role.from(user.getRole());

        Map<String, Object> userMap = new LinkedHashMap<>();
        userMap.put("id", user.getId().toString());
        userMap.put("email", user.getEmail());
        userMap.put("displayName", user.getDisplayName());
        userMap.put("status", user.getStatus());

        Map<String, Object> tenantMap = new LinkedHashMap<>();
        tenantMap.put("id", tenant.getId().toString());
        tenantMap.put("name", tenant.getName());
        tenantMap.put("slug", tenant.getSlug());
        tenantMap.put("industry", tenant.getIndustry());
        tenantMap.put("region", tenant.getRegion());
        tenantMap.put("status", tenant.getStatus());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user", userMap);
        body.put("tenant", tenantMap);
        body.put("role", role.name());
        body.put("mfaEnabled", user.isMfaEnabled());
        body.put("permissions", new ArrayList<>(role.permissions()));
        body.put("promptMfa", role == Role.TENANT_ADMIN && !user.isMfaEnabled());
        return body;
    }
}
