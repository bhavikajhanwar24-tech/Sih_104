import { useCallback, useEffect, useState } from 'react';
import PropTypes from 'prop-types';

/**
 * @typedef {Object} PerturbationConfig
 * @property {boolean} enabled
 * @property {number | null} noise_snr_db
 * @property {number} pitch_shift_pct
 * @property {number} time_stretch
 * @property {string | null} codec
 * @property {number} reverb_t60_ms
 * @property {number} breath_rate_per_min
 * @property {number} packet_loss_pct
 * @property {number | null} band_limit_hz
 */

/** @returns {PerturbationConfig} */
export function defaultPerturbationConfig() {
  return {
    enabled: false,
    noise_snr_db: 20,
    pitch_shift_pct: 0,
    time_stretch: 1,
    codec: null,
    reverb_t60_ms: 0,
    breath_rate_per_min: 0,
    packet_loss_pct: 0,
    band_limit_hz: null,
  };
}

const CODECS = [
  { value: '', label: 'None' },
  { value: 'g711_ulaw', label: 'G.711 μ-law' },
  { value: 'g711_alaw', label: 'G.711 A-law' },
  { value: 'amr_nb_sim', label: 'AMR-NB (sim)' },
];

/**
 * Live-adjustable evasion intensities — defensive robustness only.
 *
 * @param {Object} props
 * @param {PerturbationConfig} props.value
 * @param {(next: PerturbationConfig) => void} props.onChange
 * @param {boolean} [props.disabled]
 */
export function PerturbationControls({ value, onChange, disabled = false }) {
  const set = useCallback(
    (patch) => {
      onChange({ ...value, ...patch });
    },
    [onChange, value],
  );

  return (
    <div className="flex flex-col gap-4 text-sm text-sv-fg">
      <label className="flex items-center gap-2 font-medium">
        <input
          type="checkbox"
          checked={Boolean(value.enabled)}
          disabled={disabled}
          onChange={(e) => set({ enabled: e.target.checked })}
          className="accent-sv-accent"
        />
        Apply to live ingest
      </label>

      <SliderRow
        label="Additive noise (SNR dB)"
        hint="Lower SNR = more noise — acoustic families should drop"
        min={5}
        max={30}
        step={1}
        value={value.noise_snr_db ?? 20}
        disabled={disabled || !value.enabled}
        onChange={(v) => set({ noise_snr_db: v })}
        format={(v) => `${v} dB`}
      />

      <SliderRow
        label="Pitch shift"
        min={-5}
        max={5}
        step={0.5}
        value={value.pitch_shift_pct}
        disabled={disabled || !value.enabled}
        onChange={(v) => set({ pitch_shift_pct: v })}
        format={(v) => `${v > 0 ? '+' : ''}${v}%`}
      />

      <SliderRow
        label="Time stretch"
        min={0.9}
        max={1.1}
        step={0.01}
        value={value.time_stretch}
        disabled={disabled || !value.enabled}
        onChange={(v) => set({ time_stretch: v })}
        format={(v) => `${v.toFixed(2)}×`}
      />

      <label className="flex flex-col gap-1">
        <span className="text-sv-muted">Codec round-trip</span>
        <select
          className="rounded border border-sv-border bg-sv-panel px-2 py-1.5 text-sv-fg"
          disabled={disabled || !value.enabled}
          value={value.codec ?? ''}
          onChange={(e) => set({ codec: e.target.value || null })}
        >
          {CODECS.map((c) => (
            <option key={c.value || 'none'} value={c.value}>
              {c.label}
            </option>
          ))}
        </select>
      </label>

      <SliderRow
        label="Synthetic reverb (T60)"
        min={0}
        max={700}
        step={10}
        value={value.reverb_t60_ms}
        disabled={disabled || !value.enabled}
        onChange={(v) => set({ reverb_t60_ms: v })}
        format={(v) => `${v} ms`}
      />

      <SliderRow
        label="Breath insertion rate"
        hint="Synthetic breath-like bursts — not cloned speech"
        min={0}
        max={24}
        step={1}
        value={value.breath_rate_per_min}
        disabled={disabled || !value.enabled}
        onChange={(v) => set({ breath_rate_per_min: v })}
        format={(v) => `${v}/min`}
      />

      <SliderRow
        label="Packet loss"
        min={0}
        max={30}
        step={1}
        value={value.packet_loss_pct}
        disabled={disabled || !value.enabled}
        onChange={(v) => set({ packet_loss_pct: v })}
        format={(v) => `${v}%`}
      />

      <SliderRow
        label="Band-limit cutoff"
        min={300}
        max={8000}
        step={100}
        value={value.band_limit_hz ?? 8000}
        disabled={disabled || !value.enabled}
        onChange={(v) => set({ band_limit_hz: v >= 7900 ? null : v })}
        format={(v) => (v >= 7900 ? 'off' : `${v} Hz`)}
      />
    </div>
  );
}

PerturbationControls.propTypes = {
  value: PropTypes.shape({
    enabled: PropTypes.bool,
    noise_snr_db: PropTypes.number,
    pitch_shift_pct: PropTypes.number,
    time_stretch: PropTypes.number,
    codec: PropTypes.string,
    reverb_t60_ms: PropTypes.number,
    breath_rate_per_min: PropTypes.number,
    packet_loss_pct: PropTypes.number,
    band_limit_hz: PropTypes.number,
  }).isRequired,
  onChange: PropTypes.func.isRequired,
  disabled: PropTypes.bool,
};

/**
 * @param {Object} props
 * @param {string} props.label
 * @param {string} [props.hint]
 * @param {number} props.min
 * @param {number} props.max
 * @param {number} props.step
 * @param {number} props.value
 * @param {boolean} props.disabled
 * @param {(v: number) => void} props.onChange
 * @param {(v: number) => string} props.format
 */
function SliderRow({ label, hint, min, max, step, value, disabled, onChange, format }) {
  return (
    <label className={`flex flex-col gap-1 ${disabled ? 'opacity-50' : ''}`}>
      <div className="flex items-baseline justify-between gap-2">
        <span className="text-sv-muted">{label}</span>
        <span className="font-mono text-[11px] text-sv-accent">{format(value)}</span>
      </div>
      {hint ? <span className="text-[11px] text-sv-muted/80">{hint}</span> : null}
      <input
        type="range"
        min={min}
        max={max}
        step={step}
        value={value}
        disabled={disabled}
        onChange={(e) => onChange(Number(e.target.value))}
        className="w-full accent-sv-accent"
      />
    </label>
  );
}

SliderRow.propTypes = {
  label: PropTypes.string.isRequired,
  hint: PropTypes.string,
  min: PropTypes.number.isRequired,
  max: PropTypes.number.isRequired,
  step: PropTypes.number.isRequired,
  value: PropTypes.number.isRequired,
  disabled: PropTypes.bool,
  onChange: PropTypes.func.isRequired,
  format: PropTypes.func.isRequired,
};

/**
 * Debounced PUT of perturbation config to the inference plane.
 *
 * @param {string | null} sessionId
 * @param {PerturbationConfig} config
 * @param {number} [delayMs]
 */
export function usePerturbationSync(sessionId, config, delayMs = 180) {
  const [error, setError] = useState(/** @type {string | null} */ (null));
  const [synced, setSynced] = useState(false);

  useEffect(() => {
    if (!sessionId) {
      setSynced(false);
      return undefined;
    }
    const t = window.setTimeout(async () => {
      try {
        const res = await fetch(`/engine/redteam/${encodeURIComponent(sessionId)}/config`, {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(config),
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        setError(null);
        setSynced(true);
      } catch (e) {
        setError(e instanceof Error ? e.message : 'sync failed');
        setSynced(false);
      }
    }, delayMs);
    return () => window.clearTimeout(t);
  }, [sessionId, config, delayMs]);

  return { error, synced };
}
