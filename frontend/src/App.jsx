import { MicControl } from '@/components/MicControl.jsx';

/**
 * Minimal shell so P3.1 mic capture is exercisable before the full analyst console.
 */
export default function App() {
  return (
    <div className="mx-auto flex min-h-screen max-w-lg flex-col gap-6 px-4 py-10">
      <header>
        <p className="font-display text-xs uppercase tracking-[0.2em] text-sv-accent">
          SentinelVoice
        </p>
        <h1 className="mt-1 font-display text-2xl font-semibold text-sv-fg">
          Media capture
        </h1>
        <p className="mt-2 text-sm text-sv-muted">
          Streams 16 kHz mono PCM to the ml-engine ingest WebSocket. Audio never
          reaches the Java backend.
        </p>
      </header>
      <MicControl sessionId="browser-dev" />
    </div>
  );
}
