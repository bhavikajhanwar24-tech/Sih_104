package com.sentinelvoice.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Infrastructure tunables only. Risk weights / thresholds / intervention dwell live in
 * PostgreSQL {@code fusion_configs} (F8) — never in this YAML binding.
 */
@Validated
@ConfigurationProperties(prefix = "sentinelvoice")
public record SentinelProperties(
        @NotNull @Valid Ml ml,
        @NotNull @Valid Session session,
        @NotNull @Valid Audit audit,
        @NotNull @Valid Identity identity,
        @NotNull @Valid Actuation actuation,
        @NotNull @Valid Compliance compliance
) {

    public record Ml(
            @NotBlank String baseUrl,
            @NotBlank String websocketUrl,
            @Min(value = 1, message = "ml.connectTimeoutMs must be >= 1")
            int connectTimeoutMs,
            @Min(value = 1, message = "ml.frameStalenessMs must be >= 1")
            int frameStalenessMs
    ) {
    }

    public record Session(
            @Min(value = 1, message = "session.ttlMinutes must be >= 1")
            int ttlMinutes,
            @Min(value = 1, message = "session.maxConcurrent must be >= 1")
            int maxConcurrent
    ) {
    }

    public record Audit(
            @NotBlank String genesisPrefix
    ) {
    }

    public record Identity(
            @NotBlank String internalExtensionPattern,
            @NotNull List<String> registeredExternalClis,
            @DecimalMin("0.0") @DecimalMax("1.0") double cosineMatchMin,
            @DecimalMin("0.0") @DecimalMax("1.0") double cosineMismatchMax,
            @DecimalMin("0.0") @DecimalMax("1.0") double spoofHighThreshold
    ) {
    }

    public record Actuation(
            @NotBlank String adapter,
            @NotNull @Valid Ari ari,
            @NotBlank String cbsFreezeUrl,
            @NotBlank String supervisorEndpoint
    ) {
    }

    public record Ari(
            @NotBlank String baseUrl,
            @NotBlank String username,
            @NotBlank String password,
            @Min(1) int connectTimeoutMs,
            @Min(1) int readTimeoutMs
    ) {
    }

    public record Compliance(
            @Min(1) int telemetryTtlDays,
            @Min(1) int auditRetentionYears,
            @Min(1) long purgeIntervalHours,
            @NotBlank String fairnessResultsPath,
            @NotBlank String rawAudioEnforcingPath
    ) {
    }
}
