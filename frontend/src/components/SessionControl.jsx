import { useEffect, useState } from 'react';
import PropTypes from 'prop-types';
import { CHANNEL_PROFILES } from '@/contracts';
import { useSession } from '@/context/SessionContext.jsx';
import { SEED_SCENARIOS } from '@/theme.js';
import { Badge } from '@/components/ui/Badge.jsx';
import { Card } from '@/components/ui/Card.jsx';
import { Stat } from '@/components/ui/Stat.jsx';

const PROFILE_OPTIONS = [
  CHANNEL_PROFILES.WEBRTC_WIDEBAND,
  CHANNEL_PROFILES.VOIP_WIDEBAND,
  CHANNEL_PROFILES.PSTN_NARROWBAND,
];

/**
 * Start/stop Decision Plane session, channel profile, scenario, elapsed timer.
 *
 * @param {Object} props
 * @param {string} [props.className]
 */
export function SessionControl({ className = '' }) {
  const {
    sessionId,
    isRunning,
    channelProfile,
    scenarioId,
    startedAtMs,
    sessionError,
    setChannelProfile,
    setScenarioId,
    startSession,
    stopSession,
  } = useSession();

  const [elapsed, setElapsed] = useState('00:00');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!isRunning || startedAtMs == null) {
      setElapsed('00:00');
      return undefined;
    }
    const tick = () => setElapsed(formatElapsed(Date.now() - startedAtMs));
    tick();
    const id = setInterval(tick, 1000);
    return () => clearInterval(id);
  }, [isRunning, startedAtMs]);

  async function onToggle() {
    setBusy(true);
    try {
      if (isRunning) await stopSession();
      else await startSession();
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card variant="elevated" className={className}>
      <div className="flex flex-wrap items-end gap-3">
        <label className="flex min-w-[9rem] flex-col gap-1 text-xs text-sv-muted">
          Channel profile
          <select
            className="rounded border border-sv-border bg-sv-bg px-2 py-1.5 font-mono text-xs text-sv-fg disabled:opacity-50"
            value={channelProfile}
            disabled={isRunning || busy}
            onChange={(e) => setChannelProfile(e.target.value)}
          >
            {PROFILE_OPTIONS.map((p) => (
              <option key={p} value={p}>
                {p}
              </option>
            ))}
          </select>
        </label>

        <label className="flex min-w-[12rem] flex-1 flex-col gap-1 text-xs text-sv-muted">
          Scenario
          <select
            className="rounded border border-sv-border bg-sv-bg px-2 py-1.5 font-mono text-xs text-sv-fg disabled:opacity-50"
            value={scenarioId}
            disabled={isRunning || busy}
            onChange={(e) => setScenarioId(e.target.value)}
          >
            {SEED_SCENARIOS.map((s) => (
              <option key={s.id} value={s.id}>
                {s.title}
              </option>
            ))}
          </select>
        </label>

        <button
          type="button"
          disabled={busy}
          onClick={onToggle}
          className={`rounded px-4 py-1.5 text-sm font-medium text-sv-bg disabled:opacity-50 ${
            isRunning ? 'bg-sv-fault text-sv-bg' : 'bg-sv-accent text-sv-bg'
          }`}
        >
          {isRunning ? 'Stop session' : 'Start session'}
        </button>

        <Stat label="Session ID" value={sessionId ?? '—'} variant="mono" />
        <Stat label="Elapsed" value={elapsed} variant="mono" />
        <Badge variant={isRunning ? 'live' : 'neutral'}>
          {isRunning ? 'live' : 'idle'}
        </Badge>
      </div>
      {sessionError ? (
        <p className="mt-2 text-xs text-sv-fault" role="alert">
          {sessionError}
        </p>
      ) : null}
    </Card>
  );
}

SessionControl.propTypes = {
  className: PropTypes.string,
};

/**
 * @param {number} ms
 * @returns {string}
 */
function formatElapsed(ms) {
  const totalSec = Math.max(0, Math.floor(ms / 1000));
  const m = Math.floor(totalSec / 60);
  const s = totalSec % 60;
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
}
