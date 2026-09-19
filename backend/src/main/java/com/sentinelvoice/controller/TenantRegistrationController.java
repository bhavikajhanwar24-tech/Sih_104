package com.sentinelvoice.controller;

import com.sentinelvoice.auth.TenantRegistrationService;
import com.sentinelvoice.auth.TenantRegistrationService.RegisterRequest;
import com.sentinelvoice.auth.TenantRegistrationService.RegistrationResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v2/public/tenants")
public class TenantRegistrationController {

    private final TenantRegistrationService registrationService;

    public TenantRegistrationController(TenantRegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@Valid @RequestBody Body body) {
        RegistrationResult result = registrationService.register(new RegisterRequest(
                body.organisationName(),
                body.industry(),
                body.region(),
                body.adminEmail(),
                body.adminDisplayName(),
                body.password(),
                body.acceptedTerms()
        ));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("tenantId", result.tenantId().toString());
        resp.put("slug", result.slug());
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    public record Body(
            @NotBlank String organisationName,
            @NotBlank String industry,
            String region,
            @NotBlank @Email String adminEmail,
            @NotBlank String adminDisplayName,
            @NotBlank @Size(min = 12) String password,
            @NotNull @AssertTrue Boolean acceptedTerms
    ) {
    }
}
