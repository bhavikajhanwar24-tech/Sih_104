import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, Navigate } from 'react-router-dom';
import { useAuth } from '@/context/AuthContext.jsx';
import { apiJson } from '@/services/api.js';
import { Badge, Button, Input, Select, Table } from '@/ui';
import { Toggle } from '@/components/ui/Toggle.jsx';
import { useToast } from '@/ui/Toast.jsx';

const FAMILY_KEYS = ['voice', 'channel', 'prosody', 'linguistic', 'transaction', 'relationship'];
const ACOUSTIC_KEYS = ['voice', 'channel', 'prosody'];
const LEVEL_KEYS = ['L1', 'L2', 'L3', 'L4'];
const WEIGHT_SUM_TOL = 0.001;
const MAX_ACOUSTIC = 0.55;

const STATUS_TONE = {
  ACTIVE: 'success',
  DRAFT: 'accent',
  PENDING_APPROVAL: 'warn',
  SUPERSEDED: 'neutral',
  REJECTED: 'danger',
};

/**
 * @param {unknown} v
 * @returns {number}
 */
function num(v) {
  const n = Number(v);
  return Number.isFinite(n) ? n : 0;
}

/**
 * @param {Record<string, unknown> | null | undefined} config
 * @returns {Record<string, unknown> | null}
 */
function cloneConfig(config) {
  if (!config) return null;
  return /** @type {Record<string, unknown>} */ (JSON.parse(JSON.stringify(config)));
}

/**
 * @param {Record<string, number> | undefined} weights
 */
function weightSum(weights) {
  if (!weights) return 0;
  return FAMILY_KEYS.reduce((s, k) => s + num(weights[k]), 0);
}

/**
 * @param {Record<string, number> | undefined} weights
 */
function acousticTotal(weights) {
  if (!weights) return 0;
  return ACOUSTIC_KEYS.reduce((s, k) => s + num(weights[k]), 0);
}

/**
 * Client-side guardrails mirroring FusionConfigValidator (hints only).
 * @param {Record<string, any> | null | undefined} config
 * @returns {string[]}
 */
function collectGuardrailHints(config) {
  /** @type {string[]} */
  const hints = [];
  if (!config) return hints;

  for (const profile of ['wideband', 'narrowband']) {
    const w = config.weights?.[profile];
    if (!w) {
      hints.push(`weights.${profile} is required`);
      continue;
    }
    const sum = weightSum(w);
    if (Math.abs(sum - 1) > WEIGHT_SUM_TOL) {
      hints.push(`weights.${profile} sum ≠ 1 (got ${sum.toFixed(3)})`);
    }
    const acoustic = acousticTotal(w);
    if (acoustic > MAX_ACOUSTIC + WEIGHT_SUM_TOL) {
      hints.push(`weights.${profile} acoustic (voice+channel+prosody) > 0.55 (got ${acoustic.toFixed(3)})`);
    }
    for (const k of FAMILY_KEYS) {
      const v = num(w[k]);
      if (v < 0 || v > 0.6) {
        hints.push(`weights.${profile}.${k} must be in [0, 0.6]`);
      }
    }
  }

  const levels = config.levels || {};
  const enters = LEVEL_KEYS.map((k) => num(levels[k]?.enter));
  if (!(enters[0] < enters[1] && enters[1] < enters[2] && enters[2] < enters[3])) {
    hints.push('level enter thresholds must be strictly increasing L1 < L2 < L3 < L4');
  }
  for (const k of LEVEL_KEYS) {
    const band = levels[k];
    if (!band) {
      hints.push(`levels.${k} is required`);
      continue;
    }
    if (num(band.exit) >= num(band.enter)) {
      hints.push(`levels.${k}: exit ≥ enter (need hysteresis)`);
    }
  }

  const minL4 = num(config.corroboration?.minForL4);
  if (minL4 < 2) {
    hints.push('corroboration.minForL4 must be ≥ 2 (L4 cannot be a single-family trigger)');
  }

  return hints;
}

/**
 * Flatten nested objects to dotted paths for diff.
 * @param {unknown} obj
 * @param {string} [prefix]
 * @returns {Record<string, string>}
 */
function flatten(obj, prefix = '') {
  /** @type {Record<string, string>} */
  const out = {};
  if (obj == null || typeof obj !== 'object') {
    out[prefix || '(root)'] = String(obj);
    return out;
  }
  if (Array.isArray(obj)) {
    out[prefix || '(root)'] = JSON.stringify(obj);
    return out;
  }
  const entries = Object.entries(/** @type {Record<string, unknown>} */ (obj));
  if (entries.length === 0) {
    out[prefix || '(root)'] = '{}';
    return out;
  }
  for (const [k, v] of entries) {
    const path = prefix ? `${prefix}.${k}` : k;
    if (v != null && typeof v === 'object' && !Array.isArray(v)) {
      Object.assign(out, flatten(v, path));
    } else {
      out[path] = typeof v === 'number' ? String(v) : JSON.stringify(v);
    }
  }
  return out;
}

/**
 * @param {Record<string, unknown> | null | undefined} a
 * @param {Record<string, unknown> | null | undefined} b
 * @returns {{ key: string, from: string, to: string }[]}
 */
function diffConfigs(a, b) {
  const fa = flatten(a || {});
  const fb = flatten(b || {});
  const keys = new Set([...Object.keys(fa), ...Object.keys(fb)]);
  /** @type {{ key: string, from: string, to: string }[]} */
  const rows = [];
  for (const key of [...keys].sort()) {
    const from = fa[key] ?? '—';
    const to = fb[key] ?? '—';
    if (from !== to) rows.push({ key, from, to });
  }
  return rows;
}

/**
 * @param {string | null | undefined} iso
 */
function formatDate(iso) {
  if (!iso) return '—';
  try {
    return new Date(iso).toLocaleString();
  } catch {
    return iso;
  }
}

/**
 * F8 — Tenant fusion / risk-tuning editor.
 */
export function RiskTuningPage() {
  const { hasPermission, me } = useAuth();
  const { push } = useToast();

  const canRead = hasPermission('settings:read');
  const canWrite = hasPermission('settings:write');
  const canApprove =
    hasPermission('policies:approve') || me?.role === 'POLICY_APPROVER';

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [activeRow, setActiveRow] = useState(/** @type {any} */ (null));
  const [draftRow, setDraftRow] = useState(/** @type {any} */ (null));
  const [editConfig, setEditConfig] = useState(/** @type {Record<string, any> | null} */ (null));
  const [history, setHistory] = useState(/** @type {any[]} */ ([]));
  const [narrowband, setNarrowband] = useState(false);
  const [rejectComments, setRejectComments] = useState(/** @type {Record<string, string>} */ ({}));
  const [sessionIds, setSessionIds] = useState(/** @type {string[]} */ ([]));
  const [whatIfSessionId, setWhatIfSessionId] = useState('');
  const [whatIfTicks, setWhatIfTicks] = useState(/** @type {any[]} */ ([]));
  const [whatIfBusy, setWhatIfBusy] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [active, hist, sessionsRes] = await Promise.all([
        apiJson('/api/v2/fusion-configs/active'),
        apiJson('/api/v2/fusion-configs/history'),
        apiJson('/api/v1/session', { skipErrorToast: true }).catch(() => ({ sessions: [] })),
      ]);
      setActiveRow(active);
      const items = Array.isArray(hist) ? hist : hist?.items || [];
      setHistory(items);

      const existingDraft = items.find((h) => h.status === 'DRAFT');
      if (existingDraft?.config) {
        setDraftRow(existingDraft);
        setEditConfig(cloneConfig(existingDraft.config));
      } else {
        setDraftRow(null);
        setEditConfig(cloneConfig(active?.config));
      }

      const sessions = Array.isArray(sessionsRes?.sessions) ? sessionsRes.sessions : [];
      const ids = sessions
        .map((s) => (s && typeof s.sessionId === 'string' ? s.sessionId : ''))
        .filter(Boolean);
      setSessionIds(ids);
      if (ids.length && !whatIfSessionId) setWhatIfSessionId(ids[0]);
    } catch (err) {
      push(err instanceof Error ? err.message : 'Failed to load fusion configs');
    } finally {
      setLoading(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- seed session once
  }, [push]);

  useEffect(() => {
    if (canRead) load();
  }, [canRead, load]);

  const weightProfile = narrowband ? 'narrowband' : 'wideband';
  const weights = editConfig?.weights?.[weightProfile] || {};
  const sum = weightSum(weights);
  const acoustic = acousticTotal(weights);
  const contextual = sum - acoustic;
  const sumOk = Math.abs(sum - 1) <= WEIGHT_SUM_TOL;

  const hints = useMemo(() => collectGuardrailHints(editConfig), [editConfig]);
  const configValid = hints.length === 0;
  const isDraft = draftRow?.status === 'DRAFT';
  const dirty =
    isDraft &&
    editConfig &&
    draftRow?.config &&
    JSON.stringify(editConfig) !== JSON.stringify(draftRow.config);

  const diffRows = useMemo(
    () => diffConfigs(activeRow?.config, editConfig),
    [activeRow, editConfig],
  );

  if (!canRead) {
    return <Navigate to="/app" replace />;
  }

  /**
   * @param {string} family
   * @param {number} value
   */
  function setWeight(family, value) {
    if (!editConfig || !canWrite || !isDraft) return;
    setEditConfig((prev) => {
      if (!prev) return prev;
      const next = cloneConfig(prev);
      if (!next.weights) next.weights = {};
      if (!next.weights[weightProfile]) next.weights[weightProfile] = {};
      next.weights[weightProfile][family] = value;
      return next;
    });
  }

  /**
   * @param {(cfg: Record<string, any>) => void} mutator
   */
  function patchConfig(mutator) {
    if (!editConfig || !canWrite || !isDraft) return;
    setEditConfig((prev) => {
      if (!prev) return prev;
      const next = cloneConfig(prev);
      mutator(next);
      return next;
    });
  }

  async function createDraft() {
    if (!canWrite) return;
    setBusy(true);
    try {
      const body =
        editConfig && activeRow?.config
          ? { config: cloneConfig(activeRow.config) }
          : {};
      const row = await apiJson('/api/v2/fusion-configs/draft', {
        method: 'POST',
        body: JSON.stringify(body),
      });
      setDraftRow(row);
      setEditConfig(cloneConfig(row.config));
      push(`Draft v${row.version} created`);
      await loadHistoryOnly();
    } catch (err) {
      push(err instanceof Error ? err.message : 'Create draft failed');
    } finally {
      setBusy(false);
    }
  }

  async function loadHistoryOnly() {
    try {
      const hist = await apiJson('/api/v2/fusion-configs/history', { skipErrorToast: true });
      const items = Array.isArray(hist) ? hist : hist?.items || [];
      setHistory(items);
      const active = await apiJson('/api/v2/fusion-configs/active', { skipErrorToast: true });
      setActiveRow(active);
    } catch {
      /* ignore */
    }
  }

  async function saveDraft() {
    if (!canWrite || !isDraft || !draftRow?.id || !editConfig) return;
    if (!configValid) {
      push('Fix guardrail issues before saving');
      return;
    }
    setBusy(true);
    try {
      const row = await apiJson(`/api/v2/fusion-configs/${encodeURIComponent(draftRow.id)}`, {
        method: 'PUT',
        body: JSON.stringify({ config: editConfig }),
      });
      setDraftRow(row);
      setEditConfig(cloneConfig(row.config));
      push('Draft saved');
      await loadHistoryOnly();
    } catch (err) {
      push(err instanceof Error ? err.message : 'Save failed');
    } finally {
      setBusy(false);
    }
  }

  async function submitDraft() {
    if (!canWrite || !isDraft || !draftRow?.id) return;
    if (!configValid) {
      push('Fix guardrail issues before submit');
      return;
    }
    setBusy(true);
    try {
      if (dirty) {
        await apiJson(`/api/v2/fusion-configs/${encodeURIComponent(draftRow.id)}`, {
          method: 'PUT',
          body: JSON.stringify({ config: editConfig }),
        });
      }
      const row = await apiJson(
        `/api/v2/fusion-configs/${encodeURIComponent(draftRow.id)}/submit`,
        { method: 'POST' },
      );
      setDraftRow(null);
      setEditConfig(cloneConfig(activeRow?.config));
      push(`Submitted v${row.version} for approval`);
      await loadHistoryOnly();
    } catch (err) {
      push(err instanceof Error ? err.message : 'Submit failed');
    } finally {
      setBusy(false);
    }
  }

  /**
   * @param {string} id
   */
  async function approveConfig(id) {
    if (!canApprove) return;
    setBusy(true);
    try {
      await apiJson(`/api/v2/fusion-configs/${encodeURIComponent(id)}/approve`, {
        method: 'POST',
      });
      push('Fusion config approved — now ACTIVE');
      await load();
    } catch (err) {
      push(err instanceof Error ? err.message : 'Approve failed');
    } finally {
      setBusy(false);
    }
  }

  /**
   * @param {string} id
   */
  async function rejectConfig(id) {
    if (!canApprove) return;
    const comment = (rejectComments[id] || '').trim();
    if (!comment) {
      push('Rejection comment is required');
      return;
    }
    setBusy(true);
    try {
      await apiJson(`/api/v2/fusion-configs/${encodeURIComponent(id)}/reject`, {
        method: 'POST',
        body: JSON.stringify({ comment }),
      });
      push('Fusion config rejected');
      setRejectComments((prev) => {
        const next = { ...prev };
        delete next[id];
        return next;
      });
      await load();
    } catch (err) {
      push(err instanceof Error ? err.message : 'Reject failed');
    } finally {
      setBusy(false);
    }
  }

  /**
   * @param {any} row
   */
  async function loadHistoryItem(row) {
    try {
      const full = await apiJson(`/api/v2/fusion-configs/${encodeURIComponent(row.id)}`);
      if (full.status === 'DRAFT') {
        setDraftRow(full);
        setEditConfig(cloneConfig(full.config));
        push(`Editing draft v${full.version}`);
      } else {
        setEditConfig(cloneConfig(full.config));
        if (full.status !== 'DRAFT') setDraftRow(null);
        push(`Loaded v${full.version} (${full.status}) — read-only unless you create a draft`);
      }
    } catch (err) {
      push(err instanceof Error ? err.message : 'Load failed');
    }
  }

  async function runWhatIf() {
    const sid = whatIfSessionId.trim();
    if (!sid) {
      push('Enter or select a sessionId');
      return;
    }
    if (!editConfig) return;
    if (!configValid) {
      push('Fix guardrail issues before what-if');
      return;
    }
    setWhatIfBusy(true);
    setWhatIfTicks([]);
    try {
      const result = await apiJson('/api/v2/fusion-configs/what-if', {
        method: 'POST',
        body: JSON.stringify({ config: editConfig, sessionId: sid }),
      });
      setWhatIfTicks(Array.isArray(result.ticks) ? result.ticks : []);
      push(`What-if replay · ${result.tickCount ?? 0} ticks`);
    } catch (err) {
      push(err instanceof Error ? err.message : 'What-if failed');
    } finally {
      setWhatIfBusy(false);
    }
  }

  const acousticPct = sum > 0 ? (acoustic / sum) * 100 : 0;
  const contextualPct = sum > 0 ? (contextual / sum) * 100 : 0;
  const readOnly = !canWrite || !isDraft;

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-8 p-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <p className="text-xs text-sv-muted">
            <Link to="/app/settings" className="text-sv-accent hover:underline">
              Settings
            </Link>
            {' / '}
            Risk tuning
          </p>
          <h1 className="mt-1 text-xl font-semibold text-sv-fg">Risk tuning</h1>
          <p className="mt-1 text-sm text-sv-muted">
            Per-tenant fusion weights, level hysteresis, and corroboration. Changes require dual
            control (submit → approve).
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {activeRow ? (
            <Badge tone="success">ACTIVE v{activeRow.version}</Badge>
          ) : null}
          {draftRow ? (
            <Badge tone={STATUS_TONE[draftRow.status] || 'neutral'}>
              {draftRow.status} v{draftRow.version}
            </Badge>
          ) : null}
          <Button className="px-2 py-1 text-xs" variant="ghost" onClick={load} disabled={busy}>
            Refresh
          </Button>
        </div>
      </div>

      {loading ? (
        <p className="text-sm text-sv-muted">Loading…</p>
      ) : (
        <>
          {/* Actions */}
          <section className="flex flex-wrap gap-2">
            {canWrite && !isDraft ? (
              <Button disabled={busy} onClick={createDraft}>
                Create draft
              </Button>
            ) : null}
            {canWrite && isDraft ? (
              <>
                <Button disabled={busy || !configValid || !dirty} onClick={saveDraft}>
                  Save draft
                </Button>
                <Button disabled={busy || !configValid} onClick={submitDraft}>
                  Submit for approval
                </Button>
              </>
            ) : null}
            {!canWrite ? (
              <p className="text-xs text-sv-muted">
                Read-only — TENANT_ADMIN can create drafts; POLICY_APPROVER can approve.
              </p>
            ) : null}
          </section>

          {/* Guardrails */}
          {hints.length > 0 ? (
            <div
              role="alert"
              className="rounded border border-risk-watch/40 bg-risk-watch/10 px-3 py-2 text-xs text-risk-watch"
            >
              <p className="font-medium">Guardrail hints</p>
              <ul className="mt-1 list-inside list-disc space-y-0.5">
                {hints.map((h) => (
                  <li key={h}>{h}</li>
                ))}
              </ul>
              {isDraft ? (
                <p className="mt-1 text-sv-muted">Submit is disabled until these are resolved.</p>
              ) : null}
            </div>
          ) : editConfig ? (
            <p className="text-xs text-risk-clear">All client guardrails pass.</p>
          ) : null}

          {/* Weights */}
          <section className="space-y-4 rounded border border-sv-border bg-sv-elevated/40 p-4">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <h2 className="text-sm font-semibold uppercase tracking-wide text-sv-muted">
                Family weights
              </h2>
              <Toggle
                id="narrowband-toggle"
                checked={narrowband}
                onChange={setNarrowband}
                label={narrowband ? 'Narrowband' : 'Wideband'}
              />
            </div>

            <div className="flex flex-wrap items-center gap-3 text-sm">
              <span className={sumOk ? 'text-risk-clear' : 'text-risk-critical'}>
                sum = {sum.toFixed(2)}
              </span>
              <span className="text-xs text-sv-muted">
                acoustic {acoustic.toFixed(2)} · contextual {contextual.toFixed(2)}
              </span>
            </div>

            <div
              className="flex h-3 w-full overflow-hidden rounded border border-sv-border"
              title="Acoustic (voice+channel+prosody) vs contextual"
            >
              <div
                className="bg-sv-accent/80 transition-all"
                style={{ width: `${Math.max(0, Math.min(100, acousticPct))}%` }}
              />
              <div
                className="bg-sv-muted/40 transition-all"
                style={{ width: `${Math.max(0, Math.min(100, contextualPct))}%` }}
              />
            </div>
            <div className="flex gap-4 text-[10px] uppercase tracking-wide text-sv-muted">
              <span>Acoustic</span>
              <span>Contextual</span>
            </div>

            <div className="grid gap-4 sm:grid-cols-2">
              {FAMILY_KEYS.map((family) => {
                const v = num(weights[family]);
                return (
                  <label key={family} className="flex flex-col gap-1">
                    <div className="flex justify-between text-xs">
                      <span className="font-medium capitalize text-sv-fg">{family}</span>
                      <span className="font-mono text-sv-muted">{v.toFixed(2)}</span>
                    </div>
                    <input
                      type="range"
                      min={0}
                      max={0.6}
                      step={0.01}
                      value={v}
                      disabled={readOnly}
                      onChange={(e) => setWeight(family, Number(e.target.value))}
                      className="w-full accent-[var(--sv-accent,#3b82f6)]"
                    />
                  </label>
                );
              })}
            </div>
          </section>

          {/* Thresholds / lambdas / corroboration / floors */}
          <section className="space-y-4 rounded border border-sv-border bg-sv-elevated/40 p-4">
            <h2 className="text-sm font-semibold uppercase tracking-wide text-sv-muted">
              Thresholds &amp; smoothing
            </h2>
            <div className="grid gap-3 sm:grid-cols-3">
              {FAMILY_KEYS.map((family) => (
                <Input
                  key={family}
                  label={`${family} threshold`}
                  type="number"
                  min={0}
                  max={1}
                  step={0.01}
                  disabled={readOnly}
                  value={num(editConfig?.familyThresholds?.[family])}
                  onChange={(e) =>
                    patchConfig((c) => {
                      if (!c.familyThresholds) c.familyThresholds = {};
                      c.familyThresholds[family] = Number(e.target.value);
                    })
                  }
                />
              ))}
            </div>
            <div className="grid gap-3 sm:grid-cols-3">
              <Input
                label="λ up"
                type="number"
                min={0.01}
                max={1}
                step={0.01}
                disabled={readOnly}
                value={num(editConfig?.smoothing?.lambdaUp)}
                onChange={(e) =>
                  patchConfig((c) => {
                    if (!c.smoothing) c.smoothing = {};
                    c.smoothing.lambdaUp = Number(e.target.value);
                  })
                }
              />
              <Input
                label="λ down"
                type="number"
                min={0.01}
                max={1}
                step={0.01}
                disabled={readOnly}
                value={num(editConfig?.smoothing?.lambdaDown)}
                onChange={(e) =>
                  patchConfig((c) => {
                    if (!c.smoothing) c.smoothing = {};
                    c.smoothing.lambdaDown = Number(e.target.value);
                  })
                }
              />
              <Input
                label="Linguistic staleness τ (ms)"
                type="number"
                min={1}
                step={100}
                disabled={readOnly}
                value={num(editConfig?.smoothing?.linguisticStalenessTauMs)}
                onChange={(e) =>
                  patchConfig((c) => {
                    if (!c.smoothing) c.smoothing = {};
                    c.smoothing.linguisticStalenessTauMs = Number(e.target.value);
                  })
                }
              />
            </div>
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              <Input
                label="Corroboration min (L3)"
                type="number"
                min={1}
                step={1}
                disabled={readOnly}
                value={num(editConfig?.corroboration?.minIndependentFamiliesForL3)}
                onChange={(e) =>
                  patchConfig((c) => {
                    if (!c.corroboration) c.corroboration = {};
                    c.corroboration.minIndependentFamiliesForL3 = Number(e.target.value);
                  })
                }
              />
              <Input
                label="Corroboration min (L4)"
                type="number"
                min={2}
                step={1}
                disabled={readOnly}
                value={num(editConfig?.corroboration?.minForL4)}
                onChange={(e) =>
                  patchConfig((c) => {
                    if (!c.corroboration) c.corroboration = {};
                    c.corroboration.minForL4 = Number(e.target.value);
                  })
                }
              />
              <Input
                label="Acoustic alone max level"
                type="number"
                min={1}
                max={4}
                step={1}
                disabled={readOnly}
                value={num(editConfig?.hardFloors?.acousticAloneMaxLevel)}
                onChange={(e) =>
                  patchConfig((c) => {
                    if (!c.hardFloors) c.hardFloors = {};
                    c.hardFloors.acousticAloneMaxLevel = Number(e.target.value);
                  })
                }
              />
              <Input
                label="Min speech (ms)"
                type="number"
                min={0}
                step={100}
                disabled={readOnly}
                value={num(editConfig?.insufficientEvidence?.minSpeechMs)}
                onChange={(e) =>
                  patchConfig((c) => {
                    if (!c.insufficientEvidence) c.insufficientEvidence = {};
                    c.insufficientEvidence.minSpeechMs = Number(e.target.value);
                  })
                }
              />
            </div>
          </section>

          {/* Level bands */}
          <section className="space-y-4 rounded border border-sv-border bg-sv-elevated/40 p-4">
            <h2 className="text-sm font-semibold uppercase tracking-wide text-sv-muted">
              Level ladder (enter / exit / dwell)
            </h2>
            <LevelLadder levels={editConfig?.levels} />
            <div className="grid gap-4 sm:grid-cols-2">
              {LEVEL_KEYS.map((lk) => (
                <div key={lk} className="rounded border border-sv-border bg-sv-bg/30 p-3">
                  <p className="mb-2 text-xs font-semibold text-sv-fg">{lk}</p>
                  <div className="grid grid-cols-3 gap-2">
                    <Input
                      label="Enter"
                      type="number"
                      min={0}
                      max={1}
                      step={0.01}
                      disabled={readOnly}
                      value={num(editConfig?.levels?.[lk]?.enter)}
                      onChange={(e) =>
                        patchConfig((c) => {
                          if (!c.levels) c.levels = {};
                          if (!c.levels[lk]) c.levels[lk] = {};
                          c.levels[lk].enter = Number(e.target.value);
                        })
                      }
                    />
                    <Input
                      label="Exit"
                      type="number"
                      min={0}
                      max={1}
                      step={0.01}
                      disabled={readOnly}
                      value={num(editConfig?.levels?.[lk]?.exit)}
                      onChange={(e) =>
                        patchConfig((c) => {
                          if (!c.levels) c.levels = {};
                          if (!c.levels[lk]) c.levels[lk] = {};
                          c.levels[lk].exit = Number(e.target.value);
                        })
                      }
                    />
                    <Input
                      label="Dwell ms"
                      type="number"
                      min={0}
                      step={100}
                      disabled={readOnly}
                      value={num(editConfig?.levels?.[lk]?.minDwellMs)}
                      onChange={(e) =>
                        patchConfig((c) => {
                          if (!c.levels) c.levels = {};
                          if (!c.levels[lk]) c.levels[lk] = {};
                          c.levels[lk].minDwellMs = Number(e.target.value);
                        })
                      }
                    />
                  </div>
                </div>
              ))}
            </div>
          </section>

          {/* Diff */}
          <section className="space-y-3 rounded border border-sv-border bg-sv-elevated/40 p-4">
            <h2 className="text-sm font-semibold uppercase tracking-wide text-sv-muted">
              Diff vs ACTIVE
            </h2>
            {diffRows.length === 0 ? (
              <p className="text-sm text-sv-muted">No differences from the active config.</p>
            ) : (
              <Table
                columns={[
                  { key: 'key', header: 'Key' },
                  { key: 'from', header: 'ACTIVE' },
                  { key: 'to', header: 'Editor' },
                ]}
                rows={diffRows}
                rowKey={(r) => r.key}
              />
            )}
          </section>

          {/* History */}
          <section className="space-y-3 rounded border border-sv-border bg-sv-elevated/40 p-4">
            <h2 className="text-sm font-semibold uppercase tracking-wide text-sv-muted">
              Version history
            </h2>
            {history.length === 0 ? (
              <p className="text-sm text-sv-muted">No versions yet.</p>
            ) : (
              <ul className="space-y-3">
                {history.map((h) => (
                  <li
                    key={h.id}
                    className="flex flex-col gap-2 rounded border border-sv-border bg-sv-bg/30 px-3 py-2 sm:flex-row sm:items-center sm:justify-between"
                  >
                    <div className="flex flex-wrap items-center gap-2 text-sm">
                      <Badge tone={STATUS_TONE[h.status] || 'neutral'}>{h.status}</Badge>
                      <span className="font-medium text-sv-fg">v{h.version}</span>
                      <span className="text-xs text-sv-muted">{formatDate(h.updatedAt || h.createdAt)}</span>
                      {h.rejectComment ? (
                        <span className="text-xs text-risk-critical">“{h.rejectComment}”</span>
                      ) : null}
                    </div>
                    <div className="flex flex-wrap items-center gap-2">
                      <Button
                        className="px-2 py-1 text-xs"
                        variant="ghost"
                        onClick={() => loadHistoryItem(h)}
                      >
                        Open
                      </Button>
                      {canApprove && h.status === 'PENDING_APPROVAL' ? (
                        <>
                          <Button
                            className="px-2 py-1 text-xs"
                            disabled={busy}
                            onClick={() => approveConfig(h.id)}
                          >
                            Approve
                          </Button>
                          <Input
                            className="min-w-[10rem]"
                            placeholder="Reject reason"
                            value={rejectComments[h.id] || ''}
                            onChange={(e) =>
                              setRejectComments((prev) => ({
                                ...prev,
                                [h.id]: e.target.value,
                              }))
                            }
                          />
                          <Button
                            className="px-2 py-1 text-xs"
                            variant="ghost"
                            disabled={busy}
                            onClick={() => rejectConfig(h.id)}
                          >
                            Reject
                          </Button>
                        </>
                      ) : null}
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </section>

          {/* What-if */}
          <section className="space-y-3 rounded border border-sv-border bg-sv-elevated/40 p-4">
            <h2 className="text-sm font-semibold uppercase tracking-wide text-sv-muted">
              What-if replay
            </h2>
            <p className="text-xs text-sv-muted">
              Replays stored session telemetry with the editor config. Requires a live or recent
              session with frames.
            </p>
            <div className="flex flex-wrap items-end gap-3">
              {sessionIds.length > 0 ? (
                <Select
                  label="Live session"
                  value={whatIfSessionId}
                  onChange={(e) => setWhatIfSessionId(e.target.value)}
                  options={[
                    { value: '', label: 'Select…' },
                    ...sessionIds.map((id) => ({
                      value: id,
                      label: id.length > 24 ? `${id.slice(0, 12)}…${id.slice(-6)}` : id,
                    })),
                  ]}
                />
              ) : null}
              <Input
                label="Session ID"
                value={whatIfSessionId}
                onChange={(e) => setWhatIfSessionId(e.target.value)}
                placeholder="uuid…"
                className="min-w-[16rem]"
              />
              <Button
                disabled={whatIfBusy || !editConfig || !configValid}
                onClick={runWhatIf}
              >
                {whatIfBusy ? 'Running…' : 'Run replay'}
              </Button>
            </div>
            {whatIfTicks.length > 0 ? (
              <Table
                columns={[
                  { key: 'seq', header: 'Seq' },
                  {
                    key: 'score',
                    header: 'Score',
                    render: (r) => (r.score != null ? Number(r.score).toFixed(3) : '—'),
                  },
                  { key: 'level', header: 'Level' },
                  {
                    key: 'instantaneous',
                    header: 'Instant',
                    render: (r) =>
                      r.instantaneous != null ? Number(r.instantaneous).toFixed(3) : '—',
                  },
                  {
                    key: 'originalLevel',
                    header: 'Original L',
                    render: (r) => r.originalLevel ?? '—',
                  },
                ]}
                rows={whatIfTicks}
                rowKey={(r) => String(r.seq)}
              />
            ) : null}
          </section>
        </>
      )}
    </div>
  );
}

/**
 * Horizontal hysteresis bands for L1–L4.
 * @param {{ levels?: Record<string, { enter?: number, exit?: number }> }} props
 */
function LevelLadder({ levels }) {
  if (!levels) {
    return <p className="text-sm text-sv-muted">No levels loaded.</p>;
  }
  return (
    <div className="space-y-2">
      {LEVEL_KEYS.map((lk) => {
        const enter = num(levels[lk]?.enter);
        const exit = num(levels[lk]?.exit);
        const left = Math.min(exit, enter) * 100;
        const width = Math.max(0, Math.abs(enter - exit) * 100);
        return (
          <div key={lk} className="flex items-center gap-3">
            <span className="w-8 shrink-0 text-xs font-medium text-sv-fg">{lk}</span>
            <div className="relative h-6 flex-1 rounded border border-sv-border bg-sv-bg/50">
              <div
                className="absolute inset-y-0 bg-sv-accent/25"
                style={{ left: `${left}%`, width: `${width}%` }}
                title={`exit ${exit.toFixed(2)} → enter ${enter.toFixed(2)}`}
              />
              <div
                className="absolute top-0 h-full w-0.5 bg-risk-watch"
                style={{ left: `${exit * 100}%` }}
                title={`exit ${exit.toFixed(2)}`}
              />
              <div
                className="absolute top-0 h-full w-0.5 bg-sv-accent"
                style={{ left: `${enter * 100}%` }}
                title={`enter ${enter.toFixed(2)}`}
              />
            </div>
            <span className="w-28 shrink-0 text-[10px] text-sv-muted">
              exit {exit.toFixed(2)} / enter {enter.toFixed(2)}
            </span>
          </div>
        );
      })}
    </div>
  );
}
