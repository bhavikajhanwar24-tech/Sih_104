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

/** Pre-seeded demo scenarios for SessionControl. */
export const SEED_SCENARIOS = Object.freeze([
  Object.freeze({
    id: 'live-browser',
    title: 'Live browser mic',
    description: 'WebRTC tap — agent workstation / Senior Shield path',
  }),
  Object.freeze({
    id: 'cfo-wire-inr',
    title: 'CFO wire (₹50L)',
    description: 'Classic deepfake vishing — urgency + secrecy + wire ask',
  }),
  Object.freeze({
    id: 'vendor-callback',
    title: 'Vendor callback',
    description: 'Callback to a known vendor number with spoofed CLI',
  }),
  Object.freeze({
    id: 'pstn-narrowband',
    title: 'Live SIP (AudioSocket)',
    description: 'Softphone → Asterisk tap — Analyst attaches; gauge follows the call',
  }),
]);

export const VIEWS = Object.freeze({
  ANALYST: 'analyst',
  SENIOR_SHIELD: 'senior-shield',
  COMPLIANCE: 'compliance',
  RED_TEAM: 'red-team',
});
