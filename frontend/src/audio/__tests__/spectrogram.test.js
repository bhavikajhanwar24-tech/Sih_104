import { describe, expect, it } from 'vitest';
import { magnitudeToRgb } from '../spectrogram.js';
import { profileKind, profileLabel, WEIGHT_PROFILES } from '../../lib/evidenceMeta.js';

describe('spectrogram colour map', () => {
  it('returns RGB triples in range', () => {
    for (const t of [0, 0.2, 0.5, 0.8, 1]) {
      const [r, g, b] = magnitudeToRgb(t);
      expect(r).toBeGreaterThanOrEqual(0);
      expect(r).toBeLessThanOrEqual(255);
      expect(g).toBeGreaterThanOrEqual(0);
      expect(b).toBeLessThanOrEqual(255);
    }
  });
});

describe('evidence weight profiles', () => {
  it('sums to 1.0 for both profiles', () => {
    for (const kind of /** @type {const} */ (['wideband', 'narrowband'])) {
      const sum = Object.values(WEIGHT_PROFILES[kind]).reduce((a, b) => a + b, 0);
      expect(sum).toBeCloseTo(1.0, 5);
    }
  });

  it('labels narrowband distinctly', () => {
    expect(profileKind('PSTN_NARROWBAND')).toBe('narrowband');
    expect(profileLabel('narrowband')).toMatch(/acoustic weight reduced/i);
    expect(profileLabel('wideband')).toMatch(/full acoustic/i);
  });
});
