/**
 * Pure consent → enrol → erase state machine for Voice Passport UI + Vitest.
 */

export const CONSENT_STEPS = Object.freeze({
  NOTICE: 'NOTICE',
  GRANTED: 'GRANTED',
  ENROLLED: 'ENROLLED',
  ERASED: 'ERASED',
});

export const CONSENT_LANGS = Object.freeze({
  EN: 'EN',
  HI: 'HI',
});

/**
 * @param {string} step
 * @param {'grant'|'enrol'|'erase'|'reset'} action
 * @returns {string}
 */
export function nextConsentStep(step, action) {
  switch (action) {
    case 'grant':
      return step === CONSENT_STEPS.NOTICE || step === CONSENT_STEPS.ERASED
        ? CONSENT_STEPS.GRANTED
        : step;
    case 'enrol':
      return step === CONSENT_STEPS.GRANTED ? CONSENT_STEPS.ENROLLED : step;
    case 'erase':
      return step === CONSENT_STEPS.ENROLLED || step === CONSENT_STEPS.GRANTED
        ? CONSENT_STEPS.ERASED
        : step;
    case 'reset':
      return CONSENT_STEPS.NOTICE;
    default:
      return step;
  }
}

/**
 * Enrolment is gated on affirmative consent (DPDP §6).
 * @param {string} step
 * @returns {boolean}
 */
export function canEnrol(step) {
  return step === CONSENT_STEPS.GRANTED;
}

/**
 * Erasure requires an active enrolled passport.
 * @param {string} step
 * @returns {boolean}
 */
export function canErase(step) {
  return step === CONSENT_STEPS.ENROLLED;
}
