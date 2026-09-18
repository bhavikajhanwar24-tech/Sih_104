/**
 * Reason code → evidence-family axis (mirrors backend ReasonCode).
 * Used by ReasonsList → Evidence panel highlight (P6.3).
 */
export const REASON_FAMILY = Object.freeze({
  CLI_CLAIM_MISMATCH: 'relationship',
  VOICEPRINT_FAIL: 'voice',
  SYNTHETIC_ARTIFACTS: 'voice',
  NO_BREATH: 'prosody',
  OVERSMOOTH_PROSODY: 'prosody',
  NO_ROOM_ACOUSTICS: 'channel',
  DOUBLE_COMPRESSION: 'channel',
  SECRECY_DEMAND: 'linguistic',
  URGENCY_PRESSURE: 'linguistic',
  AUTHORITY_INVOCATION: 'linguistic',
  POLICY_VIOLATION: 'transaction',
  FIRST_CONTACT: 'relationship',
  HIERARCHY_ANOMALY: 'relationship',
  PRESENCE_CONFLICT: 'relationship',
  CROSS_CHANNEL_PRECURSOR: 'relationship',
  VOICE_DRIFT: 'voice',
  CHALLENGE_LATENCY_FAIL: 'voice',
  WATERMARK_DETECTED: 'voice',
});

/**
 * @param {string | undefined | null} code
 * @returns {string | null}
 */
export function familyForReasonCode(code) {
  if (!code) return null;
  return REASON_FAMILY[code] ?? null;
}

/** Intervention level bands (up-thresholds from application.yml). */
export const LEVEL_BANDS = Object.freeze([
  Object.freeze({ level: 'LEVEL_1_SILENT', from: 0, to: 0.35, label: 'L1' }),
  Object.freeze({ level: 'LEVEL_2_SOFT_NUDGE', from: 0.35, to: 0.55, label: 'L2' }),
  Object.freeze({ level: 'LEVEL_3_STEP_UP_MFA', from: 0.55, to: 0.75, label: 'L3' }),
  Object.freeze({ level: 'LEVEL_4_AUTO_HOLD', from: 0.75, to: 0.9, label: 'L4' }),
  Object.freeze({ level: 'LEVEL_5_TERMINATE', from: 0.9, to: 1.0, label: 'L5' }),
]);

export const TRUNK_CLASSES = Object.freeze({
  INTERNAL_PBX: 'INTERNAL_PBX',
  REGISTERED_EXTERNAL: 'REGISTERED_EXTERNAL',
  UNREGISTERED_SIP: 'UNREGISTERED_SIP',
  WITHHELD: 'WITHHELD',
});

export const IDENTITY_VERDICTS = Object.freeze({
  VERIFIED: 'VERIFIED',
  INCONCLUSIVE: 'INCONCLUSIVE',
  IMPERSONATION_HUMAN: 'IMPERSONATION_HUMAN',
  IMPERSONATION_SYNTHETIC: 'IMPERSONATION_SYNTHETIC',
});
