import { Link } from 'react-router-dom';

/**
 * Legacy audit explorer — use /app/audit. Kept for residual CompliancePortal imports.
 */
export function AuditChainExplorer() {
  return (
    <div className="space-y-2 text-sm text-sv-muted">
      <p>Prefer /app/audit (GET /api/v2/audit). Session-scoped v1 chain explorer is retired.</p>
      <Link className="text-sv-accent underline-offset-2 hover:underline" to="/app/audit">
        Open Audit
      </Link>
      <p className="text-[11px]">
        Dossier verify: <code>GET /api/v2/audit/verify-dossier?hash=</code>
      </p>
    </div>
  );
}
