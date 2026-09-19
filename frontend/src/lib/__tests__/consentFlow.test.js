import { describe, expect, it } from 'vitest';
import {
  CONSENT_STEPS,
  canEnrol,
  canErase,
  nextConsentStep,
} from '@/lib/consentFlow.js';

describe('consentFlow state machine', () => {
  it('starts at NOTICE and grants to GRANTED', () => {
    expect(nextConsentStep(CONSENT_STEPS.NOTICE, 'grant')).toBe(CONSENT_STEPS.GRANTED);
    expect(canEnrol(CONSENT_STEPS.NOTICE)).toBe(false);
    expect(canEnrol(CONSENT_STEPS.GRANTED)).toBe(true);
  });

  it('enrols only after consent', () => {
    expect(nextConsentStep(CONSENT_STEPS.NOTICE, 'enrol')).toBe(CONSENT_STEPS.NOTICE);
    expect(nextConsentStep(CONSENT_STEPS.GRANTED, 'enrol')).toBe(CONSENT_STEPS.ENROLLED);
    expect(canErase(CONSENT_STEPS.ENROLLED)).toBe(true);
  });

  it('erase yields ERASED tombstone step; reset returns to NOTICE', () => {
    expect(nextConsentStep(CONSENT_STEPS.ENROLLED, 'erase')).toBe(CONSENT_STEPS.ERASED);
    expect(nextConsentStep(CONSENT_STEPS.ERASED, 'grant')).toBe(CONSENT_STEPS.GRANTED);
    expect(nextConsentStep(CONSENT_STEPS.ERASED, 'reset')).toBe(CONSENT_STEPS.NOTICE);
  });
});
