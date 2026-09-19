-- F6 verification (read-only). Paste into psql as a role that can SELECT tenant tables.
-- Replace <TENANT_UUID> with your tenant id (e.g. from SELECT id, name FROM tenants;).

SELECT set_config('app.tenant_id', '<TENANT_UUID>', false);

-- 4 / 5: policy sets by status (expect at most one ACTIVE)
SELECT id, name, version, status, submitted_by, approved_by, content_sha256, updated_at
FROM policy_sets
WHERE tenant_id = current_setting('app.tenant_id')::uuid
ORDER BY version DESC;

SELECT indexname, indexdef
FROM pg_indexes
WHERE tablename = 'policy_sets'
  AND indexname = 'uq_policy_sets_one_active';

-- Approver ≠ submitter trigger
SELECT tgname, pg_get_triggerdef(oid)
FROM pg_trigger
WHERE tgname = 'trg_policy_set_approver_ne_submitter';

-- 6: last 20 audit events for this tenant (compile / submit / approve / reject)
SELECT created_at,
       event_type,
       actor_type,
       actor_id,
       hash,
       payload->>'contentSha256' AS content_sha256,
       payload->>'policySetId'   AS policy_set_id,
       payload
FROM audit_blocks
WHERE tenant_id = current_setting('app.tenant_id')::uuid
  AND event_type IN (
        'POLICY_COMPILE_STARTED',
        'POLICY_COMPILE_COMPLETED',
        'POLICY_SET_SUBMITTED',
        'POLICY_SET_APPROVED',
        'POLICY_SET_REJECTED'
      )
ORDER BY created_at DESC
LIMIT 20;

-- Broader recent audit (any policy event) with chain hash
SELECT created_at, event_type, hash, payload->>'contentSha256' AS content_sha256
FROM audit_blocks
WHERE tenant_id = current_setting('app.tenant_id')::uuid
  AND event_type LIKE 'POLICY_%'
ORDER BY created_at DESC
LIMIT 20;

-- 7: keyword counts by category (policy_keywords)
SELECT category, COUNT(*) AS n
FROM policy_keywords
WHERE tenant_id = current_setting('app.tenant_id')::uuid
GROUP BY category
ORDER BY category;

-- Rules per set by status and origin
SELECT ps.version,
       ps.name,
       ps.status AS set_status,
       pr.status AS rule_status,
       pr.origin,
       COUNT(*) AS n
FROM policy_sets ps
JOIN policy_rules pr ON pr.policy_set_id = ps.id AND pr.tenant_id = ps.tenant_id
WHERE ps.tenant_id = current_setting('app.tenant_id')::uuid
GROUP BY ps.version, ps.name, ps.status, pr.status, pr.origin
ORDER BY ps.version DESC, pr.status, pr.origin;
