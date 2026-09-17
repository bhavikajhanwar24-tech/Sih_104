/**
 * Hand-written contract facade for the Presentation Plane.
 *
 * Generated siblings (do not edit):
 *   - contracts.d.ts  — ambient types for JSDoc / editor autocomplete
 *   - validators.js   — precompiled Ajv validators
 *
 * Regenerate with: npm run contracts
 */

import {
  validateTelemetryFrame as ajvValidateTelemetryFrame,
  validateFeatureFrame as ajvValidateFeatureFrame,
  validateAuditBlock as ajvValidateAuditBlock,
  validateSessionStartRequest as ajvValidateSessionStartRequest,
  validateChallengeIssued as ajvValidateChallengeIssued,
  validateChallengeResult as ajvValidateChallengeResult,
  validateInterventionOverride as ajvValidateInterventionOverride,
  validateCrossChannelEvent as ajvValidateCrossChannelEvent,
} from './validators.js';

// ---------------------------------------------------------------------------
// Ambient type re-exports for JSDoc (see contracts.d.ts)
// ---------------------------------------------------------------------------

/**
 * @typedef {TelemetryFrame} TelemetryFrame
 * @typedef {FeatureFrame} FeatureFrame
 * @typedef {AuditBlock} AuditBlock
 * @typedef {SessionStartRequest} SessionStartRequest
 * @typedef {ChallengeIssued} ChallengeIssued
 * @typedef {ChallengeResult} ChallengeResult
 * @typedef {InterventionOverride} InterventionOverride
 * @typedef {CrossChannelEvent} CrossChannelEvent
 */

/** Dev-only runtime validation. Off in production builds so demo FPS stays high. */
export const CONTRACT_VALIDATION = import.meta.env.DEV;

export const INTERVENTION_LEVELS = Object.freeze({
  LEVEL_1_SILENT: 'LEVEL_1_SILENT',
  LEVEL_2_SOFT_NUDGE: 'LEVEL_2_SOFT_NUDGE',
  LEVEL_3_STEP_UP_MFA: 'LEVEL_3_STEP_UP_MFA',
  LEVEL_4_AUTO_HOLD: 'LEVEL_4_AUTO_HOLD',
  LEVEL_5_TERMINATE: 'LEVEL_5_TERMINATE',
});

export const CHANNEL_PROFILES = Object.freeze({
  PSTN_NARROWBAND: 'PSTN_NARROWBAND',
  VOIP_WIDEBAND: 'VOIP_WIDEBAND',
  WEBRTC_WIDEBAND: 'WEBRTC_WIDEBAND',
});

export const RISK_STATES = Object.freeze({
  SCORED: 'SCORED',
  INSUFFICIENT_EVIDENCE: 'INSUFFICIENT_EVIDENCE',
  DEGRADED: 'DEGRADED',
});

export const REASON_SEVERITIES = Object.freeze({
  LOW: 'LOW',
  MEDIUM: 'MEDIUM',
  HIGH: 'HIGH',
  CRITICAL: 'CRITICAL',
});

export {
  ajvValidateFeatureFrame as validateFeatureFrame,
  ajvValidateTelemetryFrame as validateTelemetryFrame,
  ajvValidateAuditBlock as validateAuditBlock,
  ajvValidateSessionStartRequest as validateSessionStartRequest,
  ajvValidateChallengeIssued as validateChallengeIssued,
  ajvValidateChallengeResult as validateChallengeResult,
  ajvValidateInterventionOverride as validateInterventionOverride,
  ajvValidateCrossChannelEvent as validateCrossChannelEvent,
};

/**
 * Read a JSON Pointer-ish Ajv instancePath from an object.
 * @param {unknown} data
 * @param {string} instancePath
 * @returns {unknown}
 */
function valueAtInstancePath(data, instancePath) {
  if (!instancePath || instancePath === '') return data;
  const parts = instancePath.split('/').filter(Boolean).map((p) =>
    p.replace(/~1/g, '/').replace(/~0/g, '~'),
  );
  let cur = data;
  for (const part of parts) {
    if (cur == null || typeof cur !== 'object') return undefined;
    cur = /** @type {Record<string, unknown>} */ (cur)[part];
  }
  return cur;
}

/**
 * Format Ajv errors for the console.
 * @param {string} label
 * @param {import('ajv').ErrorObject[] | null | undefined} errors
 * @param {unknown} data
 * @returns {string}
 */
function formatAjvErrors(label, errors, data) {
  if (!errors || errors.length === 0) {
    return `${label} invalid`;
  }
  const parts = errors.map((err) => {
    const path = err.instancePath && err.instancePath.length > 0 ? err.instancePath : '/';
    const got = valueAtInstancePath(data, err.instancePath || '');
    const msg = err.message || 'failed validation';
    return `${path} ${msg} (got ${JSON.stringify(got)})`;
  });
  return `${label} invalid: ${parts.join('; ')}`;
}

/**
 * Validate a TelemetryFrame in dev. On failure, log every Ajv error and
 * RETURN THE FRAME ANYWAY — never throw.
 *
 * Contract drift must produce a visible console error, not a blank dashboard
 * mid-demo. Throwing would unmount the console; logging keeps the last-known
 * UI up while the team sees the schema mismatch.
 *
 * @param {TelemetryFrame} frame
 * @returns {TelemetryFrame}
 */
export function assertTelemetryFrame(frame) {
  if (!CONTRACT_VALIDATION) return frame;

  const ok = ajvValidateTelemetryFrame(frame);
  if (!ok) {
    console.error(
      formatAjvErrors('TelemetryFrame', ajvValidateTelemetryFrame.errors, frame),
    );
  }
  return frame;
}

/**
 * @param {TelemetryFrame | Record<string, never> | null | undefined} frame
 * @param {string} family
 * @returns {number | null}
 */
export function getFamilyScore(frame, family) {
  const score = frame?.families?.[family]?.score;
  return typeof score === 'number' ? score : null;
}

/**
 * @param {TelemetryFrame | Record<string, never> | null | undefined} frame
 * @returns {number}
 */
export function getSmoothedRisk(frame) {
  const value = frame?.risk?.smoothed;
  return typeof value === 'number' ? value : 0;
}

/**
 * @param {TelemetryFrame | Record<string, never> | null | undefined} frame
 * @returns {string}
 */
export function getLevel(frame) {
  const level = frame?.intervention?.level;
  return typeof level === 'string' ? level : INTERVENTION_LEVELS.LEVEL_1_SILENT;
}

/**
 * @param {TelemetryFrame | Record<string, never> | null | undefined} frame
 * @returns {Array<{ code?: string, severity?: string, text?: string }>}
 */
export function getTopReasons(frame) {
  const reasons = frame?.topReasons;
  return Array.isArray(reasons) ? reasons : [];
}

/**
 * @param {TelemetryFrame | Record<string, never> | null | undefined} frame
 * @param {string} family
 * @returns {boolean}
 */
export function isAvailable(frame, family) {
  return frame?.families?.[family]?.available === true;
}
