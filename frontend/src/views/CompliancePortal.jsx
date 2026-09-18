import { Card } from '@/components/ui/Card.jsx';
import { AuditChainExplorer } from '@/components/compliance/AuditChainExplorer.jsx';
import { ConsentRegister } from '@/components/compliance/ConsentRegister.jsx';
import { RetentionDashboard } from '@/components/compliance/RetentionDashboard.jsx';
import { FairnessChart } from '@/components/compliance/FairnessChart.jsx';
import { DpdpMappingTable } from '@/components/compliance/DpdpMappingTable.jsx';
import { useSession } from '@/context/SessionContext.jsx';

/**
 * DPDP / RBI compliance portal (Context §13.3 / §13.5).
 * Audit chain explorer is the live H2 tamper-demo surface.
 */
export function CompliancePortal() {
  const { sessionId } = useSession();

  return (
    <div className="flex h-full min-h-0 flex-col gap-3 overflow-auto p-4">
      <header className="rounded border border-sv-border bg-sv-elevated/40 px-4 py-3">
        <p className="font-mono text-[10px] uppercase tracking-[0.2em] text-sv-accent">
          DPDP Act 2023 · RBI IT governance
        </p>
        <h1 className="mt-1 font-display text-lg font-semibold text-sv-fg">Compliance Portal</h1>
        <p className="mt-1 max-w-3xl text-sm text-sv-muted">
          Audit chain, consent register, retention counters (real queries), fairness FPR gaps, and
          the §13.3 mapping table. Tamper a row in the H2 console, then Verify Chain below.
        </p>
      </header>

      <div className="grid gap-3 xl:grid-cols-2">
        <Card title="Audit chain explorer" variant="elevated" className="xl:col-span-2">
          <AuditChainExplorer initialSessionId={sessionId} />
        </Card>

        <Card title="Consent register" variant="elevated">
          <ConsentRegister />
        </Card>

        <Card title="Retention dashboard" variant="elevated">
          <RetentionDashboard />
        </Card>

        <Card title="Fairness · FPR parity (§13.5)" variant="elevated">
          <FairnessChart />
        </Card>

        <Card title="DPDP §13.3 mapping" variant="elevated">
          <DpdpMappingTable />
        </Card>
      </div>
    </div>
  );
}

CompliancePortal.propTypes = {};
