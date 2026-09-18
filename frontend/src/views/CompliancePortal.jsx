import { Card } from '@/components/ui/Card.jsx';

/**
 * Compliance portal — placeholder until P13.
 */
export function CompliancePortal() {
  return (
    <div className="flex h-full items-center justify-center p-6">
      <Card title="Compliance Portal" variant="elevated" className="max-w-md">
        <p className="text-sm text-sv-muted">
          Placeholder — audit ledger browser and forensic dossier viewer come later.
        </p>
        <p className="mt-2 font-mono text-[11px] text-sv-accent">view: compliance</p>
      </Card>
    </div>
  );
}

CompliancePortal.propTypes = {};
