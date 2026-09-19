import { describe, expect, it } from 'vitest';
import {
  BREAK_GLASS_STATUS,
  canApproveBreakGlass,
  canRequestBreakGlass,
  canViewRedactedWindow,
  nextBreakGlassStatus,
} from '@/lib/breakGlassFlow.js';

describe('breakGlassFlow state machine', () => {
  it('request moves NONE → PENDING for analyst', () => {
    expect(canRequestBreakGlass(BREAK_GLASS_STATUS.NONE, true)).toBe(true);
    expect(canRequestBreakGlass(BREAK_GLASS_STATUS.PENDING, true)).toBe(false);
    expect(nextBreakGlassStatus(BREAK_GLASS_STATUS.NONE, 'request')).toBe(
      BREAK_GLASS_STATUS.PENDING,
    );
  });

  it('approve moves PENDING → APPROVED for supervisor only', () => {
    expect(canApproveBreakGlass(BREAK_GLASS_STATUS.PENDING, true)).toBe(true);
    expect(canApproveBreakGlass(BREAK_GLASS_STATUS.PENDING, false)).toBe(false);
    expect(canApproveBreakGlass(BREAK_GLASS_STATUS.NONE, true)).toBe(false);
    expect(nextBreakGlassStatus(BREAK_GLASS_STATUS.PENDING, 'approve')).toBe(
      BREAK_GLASS_STATUS.APPROVED,
    );
  });

  it('redacted ±5s window only after APPROVED', () => {
    expect(canViewRedactedWindow(BREAK_GLASS_STATUS.NONE)).toBe(false);
    expect(canViewRedactedWindow(BREAK_GLASS_STATUS.PENDING)).toBe(false);
    expect(canViewRedactedWindow(BREAK_GLASS_STATUS.APPROVED)).toBe(true);
    expect(nextBreakGlassStatus(BREAK_GLASS_STATUS.APPROVED, 'reset')).toBe(
      BREAK_GLASS_STATUS.NONE,
    );
  });
});
