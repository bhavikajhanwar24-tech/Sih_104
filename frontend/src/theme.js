/**
 * SentinelVoice design tokens — single source for palette + risk ramp.
 * Components must not redefine colours; import from here.
 */

/** Surface / chrome (non-risk). */
export const palette = Object.freeze({
  bg: '#0a0e14',
  panel: '#131922',
  panelElevated: '#1a222e',
  border: '#243044',
  fg: '#e8eef7',
  muted: '#8b9bb4',
  accent: '#38bdf8',
  /** Infra live indicator — not risk green. */
  live: '#38bdf8',
  /** Infra fault / offline — not risk red (risk red is reserved). */
  fault: '#e2e8f0',
  /** Soft warn for non-risk alerts (contract chip uses risk.watch amber by request). */
  warn: '#fbbf24',
});

/**
 * Risk colour ramp — EXCLUSIVELY for risk score UI.
 * Do not use these for connection, buttons, or chrome.
 */
export const riskRamp = Object.freeze({
  clear: '#10b981',
  watch: '#f59e0b',
  elevated: '#f97316',
  critical: '#ef4444',
});

/**
 * Map a unit-interval risk score to a ramp colour.
 * @param {number} score 0..1
 * @returns {string} CSS hex
 */
export function riskColour(score) {
  const s = Number.isFinite(score) ? score : 0;
  if (s < 0.35) return riskRamp.clear;
  if (s < 0.55) return riskRamp.watch;
  if (s < 0.75) return riskRamp.elevated;
  return riskRamp.critical;
}

/** @deprecated Prefer {@link riskColour} (British spelling matches brief). */
export const riskColor = riskColour;

export const typography = Object.freeze({
  sans: '"IBM Plex Sans", ui-sans-serif, system-ui, sans-serif',
  mono: '"JetBrains Mono", "IBM Plex Mono", ui-monospace, monospace',
});

/**
 * Pre-seeded scenarios for SessionControl.
 * Fixture ids MUST match scenarios/*.yaml (Context §14).
 * live-browser / pstn-narrowband are live attach paths (not YAML fixtures).
 */
export const SEED_SCENARIOS = Object.freeze([
  Object.freeze({
    id: 'pstn-narrowband',
    title: 'Live SIP (AudioSocket)',
    description: 'Softphone → Asterisk → real FeatureFrames — gauge follows the call',
    kind: 'live-sip',
  }),
  Object.freeze({
    id: 'live-browser',
    title: 'Live browser mic',
    description: 'WebRTC mic → ml-engine ingest — real acoustic + spectrogram',
    kind: 'live-browser',
  }),
  Object.freeze({
    id: 'deepfake-ceo-wire',
    title: 'Deepfake CFO wire (₹50L)',
    description: 'Loads fixture + replays WAV into ml-engine (seeds BEC / first-contact)',
    kind: 'fixture',
  }),
  Object.freeze({
    id: 'legit-cfo',
    title: 'Legit CFO tax payment',
    description: 'Quiet on routine CXO traffic — should stay low',
    kind: 'fixture',
  }),
  Object.freeze({
    id: 'hinglish-grandparent',
    title: 'Grandparent scam (Hinglish)',
    description: 'Senior Shield path — fixture + audio replay',
    kind: 'fixture',
  }),
  Object.freeze({
    id: 'false-positive-stress',
    title: 'False-positive stress',
    description: 'Noisy genuine line — corroboration gate must NOT exceed L2',
    kind: 'fixture',
  }),
  Object.freeze({
    id: 'liveness-challenge',
    title: 'Liveness challenge',
    description: 'Amber Falcon interactive defeat of RVC',
    kind: 'fixture',
  }),
  Object.freeze({
    id: 'adversarial-evasion',
    title: 'Adversarial evasion',
    description: 'Room tone + breath — context holds when acoustics drop',
    kind: 'fixture',
  }),
]);

export const VIEWS = Object.freeze({
  ANALYST: 'analyst',
  SENIOR_SHIELD: 'senior-shield',
  COMPLIANCE: 'compliance',
  RED_TEAM: 'red-team',
});
