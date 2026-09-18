import { useEffect } from 'react';
import PropTypes from 'prop-types';
import { useAudioCapture } from '@/hooks/useAudioCapture.js';
import { Badge } from '@/components/ui/Badge.jsx';

/**
 * Mic start/stop control with live RMS meter and browser-processing warning.
 *
 * @param {Object} props
 * @param {string} [props.sessionId='browser-dev']
 * @param {string} [props.wsUrl]
 * @param {boolean} [props.autoStart=false]  start capture when sessionId is set
 * @param {string} [props.className]
 */
export function MicControl({
  sessionId = 'browser-dev',
  wsUrl,
  autoStart = false,
  className = '',
}) {
  const {
    start,
    stop,
    isCapturing,
    level,
    error,
    framesSent,
    warning,
    browserProcessing,
  } = useAudioCapture({ sessionId, wsUrl });

  useEffect(() => {
    if (!autoStart || !sessionId) return undefined;
    let cancelled = false;
    (async () => {
      try {
        if (!cancelled) await start();
      } catch {
        /* onError already surfaced via capture hook */
      }
    })();
    return () => {
      cancelled = true;
      stop();
    };
  }, [autoStart, sessionId, start, stop]);

  const meterPct = Math.min(100, Math.round(level * 100 * 4));
  const processingOn = browserProcessing?.anyEnabled === true;

  return (
    <div
      className={`flex flex-col gap-3 rounded border border-sv-border bg-sv-panel p-3 ${className}`}
    >
      <div className="flex items-center justify-between gap-2">
        <h2 className="font-display text-xs font-semibold uppercase tracking-wider text-sv-muted">
          Mic capture
        </h2>
        {processingOn ? (
          <Badge variant="fault" title="Browser EC/NS/AGC corrupts acoustic features">
            Processing ON
          </Badge>
        ) : null}
      </div>

      <div className="flex items-center gap-3">
        <button
          type="button"
          onClick={() => (isCapturing ? stop() : start())}
          className={
            isCapturing
              ? 'rounded bg-sv-fault px-3 py-1.5 text-xs font-medium text-sv-bg hover:opacity-90'
              : 'rounded bg-sv-accent px-3 py-1.5 text-xs font-medium text-sv-bg hover:opacity-90'
          }
        >
          {isCapturing ? 'Stop' : 'Start'}
        </button>
        <span className="font-mono text-[11px] tabular-nums text-sv-muted">
          frames {framesSent}
        </span>
      </div>

      <div className="flex flex-col gap-1">
        <div className="flex justify-between text-[10px] text-sv-muted">
          <span>Input level (RMS)</span>
          <span className="font-mono tabular-nums">{level.toFixed(3)}</span>
        </div>
        <div className="h-1.5 w-full overflow-hidden rounded bg-sv-bg">
          <div
            className="h-full bg-sv-accent transition-[width] duration-75"
            style={{ width: `${meterPct}%` }}
            role="meter"
            aria-valuenow={meterPct}
            aria-valuemin={0}
            aria-valuemax={100}
            aria-label="Microphone input level"
          />
        </div>
      </div>

      {browserProcessing?.noiseSuppression ? (
        <p className="text-[11px] text-sv-fault">
          Browser noiseSuppression is on — acoustic features are invalid.
        </p>
      ) : null}

      {warning ? <p className="text-[11px] text-sv-muted">{warning}</p> : null}

      {error ? (
        <p className="text-[11px] text-sv-fault" role="alert">
          {error}
        </p>
      ) : null}
    </div>
  );
}

MicControl.propTypes = {
  sessionId: PropTypes.string,
  wsUrl: PropTypes.string,
  autoStart: PropTypes.bool,
  className: PropTypes.string,
};
