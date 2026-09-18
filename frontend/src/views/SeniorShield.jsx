import { Card } from '@/components/ui/Card.jsx';

/**
 * Senior Shield mode — placeholder until P11.2.
 */
export function SeniorShield() {
  return (
    <div className="flex h-full items-center justify-center p-6">
      <Card title="Senior Shield" variant="elevated" className="max-w-md">
        <p className="text-sm text-sv-muted">
          Placeholder — PWA / speakerphone shield mode lands in a later track.
        </p>
        <p className="mt-2 font-mono text-[11px] text-sv-accent">view: senior-shield</p>
      </Card>
    </div>
  );
}

SeniorShield.propTypes = {};
