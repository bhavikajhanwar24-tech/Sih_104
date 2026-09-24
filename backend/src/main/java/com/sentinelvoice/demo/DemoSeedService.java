package com.sentinelvoice.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelvoice.auth.PlatformAuthRepository;
import com.sentinelvoice.security.TenantContext;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * F18 — seed demo tenants through real DB writes (platform + tenant-scoped).
 */
@Service
public class DemoSeedService {

    private static final Logger log = LoggerFactory.getLogger(DemoSeedService.class);

    public static final String DEMO_PASSWORD = "DemoPassw0rd!";
    public static final String SLUG_BANK = "demo-bank";
    public static final String SLUG_INSURER = "demo-insurer";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final DemoProfileGuard profileGuard;
    private final ObjectMapper mapper;
    private final PlatformAuthRepository platformAuthRepository;

    public DemoSeedService(
            JdbcTemplate jdbc,
            PasswordEncoder passwordEncoder,
            DemoProfileGuard profileGuard,
            ObjectMapper mapper,
            PlatformAuthRepository platformAuthRepository
    ) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.profileGuard = profileGuard;
        this.mapper = mapper;
        this.platformAuthRepository = platformAuthRepository;
    }

    @Transactional
    public Map<String, Object> seed(String tenantKey) {
        profileGuard.requireAllowed();
        String key = tenantKey == null || tenantKey.isBlank() ? SLUG_BANK : tenantKey.trim().toLowerCase(Locale.ROOT);
        return switch (key) {
            case SLUG_BANK, "bank" -> seedBank();
            case SLUG_INSURER, "insurer" -> seedInsurer();
            case "all" -> {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("bank", seedBank());
                out.put("insurer", seedInsurer());
                yield out;
            }
            default -> throw new DemoSeedException("UNKNOWN_TENANT", "Unknown demo tenant key: " + key
                    + " (use demo-bank, demo-insurer, or all)");
        };
    }

    @Transactional
    public Map<String, Object> resetDemoTenants() {
        profileGuard.requireAllowed();
        List<UUID> ids = TenantContext.runAsPlatform(() -> jdbc.query(
                "SELECT fn_demo_list_tenants() AS id",
                (rs, i) -> rs.getObject("id", UUID.class)
        ));
        int deleted = 0;
        for (UUID id : ids) {
            wipeTenant(id);
            deleted++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deletedTenants", deleted);
        out.put("tenantIds", ids.stream().map(UUID::toString).toList());
        log.info("demo_reset deleted={} tenants={}", deleted, ids);
        return out;
    }

    private Map<String, Object> seedBank() {
        return seedOne(
                SLUG_BANK,
                "Demo Bank",
                "BANKING",
                "bank-style payments + verification policies",
                bankRules(),
                true
        );
    }

    private Map<String, Object> seedInsurer() {
        return seedOne(
                SLUG_INSURER,
                "Demo Insurer",
                "INSURANCE",
                "claims payout + beneficiary verification policies",
                insurerRules(),
                false
        );
    }

    private Map<String, Object> seedOne(
            String slug,
            String name,
            String industry,
            String policyName,
            List<Map<String, Object>> rules,
            boolean printPasswords
    ) {
        UUID existing = findDemoTenant(slug);
        if (existing != null) {
            wipeTenant(existing);
        } else {
            // leftover non-demo slug from failed prior seed
            UUID bySlug = findDemoTenant(slug);
            if (bySlug != null) {
                wipeTenant(bySlug);
            }
        }

        UUID tenantId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        UUID approverId = UUID.randomUUID();
        UUID analystId = UUID.randomUUID();
        UUID auditorId = UUID.randomUUID();
        String hash = passwordEncoder.encode(DEMO_PASSWORD);

        TenantContext.runAsPlatform(() -> {
            platformAuthRepository.registerTenant(
                    tenantId, name, slug, industry, "IN",
                    adminId, "admin@" + slug + ".demo", hash, "Demo Admin"
            );
            jdbc.query("SELECT fn_demo_flag_tenant(?)", rs -> null, tenantId);
            jdbc.query("SELECT fn_demo_insert_user(?, ?, ?, ?, ?, ?)", rs -> null,
                    approverId, tenantId, "approver@" + slug + ".demo", hash, "Demo Approver", "POLICY_APPROVER");
            jdbc.query("SELECT fn_demo_insert_user(?, ?, ?, ?, ?, ?)", rs -> null,
                    analystId, tenantId, "analyst@" + slug + ".demo", hash, "Demo Analyst", "ANALYST");
            jdbc.query("SELECT fn_demo_insert_user(?, ?, ?, ?, ?, ?)", rs -> null,
                    auditorId, tenantId, "auditor@" + slug + ".demo", hash, "Demo Auditor", "AUDITOR");
            return null;
        });

        TenantContext.runAs(tenantId, () -> {
            jdbc.query("SELECT set_config('app.tenant_id', ?, false)", rs -> null, tenantId.toString());
            seedDirectory(tenantId, slug);
            seedPolicies(tenantId, adminId, approverId, policyName, rules);
            if ("INSURANCE".equals(industry)) {
                customizeInsurerResponsePlan(tenantId);
            }
            seedConsents(tenantId);
            return null;
        });

        Map<String, Object> users = new LinkedHashMap<>();
        users.put("admin", Map.of("email", "admin@" + slug + ".demo", "password", DEMO_PASSWORD, "role", "TENANT_ADMIN"));
        users.put("approver", Map.of("email", "approver@" + slug + ".demo", "password", DEMO_PASSWORD, "role", "POLICY_APPROVER"));
        users.put("analyst", Map.of("email", "analyst@" + slug + ".demo", "password", DEMO_PASSWORD, "role", "ANALYST"));
        users.put("auditor", Map.of("email", "auditor@" + slug + ".demo", "password", DEMO_PASSWORD, "role", "AUDITOR"));

        if (printPasswords) {
            log.info("""
                    
                    ========== F18 DEMO CREDENTIALS (demo-bank) ==========
                    slug={}  password={}
                      admin@{}    TENANT_ADMIN
                      approver@{} POLICY_APPROVER
                      analyst@{}  ANALYST
                      auditor@{}  AUDITOR
                    ======================================================
                    """,
                    slug, DEMO_PASSWORD, slug + ".demo", slug + ".demo", slug + ".demo", slug + ".demo");
        } else {
            log.info("F18 demo tenant seeded slug={} password={} (same password for all demo users)", slug, DEMO_PASSWORD);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenantId", tenantId.toString());
        out.put("slug", slug);
        out.put("name", name);
        out.put("users", users);
        out.put("password", DEMO_PASSWORD);
        return out;
    }

    private UUID findDemoTenant(String slug) {
        return TenantContext.runAsPlatform(() -> {
            List<UUID> rows = jdbc.query(
                    "SELECT fn_demo_find_tenant(?) AS id",
                    (rs, i) -> rs.getObject("id", UUID.class),
                    slug
            );
            return rows.isEmpty() || rows.get(0) == null ? null : rows.get(0);
        });
    }

    private void wipeTenant(UUID tenantId) {
        TenantContext.runAsPlatform(() -> {
            jdbc.query("SELECT fn_demo_wipe_tenant(?)", rs -> null, tenantId);
            return null;
        });
    }

    private void customizeInsurerResponsePlan(UUID tenantId) {
        try {
            Map<String, Object> plan = defaultResponsePlan();
            @SuppressWarnings("unchecked")
            Map<String, Object> levels = new LinkedHashMap<>((Map<String, Object>) plan.get("levels"));
            levels.put("L3", List.of(
                    Map.of("action", "OPERATOR_ADVISORY", "trigger", "ON_ENTER"),
                    Map.of("action", "SEND_OOB_MFA", "trigger", "ON_ENTER")
            ));
            levels.put("L4", List.of(
                    Map.of("action", "HOLD_CALL", "trigger", "ON_ENTER"),
                    Map.of("action", "NOTIFY_COMPLIANCE", "trigger", "ON_ENTER")
            ));
            Map<String, Object> copy = new LinkedHashMap<>(plan);
            copy.put("levels", levels);
            copy.put("label", "Demo Insurer claims response");
            jdbc.update(
                    "UPDATE response_plans SET plan = ?::jsonb, content_sha256 = ?, updated_at = now() WHERE tenant_id = ? AND status = 'ACTIVE'",
                    toJson(copy), sha(copy), tenantId
            );
        } catch (Exception ex) {
            log.warn("demo_insurer_plan_skip err={}", ex.toString());
        }
    }

    private void seedDirectory(UUID tenantId, String slug) {
        UUID finance = UUID.randomUUID();
        UUID ops = UUID.randomUUID();
        UUID it = UUID.randomUUID();
        jdbc.update("INSERT INTO departments (id, tenant_id, name, created_at) VALUES (?,?,?,now())",
                finance, tenantId, "Finance");
        jdbc.update("INSERT INTO departments (id, tenant_id, name, created_at) VALUES (?,?,?,now())",
                ops, tenantId, "Operations");
        jdbc.update("INSERT INTO departments (id, tenant_id, name, created_at) VALUES (?,?,?,now())",
                it, tenantId, "IT Security");

        List<EmpSeed> people = demoEmployees(finance, ops, it);
        Map<String, UUID> byCode = new LinkedHashMap<>();
        int extBase = slug.equals(SLUG_BANK) ? 1001 : 2001;
        int i = 0;
        for (EmpSeed e : people) {
            UUID id = UUID.randomUUID();
            byCode.put(e.code(), id);
            jdbc.update("""
                    INSERT INTO employees (id, tenant_id, employee_code, full_name, email, department_id,
                      job_title, role_key, status, high_authority, fairness_tags, created_at, updated_at)
                    VALUES (?,?,?,?,?,?,?,?, 'ACTIVE', ?, '{}'::jsonb, now(), now())
                    """,
                    id, tenantId, e.code(), e.name(), e.email(), e.dept(),
                    e.title(), e.roleKey(), e.highAuth()
            );
            String e164 = "+9198765" + String.format("%05d", extBase + i);
            jdbc.update("""
                    INSERT INTO employee_phones (id, tenant_id, employee_id, e164, label, is_primary, sip_extension, created_at)
                    VALUES (?,?,?,?, 'MOBILE', TRUE, ?, now())
                    """,
                    UUID.randomUUID(), tenantId, id, e164, String.valueOf(extBase + i)
            );
            jdbc.update("""
                    INSERT INTO employee_authority (id, tenant_id, employee_id, action_type, max_amount_inr,
                      requires_dual_approval, allowed_channels, created_at)
                    VALUES (?,?,?,?,?,?, ARRAY['VOICE']::text[], now())
                    """,
                    UUID.randomUUID(), tenantId, id, e.actionType(), e.limitInr(), e.dual()
            );
            if (i < 4) {
                // SIP endpoints for 4 named softphones
                try {
                    jdbc.update("""
                            INSERT INTO sip_endpoints (id, tenant_id, employee_id, extension, username, password_ciphertext, status, created_at, updated_at)
                            VALUES (?,?,?,?,?,?, 'ACTIVE', now(), now())
                            """,
                            UUID.randomUUID(), tenantId, id,
                            String.valueOf(extBase + i),
                            slug.replace("-", "") + (extBase + i),
                            "enc:demo-placeholder"
                    );
                } catch (Exception ignored) {
                    /* optional */
                }
            }
            i++;
        }

        UUID cfo = byCode.get("E001");
        UUID clerk = byCode.get("E002");
        if (cfo != null && clerk != null) {
            jdbc.update("""
                    INSERT INTO known_relationships (id, tenant_id, from_employee_id, to_employee_id,
                      relationship_type, contact_count)
                    VALUES (?,?,?,?, 'MANAGER', 12)
                    """,
                    UUID.randomUUID(), tenantId, clerk, cfo
            );
        }

        UUID vendorId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO external_parties (id, tenant_id, name, type, verified, created_at, updated_at)
                VALUES (?,?,?, 'VENDOR', TRUE, now(), now())
                """,
                vendorId, tenantId, "Acme Wire Clearing Ltd"
        );
        jdbc.update("""
                INSERT INTO beneficiary_accounts (id, tenant_id, external_party_id, account_ref_hash, label, verified)
                VALUES (?,?,?,?,?, TRUE)
                """,
                UUID.randomUUID(), tenantId, vendorId, "acct_" + slug + "_payroll", "Payroll float"
        );
    }

    private void seedConsents(UUID tenantId) {
        List<UUID> empIds = jdbc.query(
                "SELECT id FROM employees WHERE tenant_id = ? LIMIT 25",
                (rs, i) -> rs.getObject("id", UUID.class),
                tenantId
        );
        for (UUID empId : empIds) {
            jdbc.update("""
                    INSERT INTO consents (id, tenant_id, employee_id, purpose, status, method,
                      notice_version, granted_at, created_at, updated_at)
                    VALUES (?,?,?, 'MONITORING', 'GRANTED', 'ADMIN', 1, now(), now(), now())
                    ON CONFLICT (tenant_id, employee_id, purpose) DO NOTHING
                    """,
                    UUID.randomUUID(), tenantId, empId
            );
        }
    }

    private void seedPolicies(UUID tenantId, UUID adminId, UUID approverId, String setName, List<Map<String, Object>> rules) {
        UUID setId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO policy_sets (id, tenant_id, name, status, version, created_at, updated_at, submitted_by, approved_by, approved_at, meta)
                VALUES (?,?,?, 'ACTIVE', 1, now(), now(), ?, ?, now(), ?::jsonb)
                """,
                setId, tenantId, setName, adminId, approverId,
                "{\"origin\":\"DEMO_FIXTURE\"}"
        );
        for (Map<String, Object> rule : rules) {
            UUID ruleId = UUID.randomUUID();
            try {
                        jdbc.update("""
                        INSERT INTO policy_rules (
                          id, tenant_id, policy_set_id, title, source, origin, status, severity,
                          rule_body, created_at, updated_at
                        ) VALUES (?,?,?,?, '{}'::jsonb, 'DEMO_FIXTURE', 'ACCEPTED', ?, ?::jsonb, now(), now())
                        """,
                        ruleId, tenantId, setId, rule.get("title"),
                        rule.getOrDefault("severity", "HIGH"),
                        mapper.writeValueAsString(rule.getOrDefault("dsl", Map.of("when", "true")))
                );
            } catch (Exception ex) {
                try {
                    jdbc.update("""
                            INSERT INTO policy_rules (id, tenant_id, policy_set_id, title, source, created_at)
                            VALUES (?,?,?,?, '{}'::jsonb, now())
                            """,
                            ruleId, tenantId, setId, rule.get("title")
                    );
                } catch (Exception ignored) {
                    log.warn("demo_policy_rule_skip title={} err={}", rule.get("title"), ex.toString());
                }
            }
        }
        try {
            byte[] pdf1 = loadOrBuildPdf(
                    "demo/policies/Payments_Authorisation_Policy.pdf",
                    "Payments Authorisation Policy",
                    "Verbal wire requests over INR 5L require dual approval."
            );
            byte[] pdf2 = loadOrBuildPdf(
                    "demo/policies/Phone_Verification_SOP.pdf",
                    "Phone Verification SOP",
                    "Never share OTP. Call back on directory number only."
            );
            insertDoc(tenantId, adminId, "Payments Authorisation Policy", "POLICY", pdf1);
            insertDoc(tenantId, adminId, "Phone Verification SOP", "SOP", pdf2);
        } catch (Exception ex) {
            log.warn("demo_pdf_seed_skip err={}", ex.toString());
        }
    }

    private void insertDoc(UUID tenantId, UUID uploadedBy, String title, String docType, byte[] pdf) throws Exception {
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pdf));
        jdbc.update("""
                INSERT INTO policy_documents (
                  id, tenant_id, title, doc_type, original_filename, mime_type, size_bytes, sha256,
                  content, status, uploaded_by, uploaded_at
                ) VALUES (?,?,?,?,?,?,?,?,?, 'EXTRACTED', ?, now())
                ON CONFLICT (tenant_id, sha256) DO NOTHING
                """,
                UUID.randomUUID(), tenantId, title, docType,
                title.replace(' ', '_') + ".pdf", "application/pdf", (long) pdf.length, sha,
                pdf, uploadedBy
        );
    }

    private byte[] loadOrBuildPdf(String classpath, String title, String body) throws Exception {
        try (var in = getClass().getClassLoader().getResourceAsStream(classpath)) {
            if (in != null) {
                return in.readAllBytes();
            }
        }
        return minimalPdf(title, body);
    }

    private static List<EmpSeed> demoEmployees(UUID finance, UUID ops, UUID it) {
        List<EmpSeed> list = new ArrayList<>();
        list.add(new EmpSeed("E001", "Priya Sharma", "priya.sharma@demo.local", finance, "CFO", "cfo", true, "WIRE_TRANSFER", 50_000_000d, true));
        list.add(new EmpSeed("E002", "Rahul Mehta", "rahul.mehta@demo.local", finance, "Treasury Clerk", "clerk", false, "WIRE_TRANSFER", 500_000d, false));
        list.add(new EmpSeed("E003", "Ananya Iyer", "ananya.iyer@demo.local", finance, "Controller", "controller", true, "WIRE_TRANSFER", 5_000_000d, true));
        list.add(new EmpSeed("E004", "Vikram Singh", "vikram.singh@demo.local", ops, "Ops Head", "ops_head", true, "APPROVE_PAYMENT", 2_000_000d, true));
        list.add(new EmpSeed("E005", "Sneha Patel", "sneha.patel@demo.local", ops, "Customer Support", "support", false, "VIEW_ACCOUNT", 0d, false));
        list.add(new EmpSeed("E006", "Arjun Desai", "arjun.desai@demo.local", it, "CISO", "ciso", true, "LOCK_ACCOUNT", 0d, true));
        for (int n = 7; n <= 25; n++) {
            UUID dept = n % 3 == 0 ? it : (n % 2 == 0 ? ops : finance);
            list.add(new EmpSeed(
                    "E" + String.format("%03d", n),
                    "Demo User " + n,
                    "user" + n + "@demo.local",
                    dept,
                    "Analyst",
                    "analyst",
                    false,
                    "VIEW_ACCOUNT",
                    100_000d,
                    false
            ));
        }
        return list;
    }

    private static List<Map<String, Object>> bankRules() {
        return List.of(
                Map.of("title", "Never share OTP on voice", "severity", "CRITICAL", "minLevel", 4,
                        "dsl", Map.of("intent", "OTP_HARVEST")),
                Map.of("title", "Wire >5L requires dual approval", "severity", "HIGH", "minLevel", 3,
                        "dsl", Map.of("action", "WIRE_TRANSFER", "amountMin", 500000)),
                Map.of("title", "Unknown CLI high-authority ask", "severity", "HIGH", "minLevel", 3,
                        "dsl", Map.of("cli", "UNKNOWN", "authority", "HIGH"))
        );
    }

    private static List<Map<String, Object>> insurerRules() {
        return List.of(
                Map.of("title", "Claims payout callback verify", "severity", "HIGH", "minLevel", 3,
                        "dsl", Map.of("intent", "CLAIM_PAYOUT")),
                Map.of("title", "Beneficiary change freeze", "severity", "CRITICAL", "minLevel", 4,
                        "dsl", Map.of("action", "BENEFICIARY_CHANGE"))
        );
    }

    private byte[] minimalPdf(String title, String body) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 14);
                cs.newLineAtOffset(50, 750);
                cs.showText(title);
                cs.endText();
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                cs.newLineAtOffset(50, 720);
                cs.showText(body.length() > 80 ? body.substring(0, 80) : body);
                cs.endText();
            }
            doc.save(bos);
            return bos.toByteArray();
        }
    }

    private static Map<String, Object> defaultResponsePlan() {
        Map<String, Object> levels = new LinkedHashMap<>();
        levels.put("L1", List.of(Map.of("action", "LOG_ONLY", "trigger", "ON_ENTER")));
        levels.put("L2", List.of(Map.of("action", "OPERATOR_ADVISORY", "trigger", "ON_ENTER")));
        levels.put("L3", List.of(Map.of("action", "SEND_OOB_MFA", "trigger", "ON_ENTER")));
        levels.put("L4", List.of(Map.of("action", "HOLD_CALL", "trigger", "ON_ENTER")));
        return Map.of("levels", levels);
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String sha(Object o) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(toJson(o).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record EmpSeed(
            String code, String name, String email, UUID dept, String title, String roleKey,
            boolean highAuth, String actionType, double limitInr, boolean dual
    ) {
    }
}
