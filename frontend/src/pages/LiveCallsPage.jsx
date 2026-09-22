import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, Navigate } from 'react-router-dom';
import { useAuth } from '@/context/AuthContext.jsx';
import { apiJson } from '@/services/api.js';
import { Badge, Button, EmptyState } from '@/ui';
import { useToast } from '@/ui/Toast.jsx';

const POLL_MS = 2500;
const POLL_TIMEOUT_MS = 60_000;

/**
 * Play a short alert tone when a call enters L3 (operator workspace).
 */
function playL3Alert() {
  try {
    const Ctx = window.AudioContext || window.webkitAudioContext;
    if (!Ctx) return;
    const ctx = new Ctx();
    const now = ctx.currentTime;
    [0, 0.18].forEach((offset, i) => {
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.type = 'sine';
      osc.frequency.value = i === 0 ? 880 : 660;
      gain.gain.setValueAtTime(0.0001, now + offset);
      gain.gain.exponentialRampToValueAtTime(0.12, now + offset + 0.02);
      gain.gain.exponentialRampToValueAtTime(0.0001, now + offset + 0.15);
      osc.connect(gain);
      gain.connect(ctx.destination);
      osc.start(now + offset);
      osc.stop(now + offset + 0.18);
    });
    window.setTimeout(() => ctx.close().catch(() => {}), 600);
  } catch {
    // Audio may be blocked until a user gesture; ignore.
  }
}

function isL3Level(level) {
  return typeof level === 'string' && level.includes('LEVEL_3');
}

function effectiveLevel(row) {
  return row.liveLevel || row.peakLevel || null;
}

/**
 * F10 — Multi-call operator workspace: Live Calls with L3 amber rows,
 * callback / approval panel, and Bridge RBAC.
 */
export function LiveCallsPage() {
  const { hasPermission, me } = useAuth();
  const { push } = useToast();
  const [items, setItems] = useState([]);
  const [activeCount, setActiveCount] = useState(0);
  const [loading, setLoading] = useState(true);
  const [selectedId, setSelectedId] = useState(null);
  const [lastOkAt, setLastOkAt] = useState(null);
  const [forceBusy, setForceBusy] = useState(false);
  const [bridgeBusy, setBridgeBusy] = useState(false);
  const [callbackBusy, setCallbackBusy] = useState(false);
  const [approveBusy, setApproveBusy] = useState(false);
  const [approveResult, setApproveResult] = useState(/** @type {string | null} */ (null));
  /** @type {React.MutableRefObject<Record<string, string>>} */
  const thinkingByCallRef = useRef({});
  /** @type {React.MutableRefObject<Record<string, string>>} */
  const transcriptByCallRef = useRef({});
  const prevL3Ref = useRef(/** @type {Set<string>} */ (new Set()));

  const canRead = hasPermission('calls:read');
  const canAct = hasPermission('calls:act');
  const canBridge = hasPermission('calls:bridge');

  const load = useCallback(async () => {
    try {
      const data = await apiJson('/api/v2/calls?limit=50', {
        skipErrorToast: true,
        timeoutMs: POLL_TIMEOUT_MS,
      });
      const next = Array.isArray(data.items) ? data.items : [];
      // Cache LLM thinking so a later poll without the field doesn't wipe the panel.
      for (const row of next) {
        const key = String(row.id || row.svSessionUuid || '');
        const think = typeof row.llmThinking === 'string' ? row.llmThinking.trim() : '';
        if (key && think) {
          thinkingByCallRef.current[key] = think;
        } else if (key && thinkingByCallRef.current[key]) {
          row.llmThinking = thinkingByCallRef.current[key];
        }
        const asr = typeof row.asrTranscript === 'string' ? row.asrTranscript.trim() : '';
        if (key && asr) {
          transcriptByCallRef.current[key] = asr;
        } else if (key && transcriptByCallRef.current[key]) {
          row.asrTranscript = transcriptByCallRef.current[key];
        }
      }
      setItems(next);
      setActiveCount(data.activeCount ?? 0);
      setLastOkAt(Date.now());

      const nowL3 = new Set(
        next
          .filter((r) => r.active && isL3Level(effectiveLevel(r)))
          .map((r) => r.id),
      );
      for (const id of nowL3) {
        if (!prevL3Ref.current.has(id)) {
          playL3Alert();
          break;
        }
      }
      prevL3Ref.current = nowL3;
    } catch {
      // Keep last good rows; never toast timeouts on the Live Calls poll.
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (!canRead) return undefined;
    let id = 0;
    const tick = () => {
      if (document.visibilityState === 'hidden') return;
      void load();
    };
    tick();
    id = window.setInterval(tick, POLL_MS);
    const onVis = () => {
      if (document.visibilityState === 'visible') tick();
    };
    document.addEventListener('visibilitychange', onVis);
    return () => {
      window.clearInterval(id);
      document.removeEventListener('visibilitychange', onVis);
    };
  }, [canRead, load]);

  if (!canRead) {
    return <Navigate to="/app" replace />;
  }

  const selected = items.find((r) => r.id === selectedId) || items.find((r) => r.active) || null;
  const selectedLevel = selected ? effectiveLevel(selected) : null;
  const selectedIsL3 = selected && isL3Level(selectedLevel);
  const callbackDone = selected && (selected.callbackVerified || false);
  const showCallback =
    selected &&
    selected.active &&
    (selected.callbackRequired || selectedIsL3 || callbackDone);
  const approvalLocked =
    selected &&
    selected.active &&
    (selected.approvalLocked === true || (selectedIsL3 && !callbackDone));

  async function onForceL3(row) {
    if (!canAct || !row?.id) return;
    setForceBusy(true);
    setApproveResult(null);
    try {
      await apiJson(`/api/v2/calls/${encodeURIComponent(row.id)}/force-level`, {
        method: 'POST',
        body: JSON.stringify({
          targetLevel: 'LEVEL_3_STEP_UP_MFA',
          reason: 'Operator workspace lab drive to L3 for callback / lock verification',
        }),
      });
      push('Forced to L3 — Approve is locked until you confirm callback');
      await load();
    } catch (err) {
      push(err instanceof Error ? err.message : 'Force L3 failed');
    } finally {
      setForceBusy(false);
    }
  }

  async function onBridge(row) {
    if (!canBridge || !row?.id) return;
    setBridgeBusy(true);
    try {
      await apiJson(`/api/v2/calls/${encodeURIComponent(row.id)}/bridge`, {
        method: 'POST',
        body: JSON.stringify({}),
      });
      push('Bridge supervisor requested');
      await load();
    } catch (err) {
      push(err instanceof Error ? err.message : 'Bridge failed');
    } finally {
      setBridgeBusy(false);
    }
  }

  async function onConfirmCallback(row) {
    if (!canAct || !row?.id) return;
    setCallbackBusy(true);
    try {
      await apiJson(`/api/v2/calls/${encodeURIComponent(row.id)}/confirm-callback`, {
        method: 'POST',
        body: JSON.stringify({}),
      });
      push('Callback verified — Approve is now unlocked');
      await load();
    } catch (err) {
      push(err instanceof Error ? err.message : 'Confirm callback failed');
    } finally {
      setCallbackBusy(false);
    }
  }

  async function onApprove(row) {
    if (!canAct || !row?.svSessionUuid || approvalLocked) return;
    setApproveBusy(true);
    setApproveResult(null);
    try {
      const data = await apiJson(
        `/api/v1/transaction/${encodeURIComponent(row.svSessionUuid)}/approve`,
        {
          method: 'POST',
          body: JSON.stringify({
            actorId: me?.user?.id || me?.email || 'demo-admin',
          }),
        },
      );
      setApproveResult(data?.message || 'Approved');
      push('Transfer approved');
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Approve failed';
      setApproveResult(msg);
      push(msg);
    } finally {
      setApproveBusy(false);
    }
  }

  return (
    <div className="space-y-4 p-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <p className="text-xs font-semibold uppercase tracking-wide text-sv-muted">Telephony</p>
          <h1 className="mt-1 text-xl font-semibold text-sv-fg">Live Calls</h1>
          <p className="mt-1 text-sm text-sv-muted">
            Multi-call operator workspace — departments, L3 step-up panel, and bridge.
          </p>
        </div>
        <div className="flex items-center gap-3 text-sm text-sv-muted">
          <span>
            Active <span className="font-semibold text-sv-fg">{activeCount}</span>
          </span>
          {lastOkAt ? (
            <span className="font-mono text-[11px]">
              updated {Math.max(0, Math.round((Date.now() - lastOkAt) / 1000))}s ago
            </span>
          ) : null}
          <Link to="/app/directory" className="text-sv-accent underline-offset-2 hover:underline">
            Directory →
          </Link>
        </div>
      </div>

      {loading && !items.length ? (
        <p className="text-sm text-sv-muted">Loading calls…</p>
      ) : !items.length ? (
        <EmptyState
          title="No calls yet"
          description="Create SIP accounts under Directory → employee → Telephony, register Zoiper, then dial. New calls appear here automatically."
        />
      ) : (
        <div className="grid gap-4 lg:grid-cols-[minmax(0,1.4fr)_minmax(280px,0.9fr)]">
          <div className="overflow-hidden rounded-md border border-sv-border">
            <table className="w-full text-left text-sm">
              <thead className="border-b border-sv-border bg-sv-elevated/60 text-[11px] uppercase tracking-wide text-sv-muted">
                <tr>
                  <th className="px-3 py-2 font-medium">Status</th>
                  <th className="px-3 py-2 font-medium">Caller</th>
                  <th className="px-3 py-2 font-medium">Callee</th>
                  <th className="px-3 py-2 font-medium">Depts</th>
                  <th className="px-3 py-2 font-medium">Level</th>
                  <th className="px-3 py-2 font-medium">Started</th>
                </tr>
              </thead>
              <tbody>
                {items.map((row) => {
                  const level = effectiveLevel(row);
                  const l3 = row.active && isL3Level(level);
                  const selectedRow = selected?.id === row.id;
                  return (
                    <tr
                      key={row.id}
                      onClick={() => setSelectedId(row.id)}
                      className={[
                        'cursor-pointer border-b border-sv-border/60 transition',
                        selectedRow ? 'bg-sv-accent/10' : 'hover:bg-sv-elevated/40',
                        l3 ? 'bg-amber-950/35 ring-1 ring-inset ring-amber-500/40' : '',
                      ].join(' ')}
                    >
                      <td className="px-3 py-2.5">
                        {row.active ? (
                          <Badge tone={l3 ? 'warning' : 'success'}>{l3 ? 'L3 alert' : 'Live'}</Badge>
                        ) : (
                          <Badge tone="neutral">Ended</Badge>
                        )}
                      </td>
                      <td className="px-3 py-2.5">
                        <div className="text-sv-fg">{row.callerName || '—'}</div>
                        <div className="font-mono text-[11px] text-sv-muted">{row.callerNumber || ''}</div>
                      </td>
                      <td className="px-3 py-2.5">
                        <div className="text-sv-fg">{row.calleeName || '—'}</div>
                        <div className="font-mono text-[11px] text-sv-muted">{row.calleeNumber || ''}</div>
                      </td>
                      <td className="px-3 py-2.5 text-xs text-sv-muted">
                        <div>{row.callerDepartment || '—'}</div>
                        <div className="text-sv-muted/70">→ {row.calleeDepartment || '—'}</div>
                      </td>
                      <td className="px-3 py-2.5">
                        {level ? (
                          <Badge tone={l3 ? 'warning' : row.active ? 'neutral' : 'neutral'}>
                            {level.replace(/^LEVEL_/, 'L').replace(/_/g, ' ')}
                            {typeof (row.liveScore ?? row.peakScore) === 'number'
                              ? ` · ${(row.liveScore ?? row.peakScore).toFixed(2)}`
                              : ''}
                          </Badge>
                        ) : (
                          <span className="text-xs text-sv-muted">—</span>
                        )}
                      </td>
                      <td className="px-3 py-2.5">
                        <Link
                          to={`/app/sessions/${encodeURIComponent(row.id || row.svSessionUuid)}`}
                          className="font-mono text-[11px] text-sv-accent underline-offset-2 hover:underline"
                          onClick={(e) => e.stopPropagation()}
                        >
                          {row.startedAt ? new Date(row.startedAt).toLocaleString() : 'Open'}
                        </Link>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>

          <aside className="space-y-3 rounded-md border border-sv-border bg-sv-elevated/30 p-4">
            {!selected ? (
              <p className="text-sm text-sv-muted">Select a call row to open the action panel.</p>
            ) : (
              <>
                <div>
                  <p className="text-xs font-semibold uppercase tracking-wide text-sv-muted">
                    Selected call
                  </p>
                  <h2 className="mt-1 text-base font-semibold text-sv-fg">
                    {selected.callerName} → {selected.calleeName}
                  </h2>
                  <p className="mt-1 text-xs text-sv-muted">
                    {[selected.callerDepartment, selected.calleeDepartment]
                      .filter(Boolean)
                      .join(' / ') || 'No department'}
                    {selectedLevel ? ` · ${selectedLevel}` : ''}
                  </p>
                </div>

                <div className="rounded border border-sv-border bg-sv-bg/60 px-3 py-2 text-sm">
                  <p className="text-[11px] font-semibold uppercase tracking-wide text-sv-muted">
                    More info
                  </p>
                  <p className="mt-2 text-[11px] font-semibold uppercase tracking-wide text-sv-muted">
                    Spoken (ASR)
                  </p>
                  <p className="mt-1 min-h-[2.5rem] whitespace-pre-wrap font-mono text-[11px] leading-relaxed text-sv-fg/80">
                    {selected.asrTranscript
                      || transcriptByCallRef.current[String(selected.id || selected.svSessionUuid || '')]
                      || (selected.active
                        ? 'Waiting for speech…'
                        : 'No transcript retained for this call.')}
                  </p>
                  <p className="mt-2 text-[11px] font-semibold uppercase tracking-wide text-sv-muted">
                    LLM thinking
                    {selected.linguisticStatus === 'pending' || selected.llmPending ? (
                      <span className="ml-1 font-normal normal-case text-sv-muted">(running…)</span>
                    ) : null}
                  </p>
                    <p className="mt-1 min-h-[3rem] whitespace-pre-wrap text-xs leading-relaxed text-sv-fg/90">
                      {selected.llmThinking
                        || thinkingByCallRef.current[String(selected.id || selected.svSessionUuid || '')]
                        || (selected.linguisticStatus === 'pending'
                          ? 'Waiting for LLM judgment on ACTIVE rules…'
                          : selected.active
                            ? 'No LLM judgment yet — speak about a policy topic (password, OTP, callback, transfer…). Keywords not required; LLM matches ACTIVE rules. ~30–60s.'
                            : 'No LLM judgment was stored for this call (hang up only after More info shows thinking).')}
                    </p>
                  <p className="mt-2 text-[11px] font-semibold uppercase tracking-wide text-sv-muted">
                    Keywords triggered
                  </p>
                  <div className="mt-1.5 flex flex-wrap gap-1.5">
                    {(Array.isArray(selected.matchedKeywords) ? selected.matchedKeywords : [])
                      .length === 0 ? (
                      <span className="text-xs text-sv-muted">None yet</span>
                    ) : (
                      selected.matchedKeywords.map((k) => (
                        <Badge key={k} tone="warning">
                          {k}
                        </Badge>
                      ))
                    )}
                  </div>
                  <p className="mt-2 text-[11px] font-semibold uppercase tracking-wide text-sv-muted">
                    Rules broken
                  </p>
                  <ul className="mt-1 space-y-1 text-xs text-sv-fg">
                    {(
                      (Array.isArray(selected.brokenRuleTitles) &&
                      selected.brokenRuleTitles.length
                        ? selected.brokenRuleTitles
                        : Array.isArray(selected.brokenRuleIds)
                          ? selected.brokenRuleIds
                          : []) || []
                    ).length === 0 ? (
                      <li className="text-sv-muted">None yet</li>
                    ) : (
                      (selected.brokenRuleTitles?.length
                        ? selected.brokenRuleTitles
                        : selected.brokenRuleIds
                      ).map((t) => (
                        <li key={t} className="font-mono text-[11px]">
                          {t}
                        </li>
                      ))
                    )}
                  </ul>
                  {selected.linguisticSource ? (
                    <p className="mt-1 text-[10px] text-sv-muted">
                      source {selected.linguisticSource}
                      {selected.linguisticConfidence != null
                        ? ` · conf ${(Number(selected.linguisticConfidence) * 100).toFixed(0)}%`
                        : ''}
                    </p>
                  ) : null}
                </div>

                {showCallback ? (
                  <div className="rounded-md border border-amber-500/40 bg-amber-950/30 px-3 py-3">
                    <p className="text-sm font-semibold text-amber-200">
                      Confirm callback verification
                    </p>
                    <ol className="mt-2 list-decimal space-y-1 pl-4 text-xs text-amber-100/90">
                      <li>Hang up the current call when safe</li>
                      <li>
                        Call back on directory number{' '}
                        <span className="font-mono">
                          {selected.calleeNumber || selected.callerNumber}
                        </span>
                      </li>
                      <li>Confirm identity before any approval or transfer</li>
                    </ol>
                    {canAct ? (
                      <Button
                        className="mt-3"
                        variant="secondary"
                        disabled={!!callbackDone || callbackBusy}
                        onClick={() => onConfirmCallback(selected)}
                      >
                        {callbackDone
                          ? 'Callback confirmed'
                          : callbackBusy
                            ? 'Confirming…'
                            : 'Confirm callback verification'}
                      </Button>
                    ) : null}
                  </div>
                ) : null}

                <div className="rounded-md border border-sv-border bg-sv-bg/40 px-3 py-3">
                  <p className="text-xs font-semibold uppercase tracking-wide text-sv-muted">
                    Transaction approval
                  </p>
                  <Button
                    className="mt-2 w-full"
                    variant="primary"
                    disabled={
                      !!approvalLocked || !canAct || !selected.active || approveBusy || !selected.svSessionUuid
                    }
                    title={
                      approvalLocked
                        ? selected.approvalLockReason ||
                          'Click “Confirm callback verification” above first'
                        : !selected.active
                          ? 'Call has ended'
                          : undefined
                    }
                    onClick={() => onApprove(selected)}
                  >
                    {approveBusy ? 'Approving…' : 'Approve'}
                  </Button>
                  {approvalLocked ? (
                    <p className="mt-2 text-[11px] leading-snug text-amber-200/90">
                      {selected.approvalLockReason ||
                        'Approval locked at L3 — click Confirm callback verification above, then Approve.'}
                    </p>
                  ) : !selected.active ? (
                    <p className="mt-2 text-[11px] text-sv-muted">Ended calls cannot be approved.</p>
                  ) : (
                    <p className="mt-2 text-[11px] text-sv-muted">
                      Approve is unlocked — click to submit the mock transfer.
                    </p>
                  )}
                  {approveResult ? (
                    <p className="mt-2 font-mono text-[11px] text-sv-fg">{approveResult}</p>
                  ) : null}
                </div>

                <div className="flex flex-wrap gap-2">
                  <span title={!canBridge ? 'Requires calls:bridge (tenant admin)' : undefined}>
                    <Button
                      variant="secondary"
                      disabled={!canBridge || !selected.active || bridgeBusy}
                      onClick={() => onBridge(selected)}
                    >
                      Bridge
                    </Button>
                  </span>
                  {canAct && selected.active ? (
                    <Button
                      variant="ghost"
                      disabled={forceBusy || selectedIsL3}
                      onClick={() => onForceL3(selected)}
                    >
                      Force L3 (lab)
                    </Button>
                  ) : null}
                </div>
                {!canBridge ? (
                  <p className="text-[11px] text-sv-muted">
                    Bridge disabled — your role ({me?.role || 'analyst'}) lacks{' '}
                    <span className="font-mono">calls:bridge</span>.
                  </p>
                ) : null}

                <Link
                  to={`/app/sessions/${encodeURIComponent(selected.id || selected.svSessionUuid)}`}
                  className="inline-block text-xs text-sv-accent underline-offset-2 hover:underline"
                >
                  Open session detail →
                </Link>
              </>
            )}
          </aside>
        </div>
      )}
    </div>
  );
}
