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

const STATUS_TONE = {
  ACTIVE: 'success',
  DRAFT: 'accent',
  PENDING_APPROVAL: 'warn',
  SUPERSEDED: 'neutral',
  REJECTED: 'danger',
};

const TRIGGERS = ['ON_ENTER', 'WHILE_ACTIVE_EVERY_N_SEC', 'ON_EXIT'];

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
  return JSON.parse(JSON.stringify(v));
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
  const [catalogue, setCatalogue] = useState([]);
  const [active, setActive] = useState(null);
  const [draft, setDraft] = useState(null);
  const [plan, setPlan] = useState(null);
  const [history, setHistory] = useState([]);
  const [validation, setValidation] = useState(null);
  const [warnings, setWarnings] = useState([]);
  const [loading, setLoading] = useState(true);
  const [selectedLevel, setSelectedLevel] = useState('L3');
  const [editStep, setEditStep] = useState(null); // { level, index }
  const [preview, setPreview] = useState(null);
  const [previewFacts, setPreviewFacts] = useState('{"ask.amountInr": 1500000}');
  const [integrations, setIntegrations] = useState([]);
  const [rejectComment, setRejectComment] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [cat, act, hist, integ] = await Promise.all([
        apiJson('/api/v2/response/action-catalogue'),
        apiJson('/api/v2/response/plans/active'),
        apiJson('/api/v2/response/plans/history'),
        apiJson('/api/v2/response/integrations').catch(() => []),
      ]);
      setCatalogue(Array.isArray(cat) ? cat : []);
      setActive(act);
      setHistory(Array.isArray(hist) ? hist : []);
      setIntegrations(Array.isArray(integ) ? integ : []);
      const openDraft = (Array.isArray(hist) ? hist : []).find((h) => h.status === 'DRAFT' || h.status === 'PENDING_APPROVAL');
      if (openDraft) {
        setDraft(openDraft);
        setPlan(clone(openDraft.plan));
        setValidation(openDraft.validation || null);
        setWarnings(openDraft.degradationWarnings || []);
      } else {
        setDraft(null);
        setPlan(clone(act.plan || defaultPlan()));
        setValidation(act.validation || null);
        setWarnings(act.degradationWarnings || []);
      }
    } catch (err) {
      push(err.message || 'Failed to load response plans');
    } finally {
      setLoading(false);
    }
  }, [push]);

  useEffect(() => {
    if (hasPermission('response:read')) load();
  }, [hasPermission, load]);

  const catalogueByKey = useMemo(() => {
    const m = {};
    for (const a of catalogue) m[a.key] = a;
    return m;
  }, [catalogue]);

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
      /* ignore live validation noise */
    }
  }

  function updatePlan(mutator) {
    setPlan((prev) => {
      const next = clone(prev || defaultPlan());
      mutator(next);
      refreshValidation(next);
      return next;
    });
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
  }

  function removeStep(level, index) {
    updatePlan((p) => {
      p.levels[level].steps.splice(index, 1);
    });
    setEditStep(null);
  }

  function addStep(level, actionKey) {
    updatePlan((p) => {
      if (!p.levels[level]) {
        p.levels[level] = { operatorOverridePermitted: true, overrideRequiresSupervisor: false, steps: [] };
      }
      p.levels[level].steps.push(emptyStep(actionKey));
    });
  }

  async function createDraft() {
    try {
      const row = await apiJson('/api/v2/response/plans/draft', {
        method: 'POST',
        body: JSON.stringify({ plan: plan || active?.plan }),
      });
      setDraft(row);
      setPlan(clone(row.plan));
      setValidation(row.validation || null);
      setWarnings(row.degradationWarnings || []);
      push(`Draft v${row.version} created`);
      load();
    } catch (err) {
      push(err.message || 'Draft failed');
    }
  }

  async function saveDraft() {
    if (!draft || draft.status !== 'DRAFT') return;
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
      push(err.message || 'Save failed');
    }
  }

  async function submitDraft() {
    if (!draft) return;
    try {
      const row = await apiJson(`/api/v2/response/plans/${draft.id}/submit`, { method: 'POST' });
      setDraft(row);
      push('Submitted for approval');
      load();
    } catch (err) {
      push(err.message || 'Submit failed');
    }
  }

  async function approveDraft() {
    if (!draft) return;
    try {
      await apiJson(`/api/v2/response/plans/${draft.id}/approve`, { method: 'POST' });
      push('Plan approved and activated');
      load();
    } catch (err) {
      push(err.message || 'Approve failed');
    }
  }

  async function rejectDraft() {
    if (!draft || !rejectComment.trim()) {
      push('Rejection comment required');
      return;
    }
    try {
      await apiJson(`/api/v2/response/plans/${draft.id}/reject`, {
        method: 'POST',
        body: JSON.stringify({ comment: rejectComment.trim() }),
      });
      push('Plan rejected');
      setRejectComment('');
      load();
    } catch (err) {
      push(err.message || 'Reject failed');
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
    try {
      const res = await apiJson('/api/v2/response/plans/preview', {
        method: 'POST',
        body: JSON.stringify({ plan, level: selectedLevel, facts }),
      });
      setPreview(res);
    } catch (err) {
      push(err.message || 'Preview failed');
    }
  }

  async function saveIntegration(kind, body) {
    try {
      await apiJson(`/api/v2/response/integrations/${kind}`, {
        method: 'PUT',
        body: JSON.stringify(body),
      });
      push(`${kind} saved`);
      const integ = await apiJson('/api/v2/response/integrations');
      setIntegrations(integ);
    } catch (err) {
      push(err.message || 'Integration save failed');
    }
  }

  async function testIntegration(kind) {
    try {
      const res = await apiJson(`/api/v2/response/integrations/${kind}/test`, {
        method: 'POST',
        skipErrorToast: true,
      });
      push(res.lastTestOk ? `${kind} ping OK` : `${kind} ping failed: ${res.lastTestDetail}`);
      const integ = await apiJson('/api/v2/response/integrations');
      setIntegrations(integ);
    } catch (err) {
      push(err.message || 'Test failed');
    }
  }

  if (!hasPermission('response:read')) {
    return <Navigate to="/app" replace />;
  }

  if (loading) {
    return <div className="p-6 text-sm text-sv-muted">Loading response plans…</div>;
  }

  const editing = draft?.status === 'DRAFT' && canWrite;
  const stepDrawer = editStep
    ? plan?.levels?.[editStep.level]?.steps?.[editStep.index]
    : null;

  return (
    <div className="flex h-full min-h-0 flex-col gap-4 p-6">
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold tracking-tight">Response plans</h1>
          <p className="mt-1 max-w-2xl text-sm text-sv-muted">
            Admin-defined actuation per risk level. The live call uses the session-snapshotted ACTIVE plan — never a hard-coded ladder.
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {active && (
            <Badge tone={STATUS_TONE.ACTIVE}>ACTIVE v{active.version}</Badge>
          )}
          {draft && (
            <Badge tone={STATUS_TONE[draft.status] || 'neutral'}>
              {draft.status} v{draft.version}
            </Badge>
          )}
        </div>
      </header>

      <div className="flex gap-2 border-b border-sv-border pb-2 text-sm">
        {[
          ['editor', 'Ladder editor'],
          ['preview', 'Preview a call'],
          ['history', 'Version history'],
          ['integrations', 'Integrations'],
        ].map(([id, label]) => (
          <button
            key={id}
            type="button"
            className={`rounded px-3 py-1.5 ${tab === id ? 'bg-sv-accent/15 text-sv-accent' : 'text-sv-muted hover:text-sv-fg'}`}
            onClick={() => setTab(id)}
          >
            {label}
          </button>
        ))}
      </div>

      {tab === 'editor' && (
        <div className="grid min-h-0 flex-1 gap-4 lg:grid-cols-[1fr_240px_280px]">
          <div className="min-h-0 overflow-auto">
            <div className="mb-3 flex flex-wrap gap-2">
              {canWrite && !draft && (
                <Button onClick={createDraft}>Create draft from ACTIVE</Button>
              )}
              {editing && (
                <>
                  <Button onClick={saveDraft}>Save draft</Button>
                  {canSubmit && <Button variant="primary" onClick={submitDraft}>Submit</Button>}
                </>
              )}
              {draft?.status === 'PENDING_APPROVAL' && canApprove && (
                <>
                  <Button variant="primary" onClick={approveDraft}>Approve</Button>
                  <Input
                    className="w-56"
                    placeholder="Reject comment"
                    value={rejectComment}
                    onChange={(e) => setRejectComment(e.target.value)}
                  />
                  <Button variant="danger" onClick={rejectDraft}>Reject</Button>
                </>
              )}
            </div>

            <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
              {LEVELS.map((level) => {
                const lp = plan?.levels?.[level] || { steps: [] };
                const status = validation?.levels?.[level];
                return (
                  <section
                    key={level}
                    className="flex min-h-[320px] flex-col rounded-lg border border-sv-border bg-sv-panel/60 p-3"
                    onDragOver={(e) => e.preventDefault()}
                    onDrop={(e) => {
                      e.preventDefault();
                      if (!editing) return;
                      const key = e.dataTransfer.getData('text/action-key');
                      if (key) addStep(level, key);
                    }}
                  >
                    <div className="mb-2 flex items-center justify-between gap-2">
                      <h2 className="text-sm font-semibold">{LEVEL_LABEL[level]}</h2>
                      <span className={`text-xs ${status?.ok === false ? 'text-red-400' : 'text-emerald-400'}`}>
                        {status?.ok === false ? '✕' : '✓'}
                      </span>
                    </div>
                    {editing && (
                      <label className="mb-2 flex items-center gap-2 text-[11px] text-sv-muted">
                        <input
                          type="checkbox"
                          checked={!!lp.operatorOverridePermitted}
                          onChange={(e) => updatePlan((p) => {
                            p.levels[level].operatorOverridePermitted = e.target.checked;
                          })}
                        />
                        Override OK
                        <input
                          type="checkbox"
                          checked={!!lp.overrideRequiresSupervisor}
                          onChange={(e) => updatePlan((p) => {
                            p.levels[level].overrideRequiresSupervisor = e.target.checked;
                          })}
                        />
                        Needs supervisor
                      </label>
                    )}
                    <ol className="flex flex-1 flex-col gap-2">
                      {(lp.steps || []).map((step, index) => (
                        <li
                          key={`${level}-${index}-${step.action}`}
                          className={`rounded border border-sv-border bg-sv-bg/80 p-2 text-xs ${editStep?.level === level && editStep?.index === index ? 'ring-1 ring-sv-accent' : ''}`}
                        >
                          <button
                            type="button"
                            className="w-full text-left"
                            onClick={() => setEditStep({ level, index })}
                          >
                            <div className="font-medium">{step.action}</div>
                            <div className="mt-0.5 text-sv-muted">
                              {step.trigger} · {step.autoExecute ? 'auto' : 'confirm'} · {step.delayMs || 0}ms
                            </div>
                          </button>
                          {editing && (
                            <div className="mt-1 flex gap-1">
                              <button type="button" className="text-sv-muted hover:text-sv-fg" onClick={() => moveStep(level, index, -1)}>↑</button>
                              <button type="button" className="text-sv-muted hover:text-sv-fg" onClick={() => moveStep(level, index, 1)}>↓</button>
                              <button type="button" className="text-red-400" onClick={() => removeStep(level, index)}>✕</button>
                            </div>
                          )}
                        </li>
                      ))}
                    </ol>
                  </section>
                );
              })}
            </div>
          </div>

          <aside className="min-h-0 overflow-auto rounded-lg border border-sv-border bg-sv-panel/40 p-3">
            <h3 className="mb-2 text-sm font-semibold">Action catalogue</h3>
            <p className="mb-2 text-[11px] text-sv-muted">Drag onto a level column{editing ? '' : ' (create a draft to edit)'}.</p>
            <ul className="space-y-1">
              {catalogue.map((a) => (
                <li
                  key={a.key}
                  draggable={editing}
                  onDragStart={(e) => e.dataTransfer.setData('text/action-key', a.key)}
                  className="cursor-grab rounded border border-sv-border/70 bg-sv-bg/70 px-2 py-1.5 text-xs"
                  title={a.description}
                >
                  <div className="font-medium">{a.label}</div>
                  <div className="text-sv-muted">{a.key}</div>
                </li>
              ))}
            </ul>
          </aside>

          <aside className="min-h-0 space-y-3 overflow-auto rounded-lg border border-sv-border bg-sv-panel/40 p-3">
            <div>
              <h3 className="mb-2 text-sm font-semibold">Safety floors</h3>
              <ul className="space-y-1 text-xs">
                {LEVELS.map((level) => {
                  const st = validation?.levels?.[level];
                  return (
                    <li key={level} className="rounded border border-sv-border/60 p-2">
                      <div className="flex justify-between">
                        <span>{level}</span>
                        <span className={st?.ok === false ? 'text-red-400' : 'text-emerald-400'}>
                          {st?.ok === false ? 'fail' : 'ok'}
                        </span>
                      </div>
                      {(st?.reasons || []).map((r) => (
                        <div key={r} className="mt-1 text-red-300/90">{r}</div>
                      ))}
                    </li>
                  );
                })}
              </ul>
            </div>
            {warnings.length > 0 && (
              <div>
                <h3 className="mb-1 text-sm font-semibold text-amber-300">Integration warnings</h3>
                <ul className="space-y-1 text-[11px] text-amber-200/90">
                  {warnings.map((w) => (
                    <li key={w}>{w}</li>
                  ))}
                </ul>
              </div>
            )}
            {stepDrawer && editStep && (
              <div>
                <h3 className="mb-2 text-sm font-semibold">Step params</h3>
                <Select
                  value={stepDrawer.action}
                  disabled={!editing}
                  onChange={(e) => updatePlan((p) => {
                    p.levels[editStep.level].steps[editStep.index].action = e.target.value;
                  })}
                  options={catalogue.map((a) => ({ value: a.key, label: a.key }))}
                />
                <Select
                  className="mt-2"
                  value={stepDrawer.trigger}
                  disabled={!editing}
                  onChange={(e) => updatePlan((p) => {
                    p.levels[editStep.level].steps[editStep.index].trigger = e.target.value;
                  })}
                  options={TRIGGERS.map((t) => ({ value: t, label: t }))}
                />
                <label className="mt-2 block text-[11px] text-sv-muted">
                  Delay ms
                  <Input
                    type="number"
                    disabled={!editing}
                    value={stepDrawer.delayMs ?? 0}
                    onChange={(e) => updatePlan((p) => {
                      p.levels[editStep.level].steps[editStep.index].delayMs = Number(e.target.value) || 0;
                    })}
                  />
                </label>
                <label className="mt-2 flex items-center gap-2 text-[11px]">
                  <input
                    type="checkbox"
                    disabled={!editing}
                    checked={!!stepDrawer.autoExecute}
                    onChange={(e) => updatePlan((p) => {
                      const s = p.levels[editStep.level].steps[editStep.index];
                      s.autoExecute = e.target.checked;
                      if (e.target.checked) s.operatorConfirm = false;
                    })}
                  />
                  Auto-execute
                </label>
                <label className="mt-1 flex items-center gap-2 text-[11px]">
                  <input
                    type="checkbox"
                    disabled={!editing}
                    checked={!!stepDrawer.operatorConfirm}
                    onChange={(e) => updatePlan((p) => {
                      const s = p.levels[editStep.level].steps[editStep.index];
                      s.operatorConfirm = e.target.checked;
                      if (e.target.checked) s.autoExecute = false;
                    })}
                  />
                  Operator confirm
                </label>
                <label className="mt-1 flex items-center gap-2 text-[11px]">
                  <input
                    type="checkbox"
                    disabled={!editing}
                    checked={!!stepDrawer.requiresAck}
                    onChange={(e) => updatePlan((p) => {
                      p.levels[editStep.level].steps[editStep.index].requiresAck = e.target.checked;
                    })}
                  />
                  Requires ack
                </label>
                {catalogueByKey[stepDrawer.action]?.description && (
                  <p className="mt-2 text-[11px] text-sv-muted">{catalogueByKey[stepDrawer.action].description}</p>
                )}
              </div>
            )}
          </aside>
        </div>
      )}

      {tab === 'preview' && (
        <div className="max-w-3xl space-y-3">
          <div className="flex flex-wrap items-end gap-2">
            <label className="text-xs">
              Level
              <Select
                value={selectedLevel}
                onChange={(e) => setSelectedLevel(e.target.value)}
                options={LEVELS.map((l) => ({ value: l, label: LEVEL_LABEL[l] }))}
              />
            </label>
            <label className="min-w-[280px] flex-1 text-xs">
              Sample facts (JSON)
              <Input value={previewFacts} onChange={(e) => setPreviewFacts(e.target.value)} />
            </label>
            <Button onClick={runPreview}>Simulate level entry</Button>
          </div>
          {preview && (
            <ol className="space-y-2">
              {(preview.timeline || []).map((row) => (
                <li
                  key={`${row.stepIndex}-${row.action}`}
                  className={`rounded border border-sv-border p-3 text-sm ${row.wouldRun ? 'bg-emerald-500/10' : 'bg-sv-panel/40 opacity-70'}`}
                >
                  <div className="font-medium">+{row.atMs}ms · {row.action}</div>
                  <div className="text-xs text-sv-muted">
                    {row.wouldRun ? 'would run' : `skipped — ${row.skipReason || 'n/a'}`}
                    {row.operatorConfirm ? ' · needs confirm' : ''}
                  </div>
                </li>
              ))}
            </ol>
          )}
        </div>
      )}

      {tab === 'history' && (
        <div className="overflow-auto">
          <Table
            columns={[
              { key: 'version', header: 'Ver' },
              { key: 'status', header: 'Status', render: (r) => <Badge tone={STATUS_TONE[r.status] || 'neutral'}>{r.status}</Badge> },
              { key: 'contentSha256', header: 'SHA', render: (r) => <span className="font-mono text-[11px]">{String(r.contentSha256 || '').slice(0, 12)}…</span> },
              { key: 'submittedBy', header: 'Submitted' },
              { key: 'approvedBy', header: 'Approved' },
              { key: 'createdAt', header: 'Created' },
              {
                key: 'actions',
                header: '',
                render: (r) => (
                  <Button
                    onClick={() => {
                      setDraft(r);
                      setPlan(clone(r.plan));
                      setValidation(r.validation || null);
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
        </div>
      )}

      {tab === 'integrations' && (
        <IntegrationsPanel
          integrations={integrations}
          canWrite={canWrite}
          onSave={saveIntegration}
          onTest={testIntegration}
        />
      )}

      <p className="text-[11px] text-sv-muted">
        Dual-control mirrors policies / risk tuning. L3/L4 edits require a distinct POLICY_APPROVER.{' '}
        <Link className="text-sv-accent" to="/app/settings/risk-tuning">Risk tuning</Link>
      </p>
    </div>
  );
}

function IntegrationsPanel({ integrations, canWrite, onSave, onTest }) {
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
    <div className="grid gap-3 md:grid-cols-2">
      {kinds.map((kind) => {
        const row = byKind[kind] || { kind, enabled: false, config: {}, hasSecrets: false };
        return (
          <IntegrationCard
            key={kind}
            kind={kind}
            row={row}
            canWrite={canWrite}
            onSave={onSave}
            onTest={onTest}
          />
        );
      })}
    </div>
  );
}

function IntegrationCard({ kind, row, canWrite, onSave, onTest }) {
  const [enabled, setEnabled] = useState(!!row.enabled);
  const [webhookUrl, setWebhookUrl] = useState(row.config?.webhookUrl || '');
  const [extension, setExtension] = useState(row.config?.extension || '');
  const [hmacSecret, setHmacSecret] = useState('');

  useEffect(() => {
    setEnabled(!!row.enabled);
    setWebhookUrl(row.config?.webhookUrl || '');
    setExtension(row.config?.extension || '');
  }, [row]);

  return (
    <div className="rounded-lg border border-sv-border bg-sv-panel/50 p-4">
      <div className="mb-2 flex items-center justify-between">
        <h3 className="text-sm font-semibold">{kind}</h3>
        <Badge tone={row.lastTestOk ? 'success' : row.lastTestOk === false ? 'danger' : 'neutral'}>
          {row.lastTestOk === true ? 'healthy' : row.lastTestOk === false ? 'fail' : row.enabled ? 'configured' : 'off'}
        </Badge>
      </div>
      <label className="flex items-center gap-2 text-xs">
        <input type="checkbox" disabled={!canWrite} checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
        Enabled
      </label>
      {(kind === 'SUPERVISOR_BRIDGE') ? (
        <label className="mt-2 block text-xs">
          Extension
          <Input disabled={!canWrite} value={extension} onChange={(e) => setExtension(e.target.value)} />
        </label>
      ) : (
        <label className="mt-2 block text-xs">
          Webhook URL
          <Input disabled={!canWrite} value={webhookUrl} onChange={(e) => setWebhookUrl(e.target.value)} />
        </label>
      )}
      <label className="mt-2 block text-xs">
        HMAC secret {row.hasSecrets ? '(set)' : ''}
        <Input
          type="password"
          disabled={!canWrite}
          value={hmacSecret}
          placeholder={row.hasSecrets ? '••••••••' : ''}
          onChange={(e) => setHmacSecret(e.target.value)}
        />
      </label>
      {row.lastTestDetail && (
        <p className="mt-2 text-[11px] text-sv-muted">{row.lastTestDetail}</p>
      )}
      {canWrite && (
        <div className="mt-3 flex gap-2">
          <Button
            onClick={() => onSave(kind, {
              enabled,
              config: {
                ...(row.config || {}),
                webhookUrl: webhookUrl || undefined,
                extension: extension || undefined,
              },
              secrets: hmacSecret ? { hmacSecret, webhookUrl: webhookUrl || undefined, extension: extension || undefined } : undefined,
            })}
          >
            Save
          </Button>
          <Button variant="secondary" onClick={() => onTest(kind)}>Test</Button>
        </div>
      )}
    </div>
  );
}
