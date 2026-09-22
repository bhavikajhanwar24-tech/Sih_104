package com.sentinelvoice.linguistics;

import com.sentinelvoice.policy.sets.PolicySetRepository;
import com.sentinelvoice.security.TenantContext;
import com.sentinelvoice.tenant.TenantSettingsEntity;
import com.sentinelvoice.tenant.TenantSettingsRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * F11 — ml-engine Stage A lexicon bootstrap (service-token).
 */
@RestController
@RequestMapping("/internal/v2/linguistics")
public class InternalLinguisticsController {

    private final PolicySetRepository policySetRepository;
    private final TenantSettingsRepository tenantSettingsRepository;
    private final JdbcTemplate jdbc;

    public InternalLinguisticsController(
            PolicySetRepository policySetRepository,
            TenantSettingsRepository tenantSettingsRepository,
            JdbcTemplate jdbc
    ) {
        this.policySetRepository = policySetRepository;
        this.tenantSettingsRepository = tenantSettingsRepository;
        this.jdbc = jdbc;
    }

    @GetMapping("/lexicon")
    public Map<String, Object> lexicon(@RequestParam UUID tenantId) {
        if (tenantId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tenantId required");
        }
        return TenantContext.runAs(tenantId, () -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("tenantId", tenantId.toString());
            Map<String, Object> active = policySetRepository.findActiveSet(tenantId).orElse(null);
            List<Map<String, Object>> keywords = List.of();
            List<Map<String, Object>> facts = List.of();
            if (active != null && active.get("id") != null) {
                UUID setId = toUuid(active.get("id"));
                out.put("policySetId", setId.toString());
                keywords = policySetRepository.listKeywords(tenantId, setId);
                facts = policySetRepository.listFacts(tenantId, setId);
            } else {
                out.put("policySetId", null);
            }
            out.put("keywords", keywords);
            out.put("facts", compactFacts(facts));
            out.put("nameTokens", directoryNameTokens(tenantId));
            out.put("languages", enabledLanguages(tenantId));
            return out;
        });
    }

    private List<String> enabledLanguages(UUID tenantId) {
        return tenantSettingsRepository.findById(tenantId)
                .map(TenantSettingsEntity::getAsrLanguages)
                .filter(s -> s != null && !s.isBlank())
                .map(s -> {
                    List<String> out = new ArrayList<>();
                    for (String part : s.split("[,\\s]+")) {
                        if (!part.isBlank()) {
                            out.add(part.trim().toLowerCase(Locale.ROOT));
                        }
                    }
                    return out.isEmpty() ? List.of("en", "hi") : out;
                })
                .orElse(List.of("en", "hi"));
    }

    private List<Map<String, Object>> compactFacts(List<Map<String, Object>> facts) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> f : facts) {
            if (out.size() >= 20) {
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", f.get("id") == null ? null : String.valueOf(f.get("id")));
            row.put("ruleId", f.get("rule_id") == null ? f.get("ruleId") : f.get("rule_id"));
            Object body = f.get("fact_text");
            if (body == null) {
                body = f.get("text");
            }
            if (body == null) {
                body = f.get("body");
            }
            String text = body == null ? "" : String.valueOf(body);
            // Cap length — ml-engine only needs lexical anchors
            if (text.length() > 240) {
                text = text.substring(0, 240);
            }
            row.put("text", text);
            out.add(row);
        }
        return out;
    }

    private List<String> directoryNameTokens(UUID tenantId) {
        try {
            return jdbc.query(
                    """
                    SELECT full_name FROM directory_employees
                    WHERE tenant_id = ? AND status = 'ACTIVE'
                    ORDER BY updated_at DESC NULLS LAST
                    LIMIT 200
                    """,
                    (rs, i) -> rs.getString(1),
                    tenantId
            );
        } catch (Exception e) {
            return List.of();
        }
    }

    private static UUID toUuid(Object raw) {
        if (raw instanceof UUID u) {
            return u;
        }
        if (raw == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "policy set id missing");
        }
        try {
            return UUID.fromString(String.valueOf(raw).trim());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "invalid policy set id");
        }
    }
}
