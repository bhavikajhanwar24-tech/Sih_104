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
            // ACTIVE runtime rules for Stage B LLM rule-break judgment (not keywords only).
            List<Map<String, Object>> rules = List.of();
            if (active != null && active.get("id") != null) {
                UUID setId = toUuid(active.get("id"));
                rules = compactRules(policySetRepository.listRuntimeRules(tenantId, setId));
            }
            out.put("rules", rules);
            // Skip directory name scan on the hot lexicon path — remote DB made this ~5s+
            // and caused ml-engine's 3s lexicon timeout → lexicon_kw=0 (no keyword hits).
            out.put("nameTokens", List.of());
            out.put("languages", enabledLanguages(tenantId));
            return out;
        });
    }

    private List<Map<String, Object>> compactRules(List<Map<String, Object>> rules) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rules) {
            if (out.size() >= 24) {
                break;
            }
            Object rid = r.get("ruleId");
            if (rid == null || String.valueOf(rid).isBlank()) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ruleId", String.valueOf(rid));
            row.put("title", r.get("title") == null ? "" : String.valueOf(r.get("title")));
            String fires = r.get("firesWhen") == null ? "" : String.valueOf(r.get("firesWhen"));
            String plain = r.get("plainEnglish") == null ? "" : String.valueOf(r.get("plainEnglish"));
            String doesNot = r.get("doesNotFireWhen") == null ? "" : String.valueOf(r.get("doesNotFireWhen"));
            if (fires.length() > 320) {
                fires = fires.substring(0, 320);
            }
            if (plain.length() > 320) {
                plain = plain.substring(0, 320);
            }
            if (doesNot.length() > 240) {
                doesNot = doesNot.substring(0, 240);
            }
            row.put("firesWhen", fires);
            row.put("doesNotFireWhen", doesNot);
            row.put("plainEnglish", plain);
            Object then = r.get("then");
            if (then instanceof Map<?, ?> m && m.get("minLevel") != null) {
                row.put("minLevel", m.get("minLevel"));
            } else if (r.get("minLevel") != null) {
                row.put("minLevel", r.get("minLevel"));
            }
            out.add(row);
        }
        return out;
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
