import { describe, expect, it } from 'vitest';
import { peakFrequencyHz, resample } from '../resample.js';

/**
 * Generate a unit-amplitude sine.
 * @param {number} freqHz
 * @param {number} sampleRate
 * @param {number} durationSec
 * @returns {Float32Array}
 */
function sine(freqHz, sampleRate, durationSec) {
  const n = Math.floor(sampleRate * durationSec);
  const out = new Float32Array(n);
  const omega = (2 * Math.PI * freqHz) / sampleRate;
  for (let i = 0; i < n; i += 1) {
    out[i] = Math.sin(omega * i);
  }
  return out;
}

describe('resample', () => {
  it('keeps a 1 kHz sine peak at 1 kHz after 48 kHz → 16 kHz', () => {
    const fromRate = 48000;
    const toRate = 16000;
    const tone = sine(1000, fromRate, 0.25);
    const out = resample(tone, fromRate, toRate);

    expect(out.length).toBeGreaterThan(toRate * 0.2);

    // Use a stable interior window to avoid FIR edge effects.
    const start = Math.floor(out.length * 0.1);
    const end = Math.floor(out.length * 0.9);
    const window = out.subarray(start, end);
    const peak = peakFrequencyHz(window, toRate);

    // Allow ±40 Hz tolerance (DFT bin width at ~0.2 s ≈ 5 Hz; FIR/phase slack).
    expect(Math.abs(peak - 1000)).toBeLessThan(40);
  });

  it('is a no-op copy when rates match', () => {
    const input = sine(440, 16000, 0.05);
    const out = resample(input, 16000, 16000);
    expect(out).toHaveLength(input.length);
    expect(out[10]).toBeCloseTo(input[10], 5);
  });
});
