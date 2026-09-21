import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, Navigate } from 'react-router-dom';
import { useAuth } from '@/context/AuthContext.jsx';
import { apiJson } from '@/services/api.js';
import { Badge, Button, Input, Select, Table } from '@/ui';
import { useToast } from '@/ui/Toast.jsx';

const LEVELS = ['L1', 'L2', 'L3', 'L4'];
const LEVEL_LABEL = {
  L1: 'L1 · Advisory',
  L2: 'L2 · Soft nudge',
  L3: 'L3 · Step-up',
  L4: 'L4 · Critical',
};
const LEVEL_HINT = {
  L1: 'Log / light guidance',
  L2: 'Operator nudge & verification',
  L3: 'Step-up controls',
  L4: 'Hold / escalate — no override without supervisor',
};

const STATUS_TONE = {
  ACTIVE: 'success',
  DRAFT: 'accent',
  PENDING_APPROVAL: 'warn',
  SUPERSEDED: 'neutral',
  REJECTED: 'danger',
};

const TRIGGERS = [
  { value: 'ON_ENTER', label: 'On enter level' },
  { value: 'WHILE_ACTIVE_EVERY_N_SEC', label: 'While active (every N sec)' },
  { value: 'ON_EXIT', label: 'On exit level' },
];

const TABS = [
  { id: 'editor', label: 'Ladder' },
  { id: 'preview', label: 'Preview' },
  { id: 'history', label: 'History' },
  { id: 'integrations', label: 'Integrations' },
];

function emptyStep(action = 'LOG_ONLY') {
  return {
    action,
    params: {},
    trigger: 'ON_ENTER',
    delayMs: 0,
    requiresAck: false,
    autoExecute: true,
    operatorConfirm: false,
  };
}

function defaultPlan() {
  const levels = {};
  for (const k of LEVELS) {
    levels[k] = {
      operatorOverridePermitted: k !== 'L4',
      overrideRequiresSupervisor: k === 'L3' || k === 'L4',
      steps: [emptyStep(k === 'L1' ? 'LOG_ONLY' : 'OPERATOR_ADVISORY')],
    };
  }
  return { levels };
}

function clone(v) {
  return JSON.parse(JSON.stringify(v ?? null));
}

/**
 * F9 — Response plan ladder editor + dual-control + integrations.
 */
export function ResponsePlansPage() {
  const { me, hasPermission } = useAuth();
  const { push } = useToast();
  const canWrite = hasPermission('response:write');
  const canSubmit = hasPermission('response:submit');
  const canApprove = hasPermission('response:approve') || me?.role === 'POLICY_APPROVER';

  const [tab, setTab] = useState('editor');
  const [catalogue, setCatalogue] = useState(/** @type {any[]} */ ([]));
  const [active, setActive] = useState(/** @type {any} */ (null));
  const [draft, setDraft] = useState(/** @type {any} */ (null));
  const [plan, setPlan] = useState(/** @type {any} */ (null));
  const [history, setHistory] = useState(/** @type {any[]} */ ([]));
  const [validation, setValidation] = useState(/** @type {any} */ (null));
  const [warnings, setWarnings] = useState(/** @type {string[]} */ ([]));
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [selectedLevel, setSelectedLevel] = useState('L3');
  const [editStep, setEditStep] = useState(/** @type {{ level: string, index: number } | null} */ (null));
  const [addActionKey, setAddActionKey] = useState('OPERATOR_ADVISORY');
  const [preview, setPreview] = useState(/** @type {any} */ (null));
  const [previewFacts, setPreviewFacts] = useState('{"ask.amountInr": 1500000}');
  const [integrations, setIntegrations] = useState(/** @type {any[]} */ ([]));
  const [rejectComment, setRejectComment] = useState('');
  const [loadError, setLoadError] = useState(/** @type {string | null} */ (null));

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError(null);
    try {
      const [cat, hist, integ] = await Promise.all([
        apiJson('/api/v2/response/action-catalogue'),
        apiJson('/api/v2/response/plans/history'),
        apiJson('/api/v2/response/integrations', { skipErrorToast: true }).catch(() => []),
      ]);
      let act = null;
      try {
        act = await apiJson('/api/v2/response/plans/active', { skipErrorToast: true });
      } catch {
        act = null;
      }
      setCatalogue(Array.isArray(cat) ? cat : []);
      setActive(act);
      setHistory(Array.isArray(hist) ? hist : []);
      setIntegrations(Array.isArray(integ) ? integ : []);

      const openDraft = (Array.isArray(hist) ? hist : []).find(
        (h) => h.status === 'DRAFT' || h.status === 'PENDING_APPROVAL',
      );
      if (openDraft) {
        setDraft(openDraft);
        setPlan(clone(openDraft.plan) || defaultPlan());
        setValidation(openDraft.validation || null);
        setWarnings(openDraft.degradationWarnings || []);
      } else {
        setDraft(null);
        setPlan(clone(act?.plan) || defaultPlan());
        setValidation(act?.validation || null);
        setWarnings(act?.degradationWarnings || []);
      }
      if (Array.isArray(cat) && cat[0]?.key) {
        setAddActionKey(cat[0].key);
      }
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Failed to load response plans';
      setLoadError(msg);
      push(msg);
    } finally {
      setLoading(false);
    }
  }, [push]);

  useEffect(() => {
    if (hasPermission('response:read')) load();
  }, [hasPermission, load]);

  const catalogueByKey = useMemo(() => {
    /** @type {Record<string, any>} */
    const m = {};
    for (const a of catalogue) m[a.key] = a;
    return m;
  }, [catalogue]);

  const editing = draft?.status === 'DRAFT' && canWrite;
  const validationOk = validation?.ok !== false
    && LEVELS.every((l) => validation?.levels?.[l]?.ok !== false);

  async function refreshValidation(nextPlan) {
    try {
      const res = await apiJson('/api/v2/response/plans/validate', {
        method: 'POST',
        body: JSON.stringify({ plan: nextPlan }),
        skipErrorToast: true,
      });
      setValidation(res);
      setWarnings(res.degradationWarnings || []);
    } catch {
      /* live validate is best-effort */
    }
  }

  function updatePlan(mutator) {
    setPlan((prev) => {
      const next = clone(prev) || defaultPlan();
      if (!next.levels) next.levels = defaultPlan().levels;
      mutator(next);
      refreshValidation(next);
      return next;
    });
  }

  function ensureLevel(p, level) {
    if (!p.levels[level]) {
      p.levels[level] = {
        operatorOverridePermitted: level !== 'L4',
        overrideRequiresSupervisor: level === 'L3' || level === 'L4',
        steps: [],
      };
    }
  }

  function addStep(level, actionKey) {
    let newIndex = 0;
    updatePlan((p) => {
      ensureLevel(p, level);
      p.levels[level].steps.push(emptyStep(actionKey || 'LOG_ONLY'));
      newIndex = p.levels[level].steps.length - 1;
    });
    setEditStep({ level, index: newIndex });
  }

  function moveStep(level, index, dir) {
    updatePlan((p) => {
      const steps = p.levels[level].steps;
      const j = index + dir;
      if (j < 0 || j >= steps.length) return;
      const tmp = steps[index];
      steps[index] = steps[j];
      steps[j] = tmp;
    });
    if (editStep?.level === level) {
      const j = editStep.index + dir;
      if (j >= 0) setEditStep({ level, index: j });
    }
  }

  function removeStep(level, index) {
    updatePlan((p) => {
      p.levels[level].steps.splice(index, 1);
    });
    setEditStep(null);
  }

  async function createDraft() {
    setBusy(true);
    try {
      const row = await apiJson('/api/v2/response/plans/draft', {
        method: 'POST',
        body: JSON.stringify({ plan: plan || active?.plan || undefined }),
      });
      setDraft(row);
      setPlan(clone(row.plan) || defaultPlan());
      setValidation(row.validation || null);
      setWarnings(row.degradationWarnings || []);
      push(`Draft v${row.version} created`);
      await load();
    } catch (err) {
      push(err instanceof Error ? err.message : 'Draft failed');
    } finally {
      setBusy(false);
    }
  }

  async function saveDraft() {
    if (!draft || draft.status !== 'DRAFT') return;
    setBusy(true);
    try {
      const row = await apiJson(`/api/v2/response/plans/${draft.id}`, {
        method: 'PUT',
        body: JSON.stringify({ plan }),
      });
      setDraft(row);
      setValidation(row.validation || null);
      setWarnings(row.degradationWarnings || []);
      push('Draft saved');
    } catch (err) {
      push(err instanceof Error ? err.message : 'Save failed');
    } finally {
      setBusy(false);
    }
  }

  async function submitDraft() {
    if (!draft) return;
    if (!validationOk) {
      push('Fix safety-floor failures before submit');
      return;
    }
    setBusy(true);
    try {
      const row = await apiJson(`/api/v2/response/plans/${draft.id}/submit`, { method: 'POST' });
      setDraft(row);
      push(`Submitted v${row.version} for approval`);
      await load();
    } catch (err) {
      push(err instanceof Error ? err.message : 'Submit failed');
    } finally {
      setBusy(false);
    }
  }

  async function approveDraft() {
    if (!draft) return;
    setBusy(true);
    try {
      await apiJson(`/api/v2/response/plans/${draft.id}/approve`, { method: 'POST' });
      push('Plan approved and activated');
      setEditStep(null);
      await load();
    } catch (err) {
      push(err instanceof Error ? err.message : 'Approve failed');
    } finally {
      setBusy(false);
    }
  }

  async function rejectDraft() {
    if (!draft || !rejectComment.trim()) {
      push('Rejection comment required');
      return;
    }
    setBusy(true);
    try {
      await apiJson(`/api/v2/response/plans/${draft.id}/reject`, {
        method: 'POST',
        body: JSON.stringify({ comment: rejectComment.trim() }),
      });
      push('Plan rejected');
      setRejectComment('');
      setEditStep(null);
      await load();
    } catch (err) {
      push(err instanceof Error ? err.message : 'Reject failed');
    } finally {
      setBusy(false);
    }
  }

  async function runPreview() {
    let facts = {};
    try {
      facts = JSON.parse(previewFacts || '{}');
    } catch {
      push('Sample facts must be valid JSON');
      return;
    }
    setBusy(true);
    try {
      const res = await apiJson('/api/v2/response/plans/preview', {
        method: 'POST',
        body: JSON.stringify({ plan, level: selectedLevel, facts }),
      });
      setPreview(res);
    } catch (err) {
      push(err instanceof Error ? err.message : 'Preview failed');
    } finally {
      setBusy(false);
    }
  }

  async function saveIntegration(kind, body) {
    setBusy(true);
    try {
      await apiJson(`/api/v2/response/integrations/${kind}`, {
        method: 'PUT',
        body: JSON.stringify(body),
      });
      push(`${kind} saved`);
      const integ = await apiJson('/api/v2/response/integrations');
      setIntegrations(Array.isArray(integ) ? integ : []);
    } catch (err) {
      push(err instanceof Error ? err.message : 'Integration save failed');
    } finally {
      setBusy(false);
    }
  }

  async function testIntegration(kind) {
    setBusy(true);
    try {
      const res = await apiJson(`/api/v2/response/integrations/${kind}/test`, {
        method: 'POST',
        skipErrorToast: true,
      });
      push(res.lastTestOk ? `${kind} ping OK` : `${kind} ping failed: ${res.lastTestDetail || 'unknown'}`);
      const integ = await apiJson('/api/v2/response/integrations');
      setIntegrations(Array.isArray(integ) ? integ : []);
    } catch (err) {
      push(err instanceof Error ? err.message : 'Test failed');
    } finally {
      setBusy(false);
    }
  }

  if (!hasPermission('response:read')) {
    return <Navigate to="/app" replace />;
  }

  const stepDrawer = editStep
    ? plan?.levels?.[editStep.level]?.steps?.[editStep.index]
    : null;

  const floorFailures = LEVELS.flatMap((level) => {
    const st = validation?.levels?.[level];
    if (st?.ok === false) {
      return (st.reasons || [`${level} failed safety floors`]).map((r) => `${level}: ${r}`);
    }
    return [];
  });

  return (
    <div className="mx-auto flex max-w-6xl flex-col gap-6 p-6">
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-xl font-semibold text-sv-fg">Response plans</h1>
          <p className="mt-1 max-w-2xl text-sm text-sv-muted">
            Map each risk level to ordered actuation steps. Live calls use the ACTIVE plan
            snapshotted at session start — mid-call edits do not change in-flight behaviour.
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {active ? <Badge tone="success">ACTIVE v{active.version}</Badge> : (
            <Badge tone="warn">No ACTIVE plan</Badge>
          )}
          {draft ? (
            <Badge tone={STATUS_TONE[draft.status] || 'neutral'}>
              {draft.status} v{draft.version}
            </Badge>
          ) : null}
          <Button className="px-2 py-1 text-xs" variant="ghost" disabled={busy || loading} onClick={load}>
            Refresh
          </Button>
        </div>
      </header>

      {loading ? (
        <p className="text-sm text-sv-muted">Loading response plans…</p>
      ) : loadError ? (
        <div role="alert" className="rounded border border-risk-critical/40 bg-risk-critical/10 px-3 py-2 text-sm text-risk-critical">
          {loadError}
          <div className="mt-2">
            <Button onClick={load}>Retry</Button>
          </div>
        </div>
      ) : (
        <>
          <nav className="flex flex-wrap gap-1 border-b border-sv-border pb-2" aria-label="Response plan sections">
            {TABS.map((t) => (
              <button
                key={t.id}
                type="button"
                className={`rounded-md px-3 py-1.5 text-sm transition ${
                  tab === t.id
                    ? 'bg-sv-accent/15 font-medium text-sv-accent'
                    : 'text-sv-muted hover:bg-sv-elevated hover:text-sv-fg'
                }`}
                onClick={() => setTab(t.id)}
              >
                {t.label}
              </button>
            ))}
          </nav>

          {tab === 'editor' && (
            <div className="flex flex-col gap-6">
              <section className="flex flex-wrap gap-2">
                {canWrite && !draft && (
                  <Button disabled={busy} onClick={createDraft}>
                    Create draft from ACTIVE
                  </Button>
                )}
                {editing && (
                  <>
                    <Button disabled={busy} onClick={saveDraft}>Save draft</Button>
                    {canSubmit && (
                      <Button disabled={busy || !validationOk} onClick={submitDraft}>
                        Submit for approval
                      </Button>
                    )}
                  </>
                )}
                {draft?.status === 'PENDING_APPROVAL' && canApprove && (
                  <>
                    <Button variant="primary" disabled={busy} onClick={approveDraft}>
                      Approve
                    </Button>
                    <Input
                      className="w-56"
                      placeholder="Reject comment"
                      value={rejectComment}
                      onChange={(e) => setRejectComment(e.target.value)}
                    />
                    <Button variant="danger" disabled={busy} onClick={rejectDraft}>
                      Reject
                    </Button>
                  </>
                )}
                {!canWrite && (
                  <p className="text-xs text-sv-muted">
                    Read-only — TENANT_ADMIN drafts; POLICY_APPROVER approves.
                  </p>
                )}
                {canWrite && draft && draft.status !== 'DRAFT' && draft.status !== 'PENDING_APPROVAL' && (
                  <p className="text-xs text-sv-muted">
                    Open a DRAFT from History, or create a new draft to edit.
                  </p>
                )}
              </section>

              {floorFailures.length > 0 ? (
                <div
                  role="alert"
                  className="rounded border border-risk-watch/40 bg-risk-watch/10 px-3 py-2 text-xs text-risk-watch"
                >
                  <p className="font-medium">Safety floor failures</p>
                  <ul className="mt-1 list-inside list-disc space-y-0.5">
                    {floorFailures.map((f) => (
                      <li key={f}>{f}</li>
                    ))}
                  </ul>
                </div>
              ) : (
                <p className="text-xs text-risk-clear">Safety floors pass for all levels.</p>
              )}

              {warnings.length > 0 && (
                <div className="rounded border border-amber-500/30 bg-amber-500/10 px-3 py-2 text-xs text-amber-200">
                  <p className="font-medium">Integration warnings</p>
                  <ul className="mt-1 list-inside list-disc">
                    {warnings.map((w) => (
                      <li key={w}>{w}</li>
                    ))}
                  </ul>
                </div>
              )}

              <div className="grid gap-4 lg:grid-cols-[1fr_300px]">
                <div className="space-y-4">
                  {LEVELS.map((level) => {
                    const lp = plan?.levels?.[level] || { steps: [] };
                    const status = validation?.levels?.[level];
                    const ok = status?.ok !== false;
                    return (
                      <section
                        key={level}
                        className="rounded-lg border border-sv-border bg-sv-panel/50 p-4"
                        onDragOver={(e) => {
                          if (editing) e.preventDefault();
                        }}
                        onDrop={(e) => {
                          e.preventDefault();
                          if (!editing) return;
                          const key = e.dataTransfer.getData('text/action-key');
                          if (key) addStep(level, key);
                        }}
                      >
                        <div className="mb-3 flex flex-wrap items-start justify-between gap-2">
                          <div>
                            <div className="flex items-center gap-2">
                              <h2 className="text-sm font-semibold text-sv-fg">{LEVEL_LABEL[level]}</h2>
                              <Badge tone={ok ? 'success' : 'danger'}>{ok ? 'ok' : 'fail'}</Badge>
                            </div>
                            <p className="mt-0.5 text-xs text-sv-muted">{LEVEL_HINT[level]}</p>
                          </div>
                          {editing && (
                            <div className="flex flex-wrap items-center gap-3 text-[11px] text-sv-muted">
                              <label className="flex items-center gap-1.5">
                                <input
                                  type="checkbox"
                                  checked={!!lp.operatorOverridePermitted}
                                  onChange={(e) => updatePlan((p) => {
                                    ensureLevel(p, level);
                                    p.levels[level].operatorOverridePermitted = e.target.checked;
                                  })}
                                />
                                Override OK
                              </label>
                              <label className="flex items-center gap-1.5">
                                <input
                                  type="checkbox"
                                  checked={!!lp.overrideRequiresSupervisor}
                                  onChange={(e) => updatePlan((p) => {
                                    ensureLevel(p, level);
                                    p.levels[level].overrideRequiresSupervisor = e.target.checked;
                                  })}
                                />
                                Needs supervisor
                              </label>
                            </div>
                          )}
                        </div>

                        <ol className="space-y-2">
                          {(lp.steps || []).length === 0 ? (
                            <li className="rounded border border-dashed border-sv-border px-3 py-4 text-center text-xs text-sv-muted">
                              No steps — add an action below{editing ? ' or drag from the catalogue' : ''}.
                            </li>
                          ) : (
                            (lp.steps || []).map((step, index) => {
                              const selected =
                                editStep?.level === level && editStep?.index === index;
                              const label = catalogueByKey[step.action]?.label || step.action;
                              return (
                                <li
                                  key={`${level}-${index}-${step.action}`}
                                  className={`rounded-md border bg-sv-bg/80 p-3 ${
                                    selected
                                      ? 'border-sv-accent ring-1 ring-sv-accent/40'
                                      : 'border-sv-border'
                                  }`}
                                >
                                  <div className="flex items-start justify-between gap-2">
                                    <button
                                      type="button"
                                      className="min-w-0 flex-1 text-left"
                                      onClick={() => setEditStep({ level, index })}
                                    >
                                      <div className="flex items-center gap-2">
                                        <span className="font-mono text-[10px] text-sv-muted">
                                          {index + 1}
                                        </span>
                                        <span className="text-sm font-medium text-sv-fg">{label}</span>
                                      </div>
                                      <p className="mt-1 text-[11px] text-sv-muted">
                                        {step.trigger}
                                        {' · '}
                                        {step.autoExecute ? 'auto' : 'confirm'}
                                        {' · '}
                                        {Number(step.delayMs) || 0} ms
                                        {step.params?.text
                                          ? ` · “${String(step.params.text).slice(0, 48)}${String(step.params.text).length > 48 ? '…' : ''}”`
                                          : ''}
                                      </p>
                                    </button>
                                    {editing && (
                                      <div className="flex shrink-0 gap-1">
                                        <Button
                                          className="!px-2 !py-0.5 text-xs"
                                          variant="ghost"
                                          onClick={() => moveStep(level, index, -1)}
                                          aria-label="Move up"
                                        >
                                          ↑
                                        </Button>
                                        <Button
                                          className="!px-2 !py-0.5 text-xs"
                                          variant="ghost"
                                          onClick={() => moveStep(level, index, 1)}
                                          aria-label="Move down"
                                        >
                                          ↓
                                        </Button>
                                        <Button
                                          className="!px-2 !py-0.5 text-xs"
                                          variant="ghost"
                                          onClick={() => removeStep(level, index)}
                                          aria-label="Remove"
                                        >
                                          ✕
                                        </Button>
                                      </div>
                                    )}
                                  </div>
                                </li>
                              );
                            })
                          )}
                        </ol>

                        {editing && (
                          <div className="mt-3 flex flex-wrap items-end gap-2 border-t border-sv-border/60 pt-3">
                            <Select
                              className="min-w-[200px] flex-1"
                              label="Add action"
                              value={addActionKey}
                              onChange={(e) => setAddActionKey(e.target.value)}
                              options={catalogue.map((a) => ({
                                value: a.key,
                                label: a.label || a.key,
                              }))}
                            />
                            <Button
                              disabled={busy}
                              onClick={() => addStep(level, addActionKey)}
                            >
                              Add to {level}
                            </Button>
                          </div>
                        )}
                      </section>
                    );
                  })}
                </div>

                <aside className="space-y-4 lg:sticky lg:top-4 lg:self-start">
                  <div className="rounded-lg border border-sv-border bg-sv-panel/40 p-4">
                    <h3 className="text-sm font-semibold text-sv-fg">Action catalogue</h3>
                    <p className="mt-1 text-[11px] text-sv-muted">
                      {editing
                        ? 'Click Add on a level, or drag an action onto a level card.'
                        : 'Create a draft to edit the ladder.'}
                    </p>
                    <ul className="mt-3 max-h-64 space-y-1 overflow-y-auto">
                      {catalogue.map((a) => (
                        <li
                          key={a.key}
                          draggable={editing}
                          onDragStart={(e) => {
                            e.dataTransfer.setData('text/action-key', a.key);
                            e.dataTransfer.effectAllowed = 'copy';
                          }}
                          className={`rounded border border-sv-border/70 bg-sv-bg/70 px-2 py-1.5 text-xs ${
                            editing ? 'cursor-grab active:cursor-grabbing' : ''
                          }`}
                          title={a.description}
                        >
                          <div className="font-medium text-sv-fg">{a.label}</div>
                          <div className="font-mono text-[10px] text-sv-muted">{a.key}</div>
                        </li>
                      ))}
                    </ul>
                  </div>

                  {stepDrawer && editStep ? (
                    <StepEditor
                      step={stepDrawer}
                      level={editStep.level}
                      index={editStep.index}
                      editing={editing}
                      catalogue={catalogue}
                      catalogueByKey={catalogueByKey}
                      onChange={(mutator) => updatePlan((p) => {
                        const s = p.levels[editStep.level].steps[editStep.index];
                        mutator(s);
                      })}
                      onClose={() => setEditStep(null)}
                    />
                  ) : (
                    <div className="rounded-lg border border-dashed border-sv-border p-4 text-xs text-sv-muted">
                      Select a step to edit triggers, delays, and action parameters.
                    </div>
                  )}
                </aside>
              </div>
            </div>
          )}

          {tab === 'preview' && (
            <div className="max-w-3xl space-y-4">
              <p className="text-sm text-sv-muted">
                Dry-run the current editor plan against sample facts (no call actuation).
              </p>
              <div className="flex flex-wrap items-end gap-3">
                <Select
                  className="w-48"
                  label="Level"
                  value={selectedLevel}
                  onChange={(e) => setSelectedLevel(e.target.value)}
                  options={LEVELS.map((l) => ({ value: l, label: LEVEL_LABEL[l] }))}
                />
                <label className="min-w-[280px] flex-1 text-sm font-medium text-sv-fg">
                  Sample facts (JSON)
                  <Input
                    className="mt-1.5"
                    value={previewFacts}
                    onChange={(e) => setPreviewFacts(e.target.value)}
                  />
                </label>
                <Button disabled={busy} onClick={runPreview}>
                  Simulate level entry
                </Button>
              </div>
              {preview ? (
                <ol className="space-y-2">
                  {(preview.timeline || []).map((row) => (
                    <li
                      key={`${row.stepIndex}-${row.action}-${row.atMs}`}
                      className={`rounded border border-sv-border p-3 text-sm ${
                        row.wouldRun ? 'bg-emerald-500/10' : 'bg-sv-panel/40 opacity-80'
                      }`}
                    >
                      <div className="font-medium text-sv-fg">
                        +{row.atMs} ms · {catalogueByKey[row.action]?.label || row.action}
                      </div>
                      <div className="mt-0.5 text-xs text-sv-muted">
                        {row.wouldRun ? 'would run' : `skipped — ${row.skipReason || 'n/a'}`}
                        {row.operatorConfirm ? ' · needs confirm' : ''}
                      </div>
                    </li>
                  ))}
                  {(preview.timeline || []).length === 0 && (
                    <li className="text-sm text-sv-muted">No timeline rows returned.</li>
                  )}
                </ol>
              ) : (
                <p className="text-sm text-sv-muted">Run a simulation to see the timeline.</p>
              )}
            </div>
          )}

          {tab === 'history' && (
            <div className="overflow-x-auto">
              {history.length === 0 ? (
                <p className="text-sm text-sv-muted">No plan versions yet.</p>
              ) : (
                <Table
                  columns={[
                    { key: 'version', header: 'Ver' },
                    {
                      key: 'status',
                      header: 'Status',
                      render: (r) => (
                        <Badge tone={STATUS_TONE[r.status] || 'neutral'}>{r.status}</Badge>
                      ),
                    },
                    {
                      key: 'contentSha256',
                      header: 'SHA',
                      render: (r) => (
                        <span className="font-mono text-[11px]">
                          {String(r.contentSha256 || '').slice(0, 12)}…
                        </span>
                      ),
                    },
                    { key: 'submittedBy', header: 'Submitted' },
                    { key: 'approvedBy', header: 'Approved' },
                    { key: 'createdAt', header: 'Created' },
                    {
                      key: 'actions',
                      header: '',
                      render: (r) => (
                        <Button
                          className="!px-2 !py-1 text-xs"
                          onClick={() => {
                            setDraft(r);
                            setPlan(clone(r.plan) || defaultPlan());
                            setValidation(r.validation || null);
                            setWarnings(r.degradationWarnings || []);
                            setEditStep(null);
                            setTab('editor');
                          }}
                        >
                          Open
                        </Button>
                      ),
                    },
                  ]}
                  rows={history}
                />
              )}
            </div>
          )}

          {tab === 'integrations' && (
            <IntegrationsPanel
              integrations={integrations}
              canWrite={canWrite}
              busy={busy}
              onSave={saveIntegration}
              onTest={testIntegration}
            />
          )}

          <p className="text-[11px] text-sv-muted">
            Dual-control matches Policies and{' '}
            <Link className="text-sv-accent hover:underline" to="/app/settings/risk-tuning">
              Risk tuning
            </Link>
            . Approver must differ from submitter.
          </p>
        </>
      )}
    </div>
  );
}

function StepEditor({
  step,
  level,
  index,
  editing,
  catalogue,
  catalogueByKey,
  onChange,
  onClose,
}) {
  const def = catalogueByKey[step.action];
  const params = step.params || {};

  return (
    <div className="rounded-lg border border-sv-border bg-sv-panel/40 p-4">
      <div className="mb-3 flex items-center justify-between gap-2">
        <h3 className="text-sm font-semibold text-sv-fg">
          Step {index + 1} · {level}
        </h3>
        <Button className="!px-2 !py-0.5 text-xs" variant="ghost" onClick={onClose}>
          Close
        </Button>
      </div>

      <Select
        label="Action"
        value={step.action}
        disabled={!editing}
        onChange={(e) => onChange((s) => {
          s.action = e.target.value;
          s.params = s.params || {};
        })}
        options={catalogue.map((a) => ({ value: a.key, label: a.label || a.key }))}
      />

      <Select
        className="mt-3"
        label="Trigger"
        value={step.trigger}
        disabled={!editing}
        onChange={(e) => onChange((s) => {
          s.trigger = e.target.value;
        })}
        options={TRIGGERS}
      />

      <label className="mt-3 block text-sm font-medium text-sv-fg">
        Delay (ms)
        <Input
          className="mt-1.5"
          type="number"
          min={0}
          disabled={!editing}
          value={step.delayMs ?? 0}
          onChange={(e) => onChange((s) => {
            s.delayMs = Number(e.target.value) || 0;
          })}
        />
      </label>

      <div className="mt-3 space-y-2 text-xs text-sv-fg">
        <label className="flex items-center gap-2">
          <input
            type="checkbox"
            disabled={!editing}
            checked={!!step.autoExecute}
            onChange={(e) => onChange((s) => {
              s.autoExecute = e.target.checked;
              if (e.target.checked) s.operatorConfirm = false;
            })}
          />
          Auto-execute
        </label>
        <label className="flex items-center gap-2">
          <input
            type="checkbox"
            disabled={!editing}
            checked={!!step.operatorConfirm}
            onChange={(e) => onChange((s) => {
              s.operatorConfirm = e.target.checked;
              if (e.target.checked) s.autoExecute = false;
            })}
          />
          Operator confirm
        </label>
        <label className="flex items-center gap-2">
          <input
            type="checkbox"
            disabled={!editing}
            checked={!!step.requiresAck}
            onChange={(e) => onChange((s) => {
              s.requiresAck = e.target.checked;
            })}
          />
          Requires ack
        </label>
      </div>

      {(step.action === 'OPERATOR_ADVISORY' || def?.paramsSchema?.properties?.text) && (
        <label className="mt-3 block text-sm font-medium text-sv-fg">
          Advisory text
          <Input
            className="mt-1.5"
            disabled={!editing}
            value={params.text || ''}
            onChange={(e) => onChange((s) => {
              s.params = { ...(s.params || {}), text: e.target.value };
            })}
            placeholder="Shown to the operator"
          />
        </label>
      )}

      {step.action === 'WHISPER_WARNING' && (
        <label className="mt-3 block text-sm font-medium text-sv-fg">
          Sound ID
          <Input
            className="mt-1.5"
            disabled={!editing}
            value={params.soundId || ''}
            onChange={(e) => onChange((s) => {
              s.params = { ...(s.params || {}), soundId: e.target.value };
            })}
          />
        </label>
      )}

      {step.trigger === 'WHILE_ACTIVE_EVERY_N_SEC' && (
        <label className="mt-3 block text-sm font-medium text-sv-fg">
          Interval (sec)
          <Input
            className="mt-1.5"
            type="number"
            min={1}
            disabled={!editing}
            value={params.everySec ?? params.intervalSec ?? 30}
            onChange={(e) => onChange((s) => {
              s.params = { ...(s.params || {}), everySec: Number(e.target.value) || 30 };
            })}
          />
        </label>
      )}

      {def?.description && (
        <p className="mt-3 text-[11px] text-sv-muted">{def.description}</p>
      )}
      {def?.needsIntegration && (
        <p className="mt-2 text-[11px] text-amber-200/90">
          Needs integration{def.integrationKind ? `: ${def.integrationKind}` : ''}.
        </p>
      )}
    </div>
  );
}

function IntegrationsPanel({ integrations, canWrite, busy, onSave, onTest }) {
  const kinds = [
    'SMS_NOTIFICATION',
    'SUPERVISOR_NOTIFY',
    'SUPERVISOR_BRIDGE',
    'CORE_BANKING',
    'ITSM',
    'CUSTOM_WEBHOOK',
  ];
  const byKind = Object.fromEntries((integrations || []).map((i) => [i.kind, i]));

  return (
    <div className="grid gap-4 md:grid-cols-2">
      <p className="md:col-span-2 text-sm text-sv-muted">
        Optional sinks for plan actions that need outbound webhooks or a supervisor bridge.
        Missing integrations degrade with warnings — they do not block draft save.
      </p>
      {kinds.map((kind) => {
        const row = byKind[kind] || { kind, enabled: false, config: {}, hasSecrets: false };
        return (
          <IntegrationCard
            key={kind}
            kind={kind}
            row={row}
            canWrite={canWrite}
            busy={busy}
            onSave={onSave}
            onTest={onTest}
          />
        );
      })}
    </div>
  );
}

function IntegrationCard({ kind, row, canWrite, busy, onSave, onTest }) {
  const [enabled, setEnabled] = useState(!!row.enabled);
  const [webhookUrl, setWebhookUrl] = useState(row.config?.webhookUrl || '');
  const [extension, setExtension] = useState(row.config?.extension || '');
  const [hmacSecret, setHmacSecret] = useState('');

  useEffect(() => {
    setEnabled(!!row.enabled);
    setWebhookUrl(row.config?.webhookUrl || '');
    setExtension(row.config?.extension || '');
    setHmacSecret('');
  }, [row]);

  return (
    <div className="rounded-lg border border-sv-border bg-sv-panel/50 p-4">
      <div className="mb-3 flex items-center justify-between gap-2">
        <h3 className="text-sm font-semibold text-sv-fg">{kind.replaceAll('_', ' ')}</h3>
        <Badge
          tone={
            row.lastTestOk === true
              ? 'success'
              : row.lastTestOk === false
                ? 'danger'
                : row.enabled
                  ? 'accent'
                  : 'neutral'
          }
        >
          {row.lastTestOk === true
            ? 'healthy'
            : row.lastTestOk === false
              ? 'fail'
              : row.enabled
                ? 'configured'
                : 'off'}
        </Badge>
      </div>
      <label className="flex items-center gap-2 text-xs text-sv-fg">
        <input
          type="checkbox"
          disabled={!canWrite}
          checked={enabled}
          onChange={(e) => setEnabled(e.target.checked)}
        />
        Enabled
      </label>
      {kind === 'SUPERVISOR_BRIDGE' ? (
        <label className="mt-3 block text-sm font-medium text-sv-fg">
          Extension
          <Input
            className="mt-1.5"
            disabled={!canWrite}
            value={extension}
            onChange={(e) => setExtension(e.target.value)}
          />
        </label>
      ) : (
        <label className="mt-3 block text-sm font-medium text-sv-fg">
          Webhook URL
          <Input
            className="mt-1.5"
            disabled={!canWrite}
            value={webhookUrl}
            onChange={(e) => setWebhookUrl(e.target.value)}
          />
        </label>
      )}
      <label className="mt-3 block text-sm font-medium text-sv-fg">
        HMAC secret {row.hasSecrets ? '(already set)' : ''}
        <Input
          className="mt-1.5"
          type="password"
          disabled={!canWrite}
          value={hmacSecret}
          placeholder={row.hasSecrets ? '••••••••' : ''}
          onChange={(e) => setHmacSecret(e.target.value)}
          autoComplete="new-password"
        />
      </label>
      {row.lastTestDetail && (
        <p className="mt-2 text-[11px] text-sv-muted">{row.lastTestDetail}</p>
      )}
      {canWrite && (
        <div className="mt-3 flex gap-2">
          <Button
            disabled={busy}
            onClick={() => onSave(kind, {
              enabled,
              config: {
                ...(row.config || {}),
                webhookUrl: webhookUrl || undefined,
                extension: extension || undefined,
              },
              secrets: hmacSecret
                ? {
                    hmacSecret,
                    webhookUrl: webhookUrl || undefined,
                    extension: extension || undefined,
                  }
                : undefined,
            })}
          >
            Save
          </Button>
          <Button variant="secondary" disabled={busy || !enabled} onClick={() => onTest(kind)}>
            Test
          </Button>
        </div>
      )}
    </div>
  );
}
