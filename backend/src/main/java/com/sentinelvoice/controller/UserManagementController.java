package com.sentinelvoice.controller;

import com.sentinelvoice.auth.UserManagementService;
import com.sentinelvoice.auth.UserManagementService.InviteRequest;
import com.sentinelvoice.auth.UserManagementService.UserView;
import com.sentinelvoice.security.TenantContext;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v2/users")
public class UserManagementController {

    private final UserManagementService userManagementService;

    public UserManagementController(UserManagementService userManagementService) {
        this.userManagementService = userManagementService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','AUDITOR')")
    public Map<String, Object> list() {
        TenantContext ctx = TenantContext.require();
        List<UserView> users = userManagementService.list(ctx.tenantId());
        return Map.of("users", users);
    }

    @PostMapping
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<UserView> invite(@RequestBody InviteBody body) {
        TenantContext ctx = TenantContext.require();
        UserView created = userManagementService.invite(
                ctx.tenantId(),
                ctx.userId(),
                new InviteRequest(body.email(), body.displayName(), body.role(), body.temporaryPassword())
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/{id}/role")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public UserView changeRole(@PathVariable("id") java.util.UUID id, @RequestBody RoleBody body) {
        TenantContext ctx = TenantContext.require();
        return userManagementService.changeRole(ctx.tenantId(), ctx.userId(), id, body.role());
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public UserView disable(@PathVariable("id") java.util.UUID id) {
        TenantContext ctx = TenantContext.require();
        return userManagementService.disable(ctx.tenantId(), ctx.userId(), id);
    }

    public record InviteBody(
            @NotBlank String email,
            @NotBlank String displayName,
            @NotBlank String role,
            @NotBlank String temporaryPassword
    ) {
    }

    public record RoleBody(@NotBlank String role) {
    }
}
