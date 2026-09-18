/**
 * Dual-profile fusion weights (Context §9.1 / §10.4 — mirrors application.yml).
 * Frame.families[*].weight is the live renormalised value; these are the base tables.
 */

export const EVIDENCE_FAMILIES = Object.freeze([
  'voice',
  'channel',
  'prosody',
  'linguistic',
  'transaction',
  'relationship',
]);

export const WEIGHT_PROFILES = Object.freeze({
  wideband: Object.freeze({
    voice: 0.24,
    channel: 0.08,
    prosody: 0.13,
    linguistic: 0.25,
    transaction: 0.18,
    relationship: 0.12,
  }),
  narrowband: Object.freeze({
    voice: 0.15,
    channel: 0.10,
    prosody: 0.12,
    linguistic: 0.29,
    transaction: 0.20,
    relationship: 0.14,
  }),
});

/** Benign reference polygon for the radar (not a measured call). */
export const BENIGN_BASELINE = Object.freeze({
  voice: 0.12,
  channel: 0.1,
  prosody: 0.14,
  linguistic: 0.1,
  transaction: 0.08,
  relationship: 0.1,
});

/**
 * @param {string | null | undefined} channelProfile
 * @returns {'wideband'|'narrowband'}
 */
export function profileKind(channelProfile) {
  return channelProfile === 'PSTN_NARROWBAND' ? 'narrowband' : 'wideband';
}

/**
 * @param {'wideband'|'narrowband'} kind
 * @returns {string}
 */
export function profileLabel(kind) {
  if (kind === 'narrowband') {
    return 'Narrowband profile — acoustic weight reduced';
  }
  return 'Wideband profile — full acoustic stack';
}

/**
 * @param {TelemetryFrame | null | undefined} frame
 * @param {string} family
 * @returns {{ score: number, weight: number, contribution: number, available: boolean }}
 */
export function readFamily(frame, family) {
  const f = frame?.families?.[family];
  return {
    score: typeof f?.score === 'number' ? f.score : 0,
    weight: typeof f?.weight === 'number' ? f.weight : 0,
    contribution: typeof f?.contribution === 'number' ? f.contribution : 0,
    available: f?.available === true,
  };
}
