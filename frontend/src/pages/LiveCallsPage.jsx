/**
 * F13 — Operator Live Calls workspace.
 * Three-pane layout: call list | selected detail | actions.
 * No mock data. STOMP list deltas + per-call telemetry.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, Navigate, useNavigate } from 'react-router-dom';
import { ChallengePanel } from '@/components/ChallengePanel.jsx';
import { FamilyRadar } from '@/components/FamilyRadar.jsx';
import { IdentityCard } from '@/components/IdentityCard.jsx';
import { InterventionBar } from '@/components/InterventionBar.jsx';
import { OverrideDialog } from '@/components/OverrideDialog.jsx';
import { RiskGauge } from '@/components/RiskGauge.jsx';
import { RiskTimeline } from '@/components/RiskTimeline.jsx';
import { SpectrogramCanvas } from '@/components/SpectrogramCanvas.jsx';
import { TransactionPanel } from '@/components/TransactionPanel.jsx';
import { WhyPanel } from '@/components/WhyPanel.jsx';
import { useAuth } from '@/context/AuthContext.jsx';
import { CONNECTION_STATES, useTelemetrySocket } from '@/hooks/useTelemetrySocket.js';
import { apiJson } from '@/services/api.js';
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
  const r = s % 60;
  return `${m}:${String(r).padStart(2, '0')}`;
}

function meter(v) {
  if (typeof v !== 'number' || !Number.isFinite(v)) return '—';
  return `${Math.round(v * 100)}%`;
}

function Sparkline({ points = [] }) {
  const vals = (Array.isArray(points) ? points : []).map(Number).filter((n) => Number.isFinite(n));
  if (vals.length < 2) {
    return <span className="font-mono text-[10px] text-sv-muted">—</span>;
  }
  const w = 64;
  const h = 18;
  const min = Math.min(...vals);
  const max = Math.max(...vals);
  const span = Math.max(0.05, max - min);
  const d = vals
    .map((v, i) => {
      const x = (i / (vals.length - 1)) * w;
      const y = h - ((v - min) / span) * (h - 2) - 1;
      return `${i === 0 ? 'M' : 'L'}${x.toFixed(1)},${y.toFixed(1)}`;
    })
    .join(' ');
  return (
    <svg width={w} height={h} className="overflow-visible" aria-hidden>
      <path d={d} fill="none" stroke="currentColor" strokeWidth="1.5" className="text-sv-accent" />
    </svg>
  );
}

function LlmDot({ state }) {
  const color =
    state === 'pending'
      ? 'bg-amber-400 animate-pulse'
      : state === 'ready' || state === 'live'
        ? 'bg-emerald-400'
        : 'bg-sv-muted/50';
  return <span className={`inline-block h-2 w-2 rounded-full ${color}`} title={`LLM ${state || 'idle'}`} />;
}

function FamilyStrip({ frame }) {
  const families = ['voice', 'channel', 'prosody', 'linguistic', 'transaction', 'relationship'];
  return (
    <div className="grid grid-cols-3 gap-1.5 sm:grid-cols-6">
      {families.map((f) => {
        const block = frame?.families?.[f] || frame?.families?.[f.toUpperCase?.()] || null;
        const available = Boolean(block?.available);
        const score = typeof block?.score === 'number' ? block.score : typeof block?.contribution === 'number' ? block.contribution : null;
        const tick = available && score != null && score > 0.35;
        return (
          <div
            key={f}
            className={`rounded border px-1.5 py-1 text-center ${available ? 'border-sv-border bg-sv-bg/50' : 'border-dashed border-sv-border/60 opacity-60'}`}
          >
            <p className="font-mono text-[9px] uppercase tracking-wide text-sv-muted">{f.slice(0, 4)}</p>
            <p className="mt-0.5 font-mono text-[11px] text-sv-fg">
              {available && score != null ? score.toFixed(2) : 'n/a'}
            </p>
            <p className="text-[10px]">{tick ? '✓' : '·'}</p>
          </div>
        );
      })}
    </div>
  );
}

/**
 * @param {{ unackedAlerts?: number, onAckAlerts?: () => void }} props
 */
export function LiveCallsPage() {
  const { hasPermission, me } = useAuth();
  const { push } = useToast();
  const navigate = useNavigate();
  const tenantId = me?.tenant?.id || null;

  const [items, setItems] = useState([]);
  const [activeCount, setActiveCount] = useState(0);
  const [loading, setLoading] = useState(true);
  const [selectedId, setSelectedId] = useState(null);
  const [filterLevel, setFilterLevel] = useState(0);
  const [filterDept, setFilterDept] = useState('');
  const [filterUnassigned, setFilterUnassigned] = useState(false);
  const [muteAlerts, setMuteAlerts] = useState(() => localStorage.getItem(L3_ALERT_KEY) === '1');
  const [unackedAlerts, setUnackedAlerts] = useState(0);
  const [overrideOpen, setOverrideOpen] = useState(false);
  const [bridgeBusy, setBridgeBusy] = useState(false);
  const [callbackBusy, setCallbackBusy] = useState(false);
  const [forceBusy, setForceBusy] = useState(false);
  const [llmHealth, setLlmHealth] = useState(null);
  const [telHealth, setTelHealth] = useState(null);

  const thinkingByCallRef = useRef({});
  const transcriptByCallRef = useRef({});
  const prevL3Ref = useRef(/** @type {Set<string>} */ (new Set()));
  const scoreHistRef = useRef(/** @type {Record<string, number[]>} */ ({}));

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
    history,
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
    if (Array.isArray(row.scoreHistory) && row.scoreHistory.length) {
      scoreHistRef.current[key] = row.scoreHistory;
    } else if (scoreHistRef.current[key]) {
      row.scoreHistory = scoreHistRef.current[key];
    }
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
      setActiveCount(data.activeCount ?? next.filter((r) => r.active).length);
    } catch {
      /* keep last good */
    } finally {
      setLoading(false);
    }
  }, [mergeRow]);

  useEffect(() => {
    if (!canRead) return undefined;
    load();
    const id = window.setInterval(() => {
      if (document.visibilityState !== 'hidden') void load();
    }, POLL_MS);
    return () => window.clearInterval(id);
  }, [canRead, load]);

  // Apply STOMP call deltas
  useEffect(() => {
    const keys = Object.keys(callDeltas || {});
    if (!keys.length) return;
    setItems((prev) => {
      const byKey = new Map(prev.map((r) => [String(r.svSessionUuid || r.id), r]));
      for (const k of keys) {
        const d = callDeltas[k];
        const existing = byKey.get(k) || {};
        const merged = mergeRow({
          ...existing,
          ...d,
          id: d.id || existing.id || k,
          active: d.active !== false,
        });
        byKey.set(k, merged);
        if (typeof d.liveScore === 'number') {
          const hist = scoreHistRef.current[k] || [];
          scoreHistRef.current[k] = [...hist, d.liveScore].slice(-24);
          merged.scoreHistory = scoreHistRef.current[k];
        }
      }
      return Array.from(byKey.values());
    });
  }, [callDeltas, mergeRow]);

  // L3/L4 alerts
  useEffect(() => {
    const nowL3 = new Set(
      items
        .filter((r) => r.active && levelRank(r.liveLevel || r.peakLevel) >= 3)
        .map((r) => r.id),
    );
    for (const id of nowL3) {
      if (!prevL3Ref.current.has(id)) {
        setUnackedAlerts((n) => n + 1);
        if (!muteAlerts) {
          playL3Alert();
          if (typeof Notification !== 'undefined' && Notification.permission === 'granted') {
            try {
              // eslint-disable-next-line no-new
              new Notification('SentinelVoice — elevated risk', {
                body: 'A call entered L3/L4',
              });
            } catch {
              /* ignore */
            }
          }
        }
        break;
      }
    }
    prevL3Ref.current = nowL3;
  }, [items, muteAlerts]);

  useEffect(() => {
    if (typeof Notification !== 'undefined' && Notification.permission === 'default') {
      // Optional — do not block UI if denied
      Notification.requestPermission().catch(() => {});
    }
  }, []);

  useEffect(() => {
    localStorage.setItem(L3_ALERT_KEY, muteAlerts ? '1' : '0');
  }, [muteAlerts]);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const [llm, tel] = await Promise.all([
          apiJson('/api/v2/settings/llm-health', { skipErrorToast: true, timeoutMs: 5000 }).catch(() => null),
          apiJson('/api/v2/telephony/asterisk/health', { skipErrorToast: true, timeoutMs: 5000 }).catch(() => null),
        ]);
        if (!cancelled) {
          setLlmHealth(llm);
          setTelHealth(tel);
        }
      } catch {
        /* ignore */
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  // Expose unacked to sidebar via custom event
  useEffect(() => {
    window.dispatchEvent(new CustomEvent('sv-live-alerts', { detail: { count: unackedAlerts } }));
  }, [unackedAlerts]);

  const departments = useMemo(() => {
    const s = new Set();
    for (const r of items) {
      if (r.callerDepartment) s.add(r.callerDepartment);
      if (r.calleeDepartment) s.add(r.calleeDepartment);
    }
    return [...s].sort();
  }, [items]);

  const sorted = useMemo(() => {
    let rows = items.slice();
    if (filterLevel > 0) {
      rows = rows.filter((r) => levelRank(r.liveLevel || (r.active ? null : r.peakLevel)) >= filterLevel);
    }
    if (filterDept) {
      rows = rows.filter(
        (r) => r.callerDepartment === filterDept || r.calleeDepartment === filterDept,
      );
    }
    if (filterUnassigned) {
      rows = rows.filter((r) => !r.callerEmployeeId || !r.calleeEmployeeId);
    }
    rows.sort((a, b) => {
      const la = levelRank(a.liveLevel || (a.active ? null : a.peakLevel));
      const lb = levelRank(b.liveLevel || (b.active ? null : b.peakLevel));
      if (lb !== la) return lb - la;
      const sa = Number(a.liveScore ?? a.peakScore ?? 0);
      const sb = Number(b.liveScore ?? b.peakScore ?? 0);
      return sb - sa;
    });
    return rows;
  }, [items, filterLevel, filterDept, filterUnassigned]);

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

  async function onForceL3(row) {
    if (!canAct || !row?.id) return;
    setForceBusy(true);
    try {
      await apiJson(`/api/v2/calls/${encodeURIComponent(row.id)}/force-level`, {
        method: 'POST',
        body: JSON.stringify({ level: 'LEVEL_3_HARD_INTERVENTION', reason: 'Operator force L3' }),
      });
      push('Forced L3', 'ok');
      void load();
    } catch (err) {
      push(err instanceof Error ? err.message : 'Force L3 failed');
    } finally {
      setForceBusy(false);
    }
  }

  return (
    <div className="flex h-full min-h-0 flex-col">
      {/* TOP BAR */}
      <header className="flex flex-wrap items-center gap-3 border-b border-sv-border bg-sv-panel px-4 py-2 text-xs">
        <span className="font-display text-sm font-semibold text-sv-fg">
          {me?.tenant?.name || me?.tenant?.slug || 'Tenant'}
        </span>
        <Badge tone={connLabel === 'live' ? 'success' : 'warn'}>STOMP {connLabel}</Badge>
        <span className="text-sv-muted">
          LLM{' '}
          <span className="text-sv-fg">
            {llmHealth?.ok === true || llmHealth?.status === 'ok' ? 'ok' : llmHealth ? 'degraded' : '—'}
          </span>
        </span>
        <span className="text-sv-muted">
          Telephony{' '}
          <span className="text-sv-fg">
            {telHealth?.ok === true || telHealth?.reachable === true ? 'ok' : telHealth ? 'check' : '—'}
          </span>
        </span>
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
          <input
            type="checkbox"
            checked={muteAlerts}
            onChange={(e) => setMuteAlerts(e.target.checked)}
          />
          Mute L3 tone
        </label>
        {canKill ? (
          <Button
            variant="secondary"
            className="!py-1 text-[11px] text-red-300"
            title="Open dashboard kill switch (password re-entry)"
            onClick={() => navigate('/app')}
          >
            Kill switch
          </Button>
        ) : null}
        <span className="truncate text-sv-muted" title={me?.user?.email}>
          {me?.user?.displayName || me?.user?.email}
        </span>
      </header>

      {stale || sockErr ? (
        <div
          role="alert"
          className="border-b border-amber-500/40 bg-amber-950/40 px-4 py-2 text-sm text-amber-100"
        >
          Stale data — socket {connLabel}
          {sockErr ? ` (${sockErr})` : ''}. Levels from cache are not shown as live.
          <Button variant="ghost" className="ml-2 !py-0.5 text-xs" onClick={() => void load()}>
            Refresh
          </Button>
        </div>
      ) : null}

      <div className="grid min-h-0 flex-1 grid-cols-1 lg:grid-cols-[minmax(260px,0.9fr)_minmax(0,1.4fr)_minmax(280px,0.95fr)]">
        {/* LEFT — call list */}
        <aside className="flex min-h-0 flex-col border-r border-sv-border">
          <div className="space-y-2 border-b border-sv-border p-3">
            <div className="flex flex-wrap gap-2">
              <select
                className="rounded border border-sv-border bg-sv-bg px-2 py-1 text-xs"
                value={filterLevel}
                onChange={(e) => setFilterLevel(Number(e.target.value))}
                aria-label="Filter by min level"
              >
                <option value={0}>Level ≥ any</option>
                <option value={1}>Level ≥ 1</option>
                <option value={2}>Level ≥ 2</option>
                <option value={3}>Level ≥ 3</option>
              </select>
              <select
                className="rounded border border-sv-border bg-sv-bg px-2 py-1 text-xs"
                value={filterDept}
                onChange={(e) => setFilterDept(e.target.value)}
                aria-label="Filter department"
              >
                <option value="">All departments</option>
                {departments.map((d) => (
                  <option key={d} value={d}>
                    {d}
                  </option>
                ))}
              </select>
              <label className="flex items-center gap-1 text-[11px] text-sv-muted">
                <input
                  type="checkbox"
                  checked={filterUnassigned}
                  onChange={(e) => setFilterUnassigned(e.target.checked)}
                />
                Unassigned
              </label>
            </div>
          </div>
          <div className="min-h-0 flex-1 overflow-y-auto">
            {loading && !sorted.length ? (
              <p className="p-4 text-sm text-sv-muted">Loading calls…</p>
            ) : !sorted.length ? (
              <div className="p-4">
                <EmptyState
                  title="No live calls"
                  description="No live calls — place a call to see it here."
                />
                <Link
                  to="/app/settings/telephony"
                  className="mt-3 inline-block text-sm text-sv-accent underline-offset-2 hover:underline"
                >
                  Telephony setup help →
                </Link>
              </div>
            ) : (
              <ul className="divide-y divide-sv-border">
                {sorted.map((row) => {
                  const level =
                    row.active && showLiveLevels
                      ? row.liveLevel
                      : row.active
                        ? null
                        : row.peakLevel;
                  const selected = selectedRow && selectedRow.id === row.id;
                  const hist = row.scoreHistory || scoreHistRef.current[String(row.svSessionUuid || row.id)] || [];
                  return (
                    <li key={row.id}>
                      <button
                        type="button"
                        onClick={() => {
                          setSelectedId(row.id);
                          setUnackedAlerts((n) => Math.max(0, n - 1));
                        }}
                        className={`flex w-full flex-col gap-1 px-3 py-2.5 text-left transition hover:bg-sv-elevated/60 ${
                          selected ? 'bg-sv-accent/10' : ''
                        }`}
                      >
                        <div className="flex items-start justify-between gap-2">
                          <div className="min-w-0">
                            <p className="truncate text-sm font-medium text-sv-fg">
                              {row.callerName}
                              {row.callerTitle ? (
                                <span className="ml-1 text-[10px] font-normal text-sv-muted">
                                  ({row.callerTitle})
                                </span>
                              ) : null}
                            </p>
                            <p className="truncate text-xs text-sv-muted">→ {row.calleeName}</p>
                          </div>
                          <LlmDot state={row.llmState || row.linguisticStatus} />
                        </div>
                        <div className="flex flex-wrap items-center gap-2">
                          <span
                            className={`rounded px-1.5 py-0.5 font-mono text-[10px] ${
                              row.active && showLiveLevels
                                ? levelChip(level)
                                : 'bg-sv-elevated text-sv-muted'
                            }`}
                          >
                            {row.active
                              ? showLiveLevels
                                ? level || '—'
                                : 'stale'
                              : 'ended'}
                          </span>
                          <span className="font-mono text-[10px] text-sv-muted">
                            {formatDuration(row.durationSec)}
                          </span>
                          <Sparkline points={hist} />
                          {row.pendingAction ? (
                            <span className="rounded bg-amber-500/20 px-1.5 py-0.5 text-[10px] text-amber-200">
                              {row.pendingAction}
                            </span>
                          ) : null}
                        </div>
                      </button>
                    </li>
                  );
                })}
              </ul>
            )}
          </div>
        </aside>

        {/* CENTER — detail */}
        <section className="min-h-0 overflow-y-auto border-r border-sv-border p-3">
          {!selectedRow ? (
            <p className="text-sm text-sv-muted">Select a call from the list.</p>
          ) : (
            <div className="space-y-3">
              <div className="flex flex-wrap items-end justify-between gap-2">
                <div>
                  <h2 className="text-base font-semibold text-sv-fg">
                    {selectedRow.callerName} → {selectedRow.calleeName}
                  </h2>
                  <p className="text-xs text-sv-muted">
                    {[selectedRow.callerDepartment, selectedRow.calleeDepartment]
                      .filter(Boolean)
                      .join(' / ') || 'No department'}
                    {' · '}
                    {formatDuration(selectedRow.durationSec)}
                    {selectedSession ? (
                      <span className="ml-2 font-mono text-[10px]">{selectedSession.slice(0, 8)}…</span>
                    ) : null}
                  </p>
                </div>
                {selectedRow.active && liveLevel ? (
                  <span className={`rounded px-2 py-1 font-mono text-xs ${levelChip(liveLevel)}`}>
                    {liveLevel}
                  </span>
                ) : (
                  <span className="text-xs text-sv-muted">Not live</span>
                )}
              </div>

              <div className="grid gap-3 md:grid-cols-2">
                <div className="rounded border border-sv-border bg-sv-elevated/20 p-2">
                  {selectedRow.active && latest ? (
                    <RiskGauge frame={latest} />
                  ) : (
                    <p className="p-6 text-center text-sm text-sv-muted">
                      {selectedRow.active
                        ? 'Waiting for live telemetry…'
                        : 'Call ended — open Call History for the dossier.'}
                    </p>
                  )}
                </div>
                <div className="rounded border border-sv-border bg-sv-elevated/20 p-2">
                  <p className="mb-1 font-mono text-[10px] uppercase tracking-wide text-sv-muted">
                    Timeline (live)
                  </p>
                  {selectedRow.active ? (
                    <RiskTimeline history={history} />
                  ) : (
                    <p className="text-xs text-sv-muted">No live timeline for ended calls.</p>
                  )}
                </div>
              </div>

              <div className="rounded border border-sv-border bg-sv-elevated/20 p-2">
                <p className="mb-1 font-mono text-[10px] uppercase tracking-wide text-sv-muted">
                  Why now
                </p>
                {latest ? (
                  <WhyPanel frame={latest} channelProfile={latest?.channelProfile} />
                ) : (
                  <p className="text-xs text-sv-muted">Reasons appear when telemetry arrives.</p>
                )}
              </div>

              <div className="rounded border border-sv-border bg-sv-elevated/20 p-2">
                <p className="mb-1 font-mono text-[10px] uppercase tracking-wide text-sv-muted">
                  Evidence families
                </p>
                <FamilyStrip frame={latest} />
                <div className="mt-2 flex justify-center">
                  <FamilyRadar frame={latest} />
                </div>
              </div>

              <div className="grid gap-3 md:grid-cols-2">
                <div className="rounded border border-sv-border bg-sv-elevated/20 p-2">
                  <IdentityCard frame={latest} />
                </div>
                <div className="rounded border border-sv-border bg-sv-elevated/20 px-3 py-2 text-sm">
                  <p className="text-[11px] font-semibold uppercase tracking-wide text-sv-muted">
                    Linguistic
                  </p>
                  <p className="mt-1 text-xs text-sv-muted">
                    Stage {selectedRow.linguisticSource || '—'}
                    {selectedRow.linguisticAgeMs != null
                      ? ` · age ${Math.round(selectedRow.linguisticAgeMs)}ms`
                      : ''}
                  </p>
                  {latest?.linguistic || latest?.families?.linguistic ? (
                    <div className="mt-2 grid grid-cols-2 gap-1.5 text-[11px]">
                      <p>
                        Ask{' '}
                        <span className="font-mono text-sv-fg">
                          {latest?.linguistic?.askType ||
                            latest?.families?.linguistic?.askType ||
                            '—'}
                        </span>
                      </p>
                      <p>
                        Amt{' '}
                        <span className="font-mono text-sv-fg">
                          {latest?.linguistic?.amountAsked ??
                            latest?.families?.linguistic?.amountAsked ??
                            '—'}
                        </span>
                      </p>
                      <p>
                        Urgency{' '}
                        <span className="font-mono text-sv-fg">
                          {meter(latest?.linguistic?.urgency ?? latest?.families?.linguistic?.urgency)}
                        </span>
                      </p>
                      <p>
                        Secrecy{' '}
                        <span className="font-mono text-sv-fg">
                          {meter(latest?.linguistic?.secrecy ?? latest?.families?.linguistic?.secrecy)}
                        </span>
                      </p>
                    </div>
                  ) : null}
                  <p className="mt-2 text-[11px] font-semibold uppercase text-sv-muted">Spoken (ASR)</p>
                  <p className="mt-1 min-h-[2rem] whitespace-pre-wrap font-mono text-[11px] text-sv-fg/80">
                    {selectedRow.asrTranscript ||
                      transcriptByCallRef.current[String(selectedRow.id || '')] ||
                      (selectedRow.active ? 'Waiting for speech…' : '—')}
                  </p>
                  <p className="mt-2 text-[11px] font-semibold uppercase text-sv-muted">LLM thinking</p>
                  <p className="mt-1 min-h-[2.5rem] whitespace-pre-wrap text-xs text-sv-fg/90">
                    {selectedRow.llmThinking ||
                      thinkingByCallRef.current[String(selectedRow.id || '')] ||
                      '—'}
                  </p>
                  <p className="mt-2 text-[11px] font-semibold uppercase text-sv-muted">Matched rules</p>
                  <ul className="mt-1 space-y-0.5 font-mono text-[11px]">
                    {(selectedRow.brokenRuleTitles?.length
                      ? selectedRow.brokenRuleTitles
                      : selectedRow.brokenRuleIds || []
                    ).length === 0 ? (
                      <li className="text-sv-muted">None yet</li>
                    ) : (
                      (selectedRow.brokenRuleTitles?.length
                        ? selectedRow.brokenRuleTitles
                        : selectedRow.brokenRuleIds
                      ).map((t) => <li key={t}>{t}</li>)
                    )}
                  </ul>
                  <div className="mt-2 flex flex-wrap gap-1">
                    {(selectedRow.matchedKeywords || []).map((k) => (
                      <Badge key={k} tone="warn">
                        {k}
                      </Badge>
                    ))}
                  </div>
                </div>
              </div>

              {selectedRow.active ? (
                <div className="rounded border border-sv-border bg-sv-elevated/20 p-2">
                  <p className="mb-1 font-mono text-[10px] uppercase tracking-wide text-sv-muted">
                    Spectrogram (feature stream)
                  </p>
                  <SpectrogramCanvas frame={latest} channelProfile="PSTN_NARROWBAND" />
                </div>
              ) : null}

              {selectedRow.svSessionUuid ? (
                <Link
                  to={`/app/sessions/${encodeURIComponent(selectedRow.svSessionUuid)}`}
                  className="text-sm text-sv-accent underline-offset-2 hover:underline"
                >
                  Open call dossier →
                </Link>
              ) : null}
            </div>
          )}
        </section>

        {/* RIGHT — actions */}
        <aside className="min-h-0 overflow-y-auto p-3">
          {!selectedRow || !selectedSession ? (
            <p className="text-sm text-sv-muted">Actions appear for a selected live session.</p>
          ) : (
            <div className="space-y-3">
              <div className="rounded border border-sv-border bg-sv-elevated/20 p-2">
                <p className="text-[11px] font-semibold uppercase tracking-wide text-sv-muted">
                  Response plan steps
                </p>
                <ul className="mt-2 space-y-1 text-xs">
                  {(selectedRow.activeActions || []).length === 0 ? (
                    <li className="text-sv-muted">No pending plan steps</li>
                  ) : (
                    (selectedRow.activeActions || []).map((a) => (
                      <li
                        key={a}
                        className="flex items-center justify-between rounded border border-sv-border/60 px-2 py-1"
                      >
                        <span className="font-mono text-[11px]">{a}</span>
                        <span className="text-[10px] text-amber-200">Pending</span>
                      </li>
                    ))
                  )}
                </ul>
                {selectedRow.pendingAction ? (
                  <p className="mt-2 text-xs text-amber-200">Awaiting operator: {selectedRow.pendingAction}</p>
                ) : null}
              </div>

              {latest ? (
                <InterventionBar frame={latest} onOverrideClick={() => setOverrideOpen(true)} />
              ) : null}

              <div className="flex flex-wrap gap-2">
                {canAct && selectedRow.callbackRequired ? (
                  <Button
                    disabled={callbackBusy}
                    onClick={() => void onConfirmCallback(selectedRow)}
                  >
                    Confirm callback
                  </Button>
                ) : null}
                {canBridge && selectedRow.active ? (
                  <Button
                    variant="secondary"
                    disabled={bridgeBusy}
                    onClick={() => void onBridge(selectedRow)}
                  >
                    Bridge supervisor
                  </Button>
                ) : null}
                {canAct && selectedRow.active ? (
                  <Button
                    variant="secondary"
                    disabled={forceBusy}
                    onClick={() => void onForceL3(selectedRow)}
                  >
                    Force L3
                  </Button>
                ) : null}
              </div>

              {selectedRow.approvalLocked ? (
                <p className="rounded border border-red-500/40 bg-red-950/30 px-2 py-2 text-xs text-red-100">
                  {selectedRow.approvalLockReason || 'Approve is locked.'}
                </p>
              ) : null}

              <TransactionPanel
                sessionId={selectedSession}
                frame={latest}
                locked={Boolean(selectedRow.approvalLocked)}
                lockReason={selectedRow.approvalLockReason}
              />
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
              {canBridge ? (
                <p className="text-[10px] text-sv-muted">
                  Supervisor co-approval: bridge into the call, then confirm overrides that require it.
                </p>
              ) : null}
            </div>
          )}
        </aside>
      </div>
    </div>
  );
}
