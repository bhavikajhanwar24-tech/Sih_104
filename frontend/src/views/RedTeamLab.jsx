import { Card } from '@/components/ui/Card.jsx';

/**
 * Red Team lab — placeholder until adversarial tooling lands.
 */
export function RedTeamLab() {
  return (
    <div className="flex h-full items-center justify-center p-6">
      <Card title="Red Team Lab" variant="elevated" className="max-w-md">
        <p className="text-sm text-sv-muted">
          Placeholder — codec round-trips, RVC sims, and adversarial replay live here.
        </p>
        <p className="mt-2 font-mono text-[11px] text-sv-accent">view: red-team</p>
      </Card>
    </div>
  );
}

RedTeamLab.propTypes = {};
