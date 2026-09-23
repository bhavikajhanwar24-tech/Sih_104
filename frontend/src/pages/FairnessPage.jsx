import { Navigate } from 'react-router-dom';
import { useAuth } from '@/context/AuthContext.jsx';
import { FairnessReport } from '@/components/fairness/FairnessReport.jsx';

/**
 * F16 — dedicated fairness page (directory tags + labelled FP rates + Wilson CIs).
 */
export function FairnessPage() {
  const { hasPermission } = useAuth();
  const canRead =
    hasPermission('analytics:read') ||
    hasPermission('compliance:read') ||
    hasPermission('dashboard:read');

  if (!canRead) {
    return <Navigate to="/app" replace />;
  }

  return (
    <div className="space-y-4 p-6">
      <header>
        <h1 className="font-display text-2xl font-semibold text-sv-fg">Fairness</h1>
        <p className="mt-1 text-sm text-sv-muted">
          False-positive rates by optional directory group tags — with confidence intervals and a
          hard minimum group size.
        </p>
      </header>
      <FairnessReport endpoint="/api/v2/analytics/fairness" />
    </div>
  );
}
