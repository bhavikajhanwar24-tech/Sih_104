-- F16: alert labelling, analytics views, optional fairness tags on directory

-- ---------------------------------------------------------------------------
-- session_labels
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS session_labels (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants (id),
    session_id      UUID NOT NULL,
    label           TEXT NOT NULL
        CHECK (label IN ('CONFIRMED_FRAUD', 'FALSE_POSITIVE', 'BENIGN_HIGH_RISK', 'UNKNOWN')),
    labelled_by     UUID,
    note            TEXT,
    labelled_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, session_id)
);

CREATE INDEX IF NOT EXISTS idx_session_labels_tenant_label
    ON session_labels (tenant_id, label, labelled_at DESC);
CREATE INDEX IF NOT EXISTS idx_session_labels_tenant_labelled_at
    ON session_labels (tenant_id, labelled_at DESC);

ALTER TABLE session_labels ENABLE ROW LEVEL SECURITY;
ALTER TABLE session_labels FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS session_labels_tenant_isolation ON session_labels;
CREATE POLICY session_labels_tenant_isolation ON session_labels
    USING (tenant_id = app_current_tenant_id())
    WITH CHECK (tenant_id = app_current_tenant_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON session_labels TO sv_app;

-- ---------------------------------------------------------------------------
-- Directory fairness tags (optional, tenant-provided — never inferred from voice)
-- ---------------------------------------------------------------------------
ALTER TABLE employees
    ADD COLUMN IF NOT EXISTS fairness_tags JSONB NOT NULL DEFAULT '{}'::jsonb;

COMMENT ON COLUMN employees.fairness_tags IS
    'Optional non-identifying group tags: language, region, gender, ageBand. Tenant-supplied only.';

-- Analytics knobs on tenant_settings
ALTER TABLE tenant_settings
    ADD COLUMN IF NOT EXISTS analytics_rule_fire_rate_max DOUBLE PRECISION NOT NULL DEFAULT 0.35
        CHECK (analytics_rule_fire_rate_max > 0 AND analytics_rule_fire_rate_max <= 1),
    ADD COLUMN IF NOT EXISTS analytics_rule_fp_contrib_max DOUBLE PRECISION NOT NULL DEFAULT 0.40
        CHECK (analytics_rule_fp_contrib_max > 0 AND analytics_rule_fp_contrib_max <= 1),
    ADD COLUMN IF NOT EXISTS analytics_fairness_min_group INT NOT NULL DEFAULT 30
        CHECK (analytics_fairness_min_group >= 5 AND analytics_fairness_min_group <= 500),
    ADD COLUMN IF NOT EXISTS analytics_label_warn_below INT NOT NULL DEFAULT 30
        CHECK (analytics_label_warn_below >= 5 AND analytics_label_warn_below <= 500);

-- ---------------------------------------------------------------------------
-- Views (tenant_id always present; app filters — no personal content)
-- ---------------------------------------------------------------------------
CREATE OR REPLACE VIEW v_analytics_labelled_sessions AS
SELECT
    cs.tenant_id,
    cs.id AS session_id,
    cs.sv_session_uuid,
    cs.started_at,
    cs.ended_at,
    cs.peak_score,
    cs.peak_level,
    cs.caller_employee_id,
    sl.label,
    sl.labelled_by,
    sl.labelled_at,
    EXTRACT(EPOCH FROM (sl.labelled_at - cs.started_at)) AS time_to_label_sec
FROM call_sessions cs
JOIN session_labels sl
  ON sl.tenant_id = cs.tenant_id AND sl.session_id = cs.id;

CREATE OR REPLACE VIEW v_analytics_alert_daily AS
SELECT
    tenant_id,
    (started_at AT TIME ZONE 'UTC')::date AS day_utc,
    COUNT(*) FILTER (
        WHERE peak_level LIKE 'LEVEL_3%'
           OR peak_level LIKE 'LEVEL_4%'
           OR peak_level LIKE 'LEVEL_5%'
           OR peak_level IN ('L3', 'L4', 'L5')
           OR COALESCE(peak_score, 0) >= 0.70
    ) AS alerts_l3_plus,
    COUNT(*) AS calls_total
FROM call_sessions
WHERE started_at IS NOT NULL
GROUP BY tenant_id, (started_at AT TIME ZONE 'UTC')::date;

-- Materialised summary refreshed by scheduler (tenant-scoped rows)
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_analytics_rule_fires AS
SELECT
    st.tenant_id,
    unnest(st.fired_rule_ids) AS rule_id,
    COUNT(DISTINCT st.session_id) AS sessions_fired
FROM session_ticks st
WHERE cardinality(st.fired_rule_ids) > 0
GROUP BY st.tenant_id, unnest(st.fired_rule_ids);

CREATE UNIQUE INDEX IF NOT EXISTS uq_mv_analytics_rule_fires
    ON mv_analytics_rule_fires (tenant_id, rule_id);

CREATE OR REPLACE FUNCTION fn_refresh_analytics_mvs()
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    REFRESH MATERIALIZED VIEW CONCURRENTLY mv_analytics_rule_fires;
EXCEPTION
    WHEN OTHERS THEN
        -- First refresh / no concurrent index yet
        REFRESH MATERIALIZED VIEW mv_analytics_rule_fires;
END;
$$;

REVOKE ALL ON FUNCTION fn_refresh_analytics_mvs() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION fn_refresh_analytics_mvs() TO sv_app;

GRANT SELECT ON v_analytics_labelled_sessions TO sv_app;
GRANT SELECT ON v_analytics_alert_daily TO sv_app;
GRANT SELECT ON mv_analytics_rule_fires TO sv_app;

-- Seed label rows from existing F12 review_status (idempotent)
INSERT INTO session_labels (tenant_id, session_id, label, labelled_by, labelled_at)
SELECT cs.tenant_id, cs.id,
       cs.review_status,
       cs.reviewed_by,
       COALESCE(cs.reviewed_at, cs.created_at, now())
FROM call_sessions cs
WHERE cs.review_status IN ('FALSE_POSITIVE', 'CONFIRMED_FRAUD')
ON CONFLICT (tenant_id, session_id) DO NOTHING;
