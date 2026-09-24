/**
 * Live Calls — operator workspace: list + live risk / captions / rules / actions.
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

const POLL_MS = 2500;
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
  if (r >= 3) return 'bg-risk-critical text-white';
  if (r === 2) return 'bg-risk-watch text-sv-bg';
  if (r === 1) return 'bg-risk-clear text-white';
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

function llmTone(state) {
  if (state === 'pending') return 'warn';
  if (state === 'ready' || state === 'live') return 'success';
  return 'neutral';
}

function Panel({ title, hint, children, className = '' }) {
  return (
    <section className={`rounded-lg border border-sv-border bg-sv-panel p-3 shadow-sm shadow-black/5 ${className}`}>
      {(title || hint) && (
        <div className="mb-2 flex items-baseline justify-between gap-2">
          {title ? (
            <h3 className="font-mono text-[10px] font-semibold uppercase tracking-wider text-sv-muted">{title}</h3>
          ) : (
            <span />
          )}
          {hint ? <span className="text-[10px] text-sv-muted">{hint}</span> : null}
        </div>
      )}
      {children}
    </section>
  );
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
  const [clearBusy, setClearBusy] = useState(false);
  const [nowTick, setNowTick] = useState(() => Date.now());

  const thinkingByCallRef = useRef({});
  const transcriptByCallRef = useRef({});
  const captionScrollRef = useRef(/** @type {HTMLDivElement | null} */ (null));
  const prevL3Ref = useRef(/** @type {Set<string>} */ (new Set()));

  const canRead = hasPermission('calls:read');
  const canAct = hasPermission('calls:act');
  const canBridge = hasPermission('calls:bridge');
  const canKill = hasPermission('calls:kill');
  const canClear = hasPermission('calls:kill') || hasPermission('calls:bridge');

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
    lastMessageAt,
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
      const data = await apiJson('/api/v2/calls?limit=80&activeOnly=true', {
        skipErrorToast: true,
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
    const id = window.setInterval(() => setNowTick(Date.now()), 1000);
    return () => window.clearInterval(id);
  }, []);

  useEffect(() => {
    const keys = Object.keys(callDeltas || {});
    if (!keys.length) return;
    setItems((prev) => {
      const byKey = new Map(prev.map((r) => [String(r.svSessionUuid || r.id), r]));
      for (const k of keys) {
        const d = callDeltas[k];
        if (d.active === false) {
          byKey.delete(k);
          continue;
        }
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
      return Array.from(byKey.values()).filter((r) => r.active !== false);
    });
  }, [callDeltas, mergeRow]);

  // Merge live telemetry captions into the selected row immediately.
  // Prefer growth / stable supersets — reject shorter near-duplicates that flicker.
  useEffect(() => {
    const text = latest?.transcriptDelta?.text;
    if (!selectedRow || !text || typeof text !== 'string' || !text.trim()) return;
    const key = String(selectedRow.id || selectedRow.svSessionUuid || '');
    if (!key) return;
    const next = text.trim();
    const prev = (transcriptByCallRef.current[key] || '').trim();
    const prevNorm = prev.toLowerCase().replace(/\s+/g, ' ');
    const nextNorm = next.toLowerCase().replace(/\s+/g, ' ');
    if (prevNorm && nextNorm === prevNorm) return;
    if (prevNorm && nextNorm.length + 8 < prevNorm.length && prevNorm.includes(nextNorm)) return;
    transcriptByCallRef.current[key] = next;
    setItems((prevItems) =>
      prevItems.map((r) =>
        String(r.id) === String(selectedRow.id) || String(r.svSessionUuid) === String(selectedSession)
          ? { ...r, asrTranscript: next }
          : r,
      ),
    );
  }, [latest?.transcriptDelta?.text, selectedRow?.id, selectedRow?.svSessionUuid, selectedSession]);

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

  const liveCaption = useMemo(() => {
    if (!selectedRow) return '';
    const key = String(selectedRow.id || '');
    const fromTelemetry =
      typeof latest?.transcriptDelta?.text === 'string' ? latest.transcriptDelta.text.trim() : '';
    const fromRow =
      typeof selectedRow.asrTranscript === 'string' ? selectedRow.asrTranscript.trim() : '';
    const fromRef = transcriptByCallRef.current[key] || '';
    // Prefer the longest stable caption (hop ASR publishes full rolling snippet).
    return [fromTelemetry, fromRow, fromRef].reduce((best, cur) => {
      if (!cur) return best;
      if (!best) return cur;
      if (cur.length >= best.length) return cur;
      if (best.toLowerCase().includes(cur.toLowerCase())) return best;
      return cur;
    }, '');
  }, [selectedRow, latest?.transcriptDelta?.text, items]);

  useEffect(() => {
    const el = captionScrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [liveCaption]);

  const sorted = useMemo(() => {
    const rows = items.slice();
    rows.sort((a, b) => {
      if (Boolean(a.active) !== Boolean(b.active)) return a.active ? -1 : 1;
      const la = levelRank(a.liveLevel || (a.active ? null : a.peakLevel));
      const lb = levelRank(b.liveLevel || (b.active ? null : b.peakLevel));
      if (lb !== la) return lb - la;
      return Number(b.liveScore ?? b.peakScore ?? 0) - Number(a.liveScore ?? a.peakScore ?? 0);
    });
    return rows;
  }, [items]);

  const liveDurationSec = useCallback(
    (row) => {
      if (!row?.active || !row.startedAt) return Number(row?.durationSec) || 0;
      const start = Date.parse(row.startedAt);
      if (Number.isNaN(start)) return Number(row.durationSec) || 0;
      return Math.max(0, Math.floor((nowTick - start) / 1000));
    },
    [nowTick],
  );

  if (!canRead) {
    return <Navigate to="/app" replace />;
  }

  const showLiveLevels = !stale;
  const liveLevel =
    selectedRow?.active && showLiveLevels
      ? selectedRow.liveLevel || latest?.intervention?.level || latest?.risk?.level || null
      : null;
  const connLabel =
    connectionState === CONNECTION_STATES.CONNECTED
      ? 'live'
      : connectionState === CONNECTION_STATES.RECONNECTING
        ? 'reconnecting'
        : 'offline';
  const activeCount = items.filter((r) => r.active).length;
  const llmState = selectedRow?.llmState || 'idle';
  const thinking =
    selectedRow?.llmThinking ||
    thinkingByCallRef.current[String(selectedRow?.id || '')] ||
    '';
  const rules =
    selectedRow?.brokenRuleTitles?.length
      ? selectedRow.brokenRuleTitles
      : selectedRow?.brokenRuleIds || [];
  const ageSinceMsg =
    lastMessageAt != null ? Math.max(0, Math.round((nowTick - lastMessageAt) / 1000)) : null;

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

  async function onClearAll() {
    if (!canClear) return;
    if (!window.confirm('End all active live calls for this tenant?')) return;
    setClearBusy(true);
    try {
      const res = await apiJson('/api/v2/calls/clear-active', { method: 'POST' });
      thinkingByCallRef.current = {};
      transcriptByCallRef.current = {};
      setSelectedId(null);
      push(`Cleared ${res.clearedDb ?? 0} DB / ${res.clearedMemory ?? 0} memory sessions`, 'ok');
      void load();
    } catch (err) {
      push(err instanceof Error ? err.message : 'Clear failed');
    } finally {
      setClearBusy(false);
    }
  }

  return (
    <div className="flex h-full min-h-0 flex-col bg-sv-bg">
      <header className="flex flex-wrap items-center gap-3 border-b border-sv-border bg-sv-panel px-4 py-2.5">
        <div className="min-w-0">
          <h1 className="font-display text-base font-semibold text-sv-fg">Live calls</h1>
          <p className="text-[11px] text-sv-muted">
            {activeCount} active · feed {connLabel}
            {ageSinceMsg != null ? ` · last frame ${ageSinceMsg}s ago` : ''}
          </p>
        </div>
        <Badge tone={connLabel === 'live' ? 'success' : 'warn'}>{connLabel}</Badge>
        {unackedAlerts > 0 ? (
          <button
            type="button"
            className="rounded bg-risk-critical px-2 py-0.5 text-xs font-semibold text-white"
            onClick={() => setUnackedAlerts(0)}
          >
            {unackedAlerts} alert{unackedAlerts === 1 ? '' : 's'} — ack
          </button>
        ) : null}
        <div className="ml-auto flex flex-wrap items-center gap-2">
          <label className="flex items-center gap-1.5 text-xs text-sv-muted">
            <input type="checkbox" checked={muteAlerts} onChange={(e) => setMuteAlerts(e.target.checked)} />
            Mute alerts
          </label>
          <Button variant="ghost" className="!py-1 text-[11px]" onClick={() => void load()}>
            Refresh
          </Button>
          {canClear ? (
            <Button
              variant="secondary"
              className="!py-1 text-[11px]"
              disabled={clearBusy || activeCount === 0}
              onClick={() => void onClearAll()}
            >
              {clearBusy ? 'Clearing…' : 'Clear all'}
            </Button>
          ) : null}
          {canKill ? (
            <Button variant="secondary" className="!py-1 text-[11px]" onClick={() => navigate('/app')}>
              Kill switch
            </Button>
          ) : null}
        </div>
      </header>

      {stale || sockErr || loadError ? (
        <div
          role="alert"
          className="border-b border-risk-watch/40 bg-risk-watch/10 px-4 py-2 text-sm text-sv-fg"
        >
          {loadError ? <span className="text-risk-critical">API: {loadError}. </span> : null}
          {stale || sockErr ? (
            <span>
              Connection {connLabel}
              {sockErr ? ` — ${sockErr}` : ''}. Captions still update on poll.
            </span>
          ) : null}
        </div>
      ) : null}

      <div className="grid min-h-0 flex-1 grid-cols-1 lg:grid-cols-[minmax(300px,340px)_minmax(0,1fr)]">
        <aside className="flex min-h-0 flex-col border-r border-sv-border bg-sv-panel/60">
          <div className="border-b border-sv-border px-3 py-2 font-mono text-[10px] uppercase tracking-wider text-sv-muted">
            Sessions
          </div>
          <div className="min-h-0 flex-1 overflow-y-auto">
            {loading && !sorted.length ? (
              <p className="p-4 text-sm text-sv-muted">Loading…</p>
            ) : !sorted.length ? (
              <div className="p-4">
                <EmptyState
                  title="No live calls"
                  description="Place a softphone call with asterisk_bridge running so AudioSocket feeds ASR. Softphone audio alone will not generate captions."
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
                  const preview =
                    row.asrTranscript || transcriptByCallRef.current[String(row.id || '')] || '';
                  return (
                    <li key={row.id}>
                      <button
                        type="button"
                        onClick={() => {
                          setSelectedId(row.id);
                          setUnackedAlerts((n) => Math.max(0, n - 1));
                        }}
                        className={`flex w-full flex-col gap-1.5 px-3 py-3 text-left transition-colors hover:bg-sv-elevated/70 ${
                          selected ? 'border-l-2 border-l-sv-accent bg-sv-accent/10' : 'border-l-2 border-l-transparent'
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
                        <div className="flex items-center justify-between gap-2 font-mono text-[10px] text-sv-muted">
                          <span>{formatDuration(liveDurationSec(row))}</span>
                          {row.active && row.llmState && row.llmState !== 'idle' ? (
                            <Badge tone={llmTone(row.llmState)}>{row.llmState}</Badge>
                          ) : null}
                        </div>
                        {preview ? (
                          <p className="line-clamp-2 text-[11px] leading-snug text-sv-fg/75">{preview}</p>
                        ) : row.active ? (
                          <p className="text-[11px] italic text-sv-muted">Listening…</p>
                        ) : null}
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
            <div className="flex h-full items-center justify-center">
              <p className="text-sm text-sv-muted">Select a call to monitor.</p>
            </div>
          ) : (
            <div className="mx-auto flex max-w-5xl flex-col gap-4">
              <div className="flex flex-wrap items-start justify-between gap-3">
                <div className="min-w-0">
                  <h2 className="font-display text-xl font-semibold text-sv-fg">
                    {selectedRow.callerName}
                    <span className="mx-2 text-sv-muted">→</span>
                    {selectedRow.calleeName}
                  </h2>
                  <p className="mt-0.5 font-mono text-xs text-sv-muted">
                    {formatDuration(liveDurationSec(selectedRow))}
                    {selectedRow.svSessionUuid
                      ? ` · ${String(selectedRow.svSessionUuid).slice(0, 8)}…`
                      : ''}
                  </p>
                </div>
                <div className="flex flex-wrap items-center gap-2">
                  {selectedRow.active && liveLevel ? (
                    <span className={`rounded px-2.5 py-1 font-mono text-xs font-semibold ${levelChip(liveLevel)}`}>
                      {shortLevel(liveLevel)}
                    </span>
                  ) : (
                    <span className="text-xs text-sv-muted">
                      {selectedRow.active ? 'Waiting for risk…' : 'Ended'}
                    </span>
                  )}
                  <Badge tone={llmTone(llmState)}>ASR/LLM · {llmState}</Badge>
                </div>
              </div>

              <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_minmax(280px,340px)]">
                <div className="flex flex-col gap-4">
                  <Panel
                    title="Live captions"
                    hint={selectedRow.active ? 'updates as speech is recognized' : 'final'}
                    className="min-h-[200px] flex-1"
                  >
                    <div
                      ref={captionScrollRef}
                      className="max-h-[280px] min-h-[140px] overflow-y-auto rounded border border-sv-border/70 bg-sv-bg/60 px-3 py-3"
                    >
                      {liveCaption ? (
                        <p className="whitespace-pre-wrap font-mono text-sm leading-relaxed text-sv-fg">
                          {liveCaption}
                        </p>
                      ) : (
                        <p className="text-sm text-sv-muted">
                          {selectedRow.active
                            ? 'Waiting for speech… Ensure asterisk_bridge is running and LAB_MODE=true.'
                            : 'No captions recorded.'}
                        </p>
                      )}
                    </div>
                    {(selectedRow.matchedKeywords || []).length ? (
                      <div className="mt-2 flex flex-wrap gap-1">
                        {(selectedRow.matchedKeywords || []).map((k) => (
                          <Badge key={k} tone="warn">
                            {k}
                          </Badge>
                        ))}
                      </div>
                    ) : null}
                  </Panel>

                  <Panel title="LLM judgment" hint={llmState}>
                    {thinking ? (
                      <p className="whitespace-pre-wrap text-sm leading-relaxed text-sv-fg">{thinking}</p>
                    ) : (
                      <p className="text-sm text-sv-muted">
                        {selectedRow.active
                          ? llmState === 'pending'
                            ? 'Model is judging the utterance…'
                            : 'No judgment yet — speak, or publish ACTIVE policy rules.'
                          : '—'}
                      </p>
                    )}
                  </Panel>

                  <Panel title="Matched rules">
                    {rules.length === 0 ? (
                      <p className="text-sm text-sv-muted">None yet.</p>
                    ) : (
                      <ul className="space-y-1.5">
                        {rules.map((t) => (
                          <li
                            key={t}
                            className="rounded border border-risk-watch/30 bg-risk-watch/10 px-2 py-1.5 font-mono text-xs text-sv-fg"
                          >
                            {t}
                          </li>
                        ))}
                      </ul>
                    )}
                  </Panel>
                </div>

                <div className="flex flex-col gap-4">
                  <Panel title="Risk">
                    {selectedRow.active && latest ? (
                      <RiskGauge frame={latest} />
                    ) : (
                      <p className="py-8 text-center text-sm text-sv-muted">
                        {selectedRow.active ? 'Waiting for live risk frames…' : 'Call ended.'}
                      </p>
                    )}
                  </Panel>

                  <Panel title="Why">
                    {latest ? (
                      <WhyPanel frame={latest} channelProfile={latest?.channelProfile} />
                    ) : (
                      <p className="text-xs text-sv-muted">Reasons appear with telemetry.</p>
                    )}
                  </Panel>

                  {selectedRow.active && selectedSession ? (
                    <Panel title="Actions">
                      {selectedRow.approvalLocked ? (
                        <p className="mb-2 rounded border border-risk-critical/40 bg-risk-critical/10 px-2 py-2 text-xs text-risk-critical">
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
                      <div className="mt-3">
                        <ChallengePanel sessionId={selectedSession} />
                      </div>
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
                    </Panel>
                  ) : null}
                </div>
              </div>

              {selectedRow.svSessionUuid ? (
                <Link
                  to={`/app/sessions/${encodeURIComponent(selectedRow.svSessionUuid)}`}
                  className="text-sm text-sv-accent hover:underline"
                >
                  Open full dossier →
                </Link>
              ) : null}
            </div>
          )}
        </section>
      </div>
    </div>
  );
}
