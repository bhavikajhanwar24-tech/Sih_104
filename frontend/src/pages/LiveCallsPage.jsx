/**
 * Live Calls — simple operator workspace: call list + detail/actions.
 * Live data only (STOMP + poll). No scenarios, no bank-specific panels.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, Navigate, useNavigate } from 'react-router-dom';
import { ChallengePanel } from '@/components/ChallengePanel.jsx';
import { OverrideDialog } from '@/components/OverrideDialog.jsx';
import { RiskGauge } from '@/components/RiskGauge.jsx';
import { WhyPanel } from '@/components/WhyPanel.jsx';
import { useAuth } from '@/context/AuthContext.jsx';
import { CONNECTION_STATES, useTelemetrySocket } from '@/hooks/useTelemetrySocket.js';
import { apiFetch, apiJson } from '@/services/api.js';
import { Badge, Button, EmptyState } from '@/ui';
import { useToast } from '@/ui/Toast.jsx';

const POLL_MS = 4000;
const POLL_TIMEOUT_MS = 60_000;
const L3_ALERT_KEY = 'sv-l3-mute';

function playL3Alert() {
  try {
    const Ctx = window.AudioContext || window.webkitAudioContext;
    if (!Ctx) return;
    const ctx = new Ctx();
    const now = ctx.currentTime;
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.type = 'sine';
    osc.frequency.value = 880;
    gain.gain.setValueAtTime(0.0001, now);
    gain.gain.exponentialRampToValueAtTime(0.1, now + 0.02);
    gain.gain.exponentialRampToValueAtTime(0.0001, now + 0.2);
    osc.connect(gain);
    gain.connect(ctx.destination);
    osc.start(now);
    osc.stop(now + 0.22);
    window.setTimeout(() => ctx.close().catch(() => {}), 400);
  } catch {
    /* ignore */
  }
}

function levelRank(level) {
  if (!level) return 0;
  if (level.includes('LEVEL_4') || level.includes('TERMINATE')) return 4;
  if (level.includes('LEVEL_3')) return 3;
  if (level.includes('LEVEL_2')) return 2;
  if (level.includes('LEVEL_1')) return 1;
  return 0;
}

function levelChip(level) {
  const r = levelRank(level);
  if (r >= 3) return 'bg-red-600/90 text-white';
  if (r === 2) return 'bg-amber-500/90 text-black';
  if (r === 1) return 'bg-emerald-700/80 text-white';
  return 'bg-sv-elevated text-sv-muted';
}

function formatDuration(sec) {
  const s = Math.max(0, Math.floor(Number(sec) || 0));
  const m = Math.floor(s / 60);
  return `${m}:${String(s % 60).padStart(2, '0')}`;
}

function shortLevel(level) {
  if (!level) return '—';
  const m = String(level).match(/LEVEL_(\d)/);
  return m ? `L${m[1]}` : String(level).slice(0, 12);
}

export function LiveCallsPage() {
  const { hasPermission, me } = useAuth();
  const { push } = useToast();
  const navigate = useNavigate();
  const tenantId = me?.tenant?.id || null;

  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(/** @type {string | null} */ (null));
  const [selectedId, setSelectedId] = useState(null);
  const [muteAlerts, setMuteAlerts] = useState(() => localStorage.getItem(L3_ALERT_KEY) === '1');
  const [unackedAlerts, setUnackedAlerts] = useState(0);
  const [overrideOpen, setOverrideOpen] = useState(false);
  const [bridgeBusy, setBridgeBusy] = useState(false);
  const [callbackBusy, setCallbackBusy] = useState(false);
  const [approveBusy, setApproveBusy] = useState(false);

  const thinkingByCallRef = useRef({});
  const transcriptByCallRef = useRef({});
  const prevL3Ref = useRef(/** @type {Set<string>} */ (new Set()));

  const canRead = hasPermission('calls:read');
  const canAct = hasPermission('calls:act');
  const canBridge = hasPermission('calls:bridge');
  const canKill = hasPermission('calls:kill');

  const selectedRow = useMemo(
    () => items.find((r) => r.id === selectedId) || items.find((r) => r.active) || null,
    [items, selectedId],
  );
  const selectedSession = selectedRow?.svSessionUuid || null;

  const {
    latest,
    callDeltas,
    connectionState,
    stale,
    error: sockErr,
  } = useTelemetrySocket({
    sessionId: selectedSession,
    tenantId,
    subscribeCalls: true,
  });

  const mergeRow = useCallback((row) => {
    const key = String(row.id || row.svSessionUuid || '');
    const think = typeof row.llmThinking === 'string' ? row.llmThinking.trim() : '';
    if (key && think) thinkingByCallRef.current[key] = think;
    else if (key && thinkingByCallRef.current[key]) row.llmThinking = thinkingByCallRef.current[key];
    const asr = typeof row.asrTranscript === 'string' ? row.asrTranscript.trim() : '';
    if (key && asr) transcriptByCallRef.current[key] = asr;
    else if (key && transcriptByCallRef.current[key]) row.asrTranscript = transcriptByCallRef.current[key];
    return row;
  }, []);

  const load = useCallback(async () => {
    try {
      const data = await apiJson('/api/v2/calls?limit=80', {
        skipErrorToast: true,
        timeoutMs: POLL_TIMEOUT_MS,
      });
      const next = (Array.isArray(data.items) ? data.items : []).map((r) => mergeRow({ ...r }));
      setItems(next);
      setLoadError(null);
    } catch (err) {
      setLoadError(err instanceof Error ? err.message : 'Failed to load calls');
    } finally {
      setLoading(false);
    }
  }, [mergeRow]);

  useEffect(() => {
    if (!canRead) return undefined;
    void load();
    const id = window.setInterval(() => {
      if (document.visibilityState !== 'hidden') void load();
    }, POLL_MS);
    return () => window.clearInterval(id);
  }, [canRead, load]);

  useEffect(() => {
    const keys = Object.keys(callDeltas || {});
    if (!keys.length) return;
    setItems((prev) => {
      const byKey = new Map(prev.map((r) => [String(r.svSessionUuid || r.id), r]));
      for (const k of keys) {
        const d = callDeltas[k];
        const existing = byKey.get(k) || {};
        byKey.set(
          k,
          mergeRow({
            ...existing,
            ...d,
            id: d.id || existing.id || k,
            active: d.active !== false,
          }),
        );
      }
      return Array.from(byKey.values());
    });
  }, [callDeltas, mergeRow]);

  useEffect(() => {
    const nowL3 = new Set(
      items.filter((r) => r.active && levelRank(r.liveLevel || r.peakLevel) >= 3).map((r) => r.id),
    );
    for (const id of nowL3) {
      if (!prevL3Ref.current.has(id)) {
        setUnackedAlerts((n) => n + 1);
        if (!muteAlerts) playL3Alert();
        break;
      }
    }
    prevL3Ref.current = nowL3;
  }, [items, muteAlerts]);

  useEffect(() => {
    localStorage.setItem(L3_ALERT_KEY, muteAlerts ? '1' : '0');
  }, [muteAlerts]);

  useEffect(() => {
    window.dispatchEvent(new CustomEvent('sv-live-alerts', { detail: { count: unackedAlerts } }));
  }, [unackedAlerts]);

  const sorted = useMemo(() => {
    const rows = items.slice();
    rows.sort((a, b) => {
      const la = levelRank(a.liveLevel || (a.active ? null : a.peakLevel));
      const lb = levelRank(b.liveLevel || (b.active ? null : b.peakLevel));
      if (lb !== la) return lb - la;
      return Number(b.liveScore ?? b.peakScore ?? 0) - Number(a.liveScore ?? a.peakScore ?? 0);
    });
    return rows;
  }, [items]);

  if (!canRead) {
    return <Navigate to="/app" replace />;
  }

  const showLiveLevels = !stale;
  const liveLevel =
    selectedRow?.active && showLiveLevels
      ? selectedRow.liveLevel || latest?.risk?.level || null
      : null;
  const connLabel =
    connectionState === CONNECTION_STATES.CONNECTED
      ? 'live'
      : connectionState === CONNECTION_STATES.RECONNECTING
        ? 'reconnecting'
        : 'offline';
  const activeCount = items.filter((r) => r.active).length;

  async function onBridge(row) {
    if (!canBridge || !row?.id) return;
    setBridgeBusy(true);
    try {
      await apiJson(`/api/v2/calls/${encodeURIComponent(row.id)}/bridge`, { method: 'POST' });
      push('Supervisor bridge requested', 'ok');
      void load();
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
      });
      push('Callback confirmed', 'ok');
      void load();
    } catch (err) {
      push(err instanceof Error ? err.message : 'Confirm failed');
    } finally {
      setCallbackBusy(false);
    }
  }

  async function onApprove() {
    if (!canAct || !selectedSession || selectedRow?.approvalLocked) return;
    setApproveBusy(true);
    try {
      const res = await apiFetch(`/api/v1/transaction/${encodeURIComponent(selectedSession)}/approve`, {
        method: 'POST',
        body: JSON.stringify({ actorId: me?.user?.id || 'operator' }),
        skipErrorToast: true,
      });
      if (res.status === 423) {
        push('Action locked — confirm callback / step-up first');
      } else if (!res.ok) {
        push(`Approve failed (${res.status})`);
      } else {
        push('Action approved', 'ok');
        void load();
      }
    } catch (err) {
      push(err instanceof Error ? err.message : 'Approve failed');
    } finally {
      setApproveBusy(false);
    }
  }

  return (
    <div className="flex h-full min-h-0 flex-col">
      <header className="flex flex-wrap items-center gap-3 border-b border-sv-border bg-sv-panel px-4 py-2 text-xs">
        <span className="font-display text-sm font-semibold text-sv-fg">Live calls</span>
        <Badge tone={connLabel === 'live' ? 'success' : 'warn'}>{connLabel}</Badge>
        <span className="text-sv-muted">
          Active <strong className="text-sv-fg">{activeCount}</strong>
        </span>
        {unackedAlerts > 0 ? (
          <button
            type="button"
            className="rounded bg-red-700/80 px-2 py-0.5 font-semibold text-white"
            onClick={() => setUnackedAlerts(0)}
          >
            {unackedAlerts} alert{unackedAlerts === 1 ? '' : 's'} — ack
          </button>
        ) : null}
        <label className="ml-auto flex items-center gap-1.5 text-sv-muted">
          <input type="checkbox" checked={muteAlerts} onChange={(e) => setMuteAlerts(e.target.checked)} />
          Mute alerts
        </label>
        {canKill ? (
          <Button variant="secondary" className="!py-1 text-[11px]" onClick={() => navigate('/app')}>
            Kill switch
          </Button>
        ) : null}
      </header>

      {stale || sockErr || loadError ? (
        <div role="alert" className="border-b border-amber-500/40 bg-amber-950/40 px-4 py-2 text-sm text-amber-100">
          {loadError ? `API: ${loadError}. ` : null}
          {stale || sockErr ? (
            <>
              Connection {connLabel}
              {sockErr ? ` — ${sockErr}` : ''}.
            </>
          ) : null}
          <Button variant="ghost" className="ml-2 !py-0.5 text-xs" onClick={() => void load()}>
            Refresh
          </Button>
        </div>
      ) : null}

      <div className="grid min-h-0 flex-1 grid-cols-1 lg:grid-cols-[minmax(280px,360px)_minmax(0,1fr)]">
        <aside className="flex min-h-0 flex-col border-r border-sv-border">
          <div className="min-h-0 flex-1 overflow-y-auto">
            {loading && !sorted.length ? (
              <p className="p-4 text-sm text-sv-muted">Loading…</p>
            ) : !sorted.length ? (
              <div className="p-4">
                <EmptyState
                  title="No live calls"
                  description="No monitored sessions for this tenant. Softphone audio alone is not enough — Decision Plane must open the session (AGI sessions/start or bridge)."
                />
                <Link
                  to="/app/settings/telephony"
                  className="mt-3 inline-block text-sm text-sv-accent hover:underline"
                >
                  Telephony setup →
                </Link>
              </div>
            ) : (
              <ul className="divide-y divide-sv-border">
                {sorted.map((row) => {
                  const level =
                    row.active && showLiveLevels ? row.liveLevel : row.active ? null : row.peakLevel;
                  const selected = selectedRow && selectedRow.id === row.id;
                  return (
                    <li key={row.id}>
                      <button
                        type="button"
                        onClick={() => {
                          setSelectedId(row.id);
                          setUnackedAlerts((n) => Math.max(0, n - 1));
                        }}
                        className={`flex w-full flex-col gap-1 px-3 py-3 text-left hover:bg-sv-elevated/60 ${
                          selected ? 'bg-sv-accent/10' : ''
                        }`}
                      >
                        <div className="flex items-start justify-between gap-2">
                          <div className="min-w-0">
                            <p className="truncate text-sm font-medium text-sv-fg">{row.callerName}</p>
                            <p className="truncate text-xs text-sv-muted">→ {row.calleeName}</p>
                          </div>
                          <span
                            className={`shrink-0 rounded px-1.5 py-0.5 font-mono text-[10px] ${
                              row.active && showLiveLevels ? levelChip(level) : 'bg-sv-elevated text-sv-muted'
                            }`}
                          >
                            {row.active ? (showLiveLevels ? shortLevel(level) : '…') : 'ended'}
                          </span>
                        </div>
                        <p className="font-mono text-[10px] text-sv-muted">{formatDuration(row.durationSec)}</p>
                      </button>
                    </li>
                  );
                })}
              </ul>
            )}
          </div>
        </aside>

        <section className="min-h-0 overflow-y-auto p-4">
          {!selectedRow ? (
            <p className="text-sm text-sv-muted">Select a call.</p>
          ) : (
            <div className="mx-auto flex max-w-3xl flex-col gap-4">
              <div className="flex flex-wrap items-start justify-between gap-3">
                <div>
                  <h2 className="text-lg font-semibold text-sv-fg">
                    {selectedRow.callerName} → {selectedRow.calleeName}
                  </h2>
                  <p className="text-xs text-sv-muted">{formatDuration(selectedRow.durationSec)}</p>
                </div>
                {selectedRow.active && liveLevel ? (
                  <span className={`rounded px-2 py-1 font-mono text-xs ${levelChip(liveLevel)}`}>
                    {shortLevel(liveLevel)}
                  </span>
                ) : (
                  <span className="text-xs text-sv-muted">{selectedRow.active ? 'Waiting…' : 'Ended'}</span>
                )}
              </div>

              <div className="rounded border border-sv-border bg-sv-elevated/20 p-3">
                {selectedRow.active && latest ? (
                  <RiskGauge frame={latest} />
                ) : (
                  <p className="py-6 text-center text-sm text-sv-muted">
                    {selectedRow.active ? 'Waiting for live risk…' : 'Call ended — open history for the dossier.'}
                  </p>
                )}
              </div>

              <div className="rounded border border-sv-border bg-sv-elevated/20 p-3">
                <p className="mb-2 text-[11px] font-semibold uppercase tracking-wide text-sv-muted">Why</p>
                {latest ? (
                  <WhyPanel frame={latest} channelProfile={latest?.channelProfile} />
                ) : (
                  <p className="text-xs text-sv-muted">Reasons appear with telemetry.</p>
                )}
              </div>

              <div className="grid gap-3 sm:grid-cols-2">
                <div className="rounded border border-sv-border bg-sv-elevated/20 p-3">
                  <p className="text-[11px] font-semibold uppercase text-sv-muted">Heard</p>
                  <p className="mt-2 min-h-[3rem] whitespace-pre-wrap font-mono text-xs text-sv-fg/85">
                    {selectedRow.asrTranscript ||
                      transcriptByCallRef.current[String(selectedRow.id || '')] ||
                      (selectedRow.active ? 'Waiting for speech…' : '—')}
                  </p>
                </div>
                <div className="rounded border border-sv-border bg-sv-elevated/20 p-3">
                  <p className="text-[11px] font-semibold uppercase text-sv-muted">Matched rules</p>
                  <ul className="mt-2 space-y-1 font-mono text-xs">
                    {(selectedRow.brokenRuleTitles?.length
                      ? selectedRow.brokenRuleTitles
                      : selectedRow.brokenRuleIds || []
                    ).length === 0 ? (
                      <li className="text-sv-muted">None</li>
                    ) : (
                      (selectedRow.brokenRuleTitles?.length
                        ? selectedRow.brokenRuleTitles
                        : selectedRow.brokenRuleIds
                      ).map((t) => <li key={t}>{t}</li>)
                    )}
                  </ul>
                  {(selectedRow.matchedKeywords || []).length ? (
                    <div className="mt-2 flex flex-wrap gap-1">
                      {(selectedRow.matchedKeywords || []).map((k) => (
                        <Badge key={k} tone="warn">
                          {k}
                        </Badge>
                      ))}
                    </div>
                  ) : null}
                </div>
              </div>

              {selectedRow.active && selectedSession ? (
                <div className="space-y-3 rounded border border-sv-border bg-sv-elevated/20 p-3">
                  <p className="text-[11px] font-semibold uppercase text-sv-muted">Actions</p>
                  {selectedRow.approvalLocked ? (
                    <p className="rounded border border-red-500/40 bg-red-950/30 px-2 py-2 text-xs text-red-100">
                      {selectedRow.approvalLockReason || 'Action locked — confirm callback first.'}
                    </p>
                  ) : null}
                  <div className="flex flex-wrap gap-2">
                    {canAct && selectedRow.callbackRequired ? (
                      <Button disabled={callbackBusy} onClick={() => void onConfirmCallback(selectedRow)}>
                        Confirm callback
                      </Button>
                    ) : null}
                    {canBridge ? (
                      <Button
                        variant="secondary"
                        disabled={bridgeBusy}
                        onClick={() => void onBridge(selectedRow)}
                      >
                        Bridge supervisor
                      </Button>
                    ) : null}
                    {canAct ? (
                      <Button
                        disabled={approveBusy || Boolean(selectedRow.approvalLocked)}
                        onClick={() => void onApprove()}
                      >
                        {approveBusy ? '…' : 'Approve action'}
                      </Button>
                    ) : null}
                    {canAct ? (
                      <Button variant="ghost" onClick={() => setOverrideOpen(true)}>
                        Override level
                      </Button>
                    ) : null}
                  </div>
                  <ChallengePanel sessionId={selectedSession} />
                  <OverrideDialog
                    open={overrideOpen}
                    onClose={() => setOverrideOpen(false)}
                    sessionId={selectedSession}
                    currentLevel={liveLevel}
                    actorId={me?.user?.id || me?.user?.email}
                    onSuccess={() => {
                      setOverrideOpen(false);
                      void load();
                    }}
                  />
                </div>
              ) : null}

              {selectedRow.svSessionUuid ? (
                <Link
                  to={`/app/sessions/${encodeURIComponent(selectedRow.svSessionUuid)}`}
                  className="text-sm text-sv-accent hover:underline"
                >
                  Open dossier →
                </Link>
              ) : null}
            </div>
          )}
        </section>
      </div>
    </div>
  );
}
