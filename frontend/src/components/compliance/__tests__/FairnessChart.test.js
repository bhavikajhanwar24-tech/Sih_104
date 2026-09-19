import { describe, expect, it } from 'vitest';
import { fairnessIsSynthetic } from '../FairnessChart.jsx';

describe('fairnessIsSynthetic', () => {
  it('is true when API sets synthetic on the report', () => {
    expect(fairnessIsSynthetic({ status: 'ok', synthetic: true, results: {} })).toBe(true);
  });

  it('is true when shaped results carry synthetic', () => {
    expect(fairnessIsSynthetic({ status: 'ok', results: { synthetic: true } })).toBe(true);
  });

  it('is false for disk / field results', () => {
    expect(fairnessIsSynthetic({ status: 'ok', synthetic: false, results: { synthetic: false } })).toBe(
      false,
    );
  });

  it('is false when evaluation not run', () => {
    expect(fairnessIsSynthetic({ status: 'EVALUATION_NOT_RUN', results: null })).toBe(false);
  });
});
