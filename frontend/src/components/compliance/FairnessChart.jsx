import { FairnessReport } from '@/components/fairness/FairnessReport.jsx';

/**
 * Compatibility wrapper for Compliance portal — F16 directory-tag fairness.
 */
export function FairnessChart() {
  return <FairnessReport endpoint="/api/v2/analytics/fairness" compact />;
}

/** Pure helper for vitest — whether the portal must show the synthetic badge. */
export function fairnessIsSynthetic(report) {
  if (!report || typeof report !== 'object') return false;
  if (report.synthetic === true) return true;
  if (report.results?.synthetic === true) return true;
  return false;
}
