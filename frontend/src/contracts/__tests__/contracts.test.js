import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  assertTelemetryFrame,
  getFamilyScore,
  getLevel,
  getSmoothedRisk,
  getTopReasons,
  isAvailable,
  validateTelemetryFrame,
  INTERVENTION_LEVELS,
} from '../index.js';

const __dirname = dirname(fileURLToPath(import.meta.url));
const fixturePath = join(
  __dirname,
  '../../../../docs/contracts/fixtures/TelemetryFrame.example.json',
);

/** @returns {TelemetryFrame} */
function loadGoodFixture() {
  return JSON.parse(readFileSync(fixturePath, 'utf8'));
}

describe('contracts', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('validateTelemetryFrame accepts the Context §8 fixture', () => {
    const good = loadGoodFixture();
    expect(validateTelemetryFrame(good)).toBe(true);
  });

  it('rejects risk.smoothed > 1 with an error path mentioning /risk/smoothed', () => {
    const bad = loadGoodFixture();
    bad.risk.smoothed = 1.4;
    expect(validateTelemetryFrame(bad)).toBe(false);
    const paths = (validateTelemetryFrame.errors || []).map((e) => e.instancePath);
    expect(paths.some((p) => p.includes('/risk/smoothed'))).toBe(true);
  });

  it('assertTelemetryFrame logs on bad data but still returns the frame (never throws)', () => {
    const bad = loadGoodFixture();
    bad.risk.smoothed = 1.4;
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});

    let returned;
    expect(() => {
      returned = assertTelemetryFrame(bad);
    }).not.toThrow();

    expect(returned).toBe(bad);
    expect(spy).toHaveBeenCalled();
    const message = String(spy.mock.calls[0]?.[0] ?? '');
    expect(message).toContain('/risk/smoothed');
    expect(message).toMatch(/1\.4/);
  });

  it('safe accessors return documented defaults on an empty object', () => {
    const empty = {};
    expect(getFamilyScore(empty, 'voice')).toBeNull();
    expect(getSmoothedRisk(empty)).toBe(0);
    expect(getLevel(empty)).toBe(INTERVENTION_LEVELS.LEVEL_1_SILENT);
    expect(getTopReasons(empty)).toEqual([]);
    expect(isAvailable(empty, 'prosody')).toBe(false);
  });
});
