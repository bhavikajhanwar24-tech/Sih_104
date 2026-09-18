import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { TransactionPanel } from '@/components/TransactionPanel.jsx';
import { INTERVENTION_LEVELS } from '@/contracts';

function frameWithLevel(level) {
  return {
    schema: 'sentinelvoice.TelemetryFrame/1',
    sessionId: 's1',
    seq: 1,
    tsEpochMs: 1,
    callElapsedMs: 1,
    risk: { instantaneous: 0.6, smoothed: 0.6, trend: 'RISING', state: 'SCORED' },
    intervention: {
      level,
      previousLevel: INTERVENTION_LEVELS.LEVEL_1_SILENT,
      changedAtMs: 1,
      dwellRemainingMs: 0,
      manualOverride: null,
      actionsFired: [],
    },
  };
}

describe('TransactionPanel', () => {
  it('enables approve at L1/L2 and locks at L3+ from TelemetryFrame', () => {
    const { rerender } = render(
      <TransactionPanel sessionId="s1" frame={frameWithLevel(INTERVENTION_LEVELS.LEVEL_2_SOFT_NUDGE)} />,
    );
    const btn = screen.getByTestId('approve-transfer');
    expect(btn.disabled).toBe(false);
    expect(btn.getAttribute('data-locked')).toBe('false');

    rerender(
      <TransactionPanel sessionId="s1" frame={frameWithLevel(INTERVENTION_LEVELS.LEVEL_3_STEP_UP_MFA)} />,
    );
    const locked = screen.getByTestId('approve-transfer');
    expect(locked.disabled).toBe(true);
    expect(locked.getAttribute('data-locked')).toBe('true');
    expect(screen.getByText(/Locked by SentinelVoice/i)).toBeTruthy();
    expect(screen.getByTestId('oob-mfa-card')).toBeTruthy();
  });
});
