import PropTypes from 'prop-types';
import { useAudioCapture } from '@/hooks/useAudioCapture.js';

/**
 * Mic start/stop control with live RMS meter and browser-processing warning.
 *
 * @param {Object} props
 * @param {string} [props.sessionId='browser-dev']
 * @param {string} [props.wsUrl]
 * @param {string} [props.className]
 */
export function MicControl({ sessionId = 'browser-dev', wsUrl, className = '' }) {
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

  const meterPct = Math.min(100, Math.round(level * 100 * 4)); // gain for visibility
  const processingOn = browserProcessing?.anyEnabled === true;

  return (
    <div
      className={`flex flex-col gap-4 rounded-lg border border-sv-border bg-sv-panel/80 p-4 ${className}`}
    >
      <div className="flex items-center justify-between gap-3">
        <h2 className="font-display text-sm font-semibold tracking-wide text-sv-fg">
          Microphone capture
        </h2>
        {processingOn ? (
          <span
            className="rounded bg-risk-critical px-2 py-0.5 text-xs font-semibold uppercase tracking-wider text-white"
            title="Browser EC/NS/AGC is corrupting acoustic features"
          >
            Audio processing ON
          </span>
        ) : null}
      </div>

      <div className="flex items-center gap-3">
        <button
          type="button"
          onClick={() => (isCapturing ? stop() : start())}
          className={
            isCapturing
              ? 'rounded bg-risk-critical px-4 py-2 text-sm font-medium text-white hover:opacity-90'
              : 'rounded bg-risk-clear px-4 py-2 text-sm font-medium text-sv-bg hover:opacity-90'
          }
        >
          {isCapturing ? 'Stop' : 'Start'}
        </button>
        <span className="font-mono text-xs text-sv-muted">
          frames: {framesSent}
        </span>
      </div>

      <div className="flex flex-col gap-1">
        <div className="flex justify-between text-xs text-sv-muted">
          <span>Input level (RMS)</span>
          <span className="font-mono">{level.toFixed(3)}</span>
        </div>
        <div className="h-2 w-full overflow-hidden rounded bg-sv-bg">
          <div
            className="h-full bg-risk-clear transition-[width] duration-75"
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
        <p className="text-xs text-risk-critical">
          Warning: browser noiseSuppression is enabled. It strips micro-artifacts we
          detect and fabricates new ones — acoustic features are invalid.
        </p>
      ) : null}

      {warning ? (
        <p className="text-xs text-risk-elevated">{warning}</p>
      ) : null}

      {error ? (
        <p className="text-xs text-risk-critical" role="alert">
          {error}
        </p>
      ) : null}
    </div>
  );
}

MicControl.propTypes = {
  sessionId: PropTypes.string,
  wsUrl: PropTypes.string,
  className: PropTypes.string,
};
