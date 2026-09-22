-- F12 read-only verification queries (owner / TENANT_ADMIN via psql or SQL console).
-- Replace :tenant_id with the demo-bank tenant UUID when prompted.
-- Safe: SELECT only.

-- 0) Resolve demo-bank tenant (optional helper)
-- SELECT id, slug FROM tenants WHERE slug = 'demo-bank';

-- 1) call_sessions exist for tenant (F10 prerequisite)
SELECT
  count(*) AS call_sessions,
  count(*) FILTER (WHERE ended_at IS NULL) AS still_live,
  count(*) FILTER (WHERE ended_at IS NOT NULL) AS ended
FROM call_sessions
WHERE tenant_id = :tenant_id;

-- 2) session_ticks population (expect >0 after a FeatureFrame-backed call)
SELECT
  cs.sv_session_uuid,
  cs.peak_level,
  count(st.id) AS tick_count,
  min(st.t_ms) AS first_t_ms,
  max(st.t_ms) AS last_t_ms
FROM call_sessions cs
LEFT JOIN session_ticks st
  ON st.tenant_id = cs.tenant_id AND st.session_id = cs.sv_session_uuid
WHERE cs.tenant_id = :tenant_id
GROUP BY cs.sv_session_uuid, cs.peak_level, cs.started_at
ORDER BY cs.started_at DESC
LIMIT 20;

-- 3) session_reasons citations (rule_id / source_clause)
SELECT
  sr.session_id,
  sr.seq,
  sr.code,
  sr.rule_id,
  sr.policy_version,
  sr.contribution,
  sr.source_clause
FROM session_reasons sr
WHERE sr.tenant_id = :tenant_id
ORDER BY sr.created_at DESC
LIMIT 50;

-- 4) Tenant scoping: ticks must never cross tenants
SELECT count(*) AS cross_tenant_ticks
FROM session_ticks st
JOIN call_sessions cs ON cs.sv_session_uuid = st.session_id
WHERE st.tenant_id <> cs.tenant_id;

-- 5) Data minimisation: no obvious speech/media columns on F12 tables
SELECT table_name, column_name, data_type
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name IN (
    'call_sessions', 'session_ticks', 'session_reasons',
    'session_extractions', 'forensic_dossiers', 'session_actions'
  )
  AND (
    column_name ILIKE '%audio%'
    OR column_name ILIKE '%pcm%'
    OR column_name ILIKE '%wav%'
    OR column_name ILIKE '%transcript%'
    OR column_name ILIKE '%snippet%'
  )
ORDER BY 1, 2;

-- 6) forensic_dossiers digests for verify-dossier
SELECT
  session_id,
  content_sha256,
  pdf_sha256,
  audit_seq_from,
  audit_seq_to,
  audit_chain_valid,
  generated_at
FROM forensic_dossiers
WHERE tenant_id = :tenant_id
ORDER BY generated_at DESC
LIMIT 10;
