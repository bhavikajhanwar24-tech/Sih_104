import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router-dom';
import PropTypes from 'prop-types';
import { useAuth } from '@/context/AuthContext.jsx';
import { apiJson } from '@/services/api.js';
import { ConflictPanel } from '@/components/policy/ConflictPanel.jsx';
import { Badge, Button, Input, Modal, Table, Tabs } from '@/ui';
import { useToast } from '@/ui/Toast.jsx';

const SEVERITY_TONE = {
  LOW: 'neutral',
  MEDIUM: 'accent',
  HIGH: 'warn',
  CRITICAL: 'danger',
};

const STATUS_TONE = {
  PROPOSED: 'accent',
  ACCEPTED: 'success',
  EDITED: 'accent',
  REJECTED: 'danger',
};

/**
 * F6 — full-screen policy review workspace.
 */
export function PolicyReviewPage() {
  const { hasPermission } = useAuth();
  const { push } = useToast();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const setId = params.get('set');
  const [set, setSet] = useState(null);
  const [facts, setFacts] = useState([]);
  const [selectedRuleId, setSelectedRuleId] = useState(null);
  const [chunks, setChunks] = useState([]);
  const [docMeta, setDocMeta] = useState(null);
  const [advanced, setAdvanced] = useState(false);
  const [editJson, setEditJson] = useState('');
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [rerunBusy, setRerunBusy] = useState(false);
  const [editError, setEditError] = useState('');
  const [builderError, setBuilderError] = useState('');
  const [rejectedOpen, setRejectedOpen] = useState(true);
  const [builderFocusKey, setBuilderFocusKey] = useState(0);
  const [rulesJsonOpen, setRulesJsonOpen] = useState(false);
  const [rulesJsonText, setRulesJsonText] = useState('');
  const [deletedOpen, setDeletedOpen] = useState(false);
  const [conflictOpen, setConflictOpen] = useState(false);

  const canWrite = hasPermission('policies:write');

  const load = useCallback(async (opts = {}) => {
    if (!setId) return;
    const soft = Boolean(opts.soft);
    if (!soft) setLoading(true);
    try {
      const [s, catalogue] = await Promise.all([
        apiJson(`/api/v2/policy/sets/${setId}`),
        apiJson('/api/v2/policy/fact-catalogue'),
      ]);
      setSet(s);
      setFacts(catalogue.facts || []);
      const rules = s.rules || [];
      setSelectedRuleId((prev) => {
        if (!rules.length) return null;
        if (prev && rules.some((r) => r.id === prev)) return prev;
        return rules[0].id;
      });
    } catch (err) {
      push(err.message || 'Failed to load policy set');
    } finally {
      if (!soft) setLoading(false);
    }
  }, [setId, push]);

  useEffect(() => {
    load();
  }, [load]);

  const selected = useMemo(
    () => (set?.rules || []).find((r) => r.id === selectedRuleId) || null,
    [set, selectedRuleId]
  );

  const diagnostics = set?.compileDiagnostics || null;
  const allRules = (set?.rules || []).filter((r) => !r.deletedAt);
  const deletedRules = (set?.deletedRules || set?.rules || []).filter((r) => r.deletedAt);
  const reviewRules = useMemo(
    () => allRules.filter((r) => r.status !== 'REJECTED'),
    [allRules]
  );
  /** Meaningful rejections only — placeholders (empty when / no clause) are never shown as cards. */
  const rejectedRules = useMemo(
    () => allRules.filter((r) => r.status === 'REJECTED' && isMeaningfulRejection(r)),
    [allRules]
  );
  const couldNotRule = diagnostics?.couldNotRule || [];
  const ruleCount = allRules.length;
  const canSubmit = Boolean(set?.canSubmit);
  const preflight = set?.preflight || null;
  const compilationId = diagnostics?.compilationId || null;
  const canRerun =
    canWrite &&
    compilationId &&
    (diagnostics?.hasFailedChunks ||
      diagnostics?.compilationStatus === 'COMPLETED_NO_RULES' ||
      ruleCount === 0);

  function editToFix(rule) {
    setSelectedRuleId(rule.id);
    setAdvanced(false);
    setBuilderError('');
    setEditError('');
    setRejectedOpen(true);
    setBuilderFocusKey((k) => k + 1);
  }

  async function addRuleManually(prefill = {}) {
    if (!canWrite) return;
    const text =
      prefill.quote ||
      selected?.source?.quote ||
      chunks.find((c) => c.id === (prefill.chunkId || selected?.source?.chunkIds?.[0]))?.text ||
      '';
    if (!text || String(text).trim().length < 15) {
      push('Select a clause with enough text, or add the rule from the Rules tab text box');
      return;
    }
    setBusy(true);
    try {
      const data = await apiJson('/api/v2/policy/rules/from-text', {
        method: 'POST',
        body: JSON.stringify({ text: String(text).trim(), setId }),
      });
      if (!data.ok) {
        push(data.reason || 'Could not extract a rule');
        return;
      }
      push(data.message || 'Rule added');
      await load({ soft: true });
      if ((data.newConflicts || []).length) setConflictOpen(true);
    } catch (err) {
      push(err.message || 'Could not add rule');
    } finally {
      setBusy(false);
    }
  }

  async function rerunFailed() {
    if (!canRerun || !compilationId) return;
    setRerunBusy(true);
    try {
      await apiJson(`/api/v2/policy/compilations/${compilationId}/rerun-failed`, {
        method: 'POST',
      });
      push('Re-running failed chunks');
      await load();
    } catch (err) {
      push(err.message || 'Re-run failed');
    } finally {
      setRerunBusy(false);
    }
  }

  useEffect(() => {
    let cancelled = false;
    async function loadChunks() {
      if (!selected?.source?.documentId) {
        setChunks([]);
        setDocMeta(null);
        return;
      }
      try {
        const docId = selected.source.documentId;
        const [doc, ch] = await Promise.all([
          apiJson(`/api/v2/policies/documents/${docId}`),
          apiJson(`/api/v2/policies/documents/${docId}/chunks`),
        ]);
        if (!cancelled) {
          setDocMeta(doc);
          setChunks(ch.items || []);
        }
      } catch {
        if (!cancelled) {
          setChunks([]);
        }
      }
    }
    loadChunks();
    return () => {
      cancelled = true;
    };
  }, [selected]);

  useEffect(() => {
    if (selected) {
      setEditJson(JSON.stringify({
        when: selected.when,
        then: selected.then,
        appliesTo: selected.appliesTo,
      }, null, 2));
    }
  }, [selected]);

  if (!hasPermission('policies:read')) {
    return <Navigate to="/app" replace />;
  }
  if (!setId) {
    return <Navigate to="/app/policies?tab=rules" replace />;
  }

  async function setStatus(status) {
    if (!canWrite || !selected) return;
    setBusy(true);
    try {
      await apiJson(`/api/v2/policy/rules/${selected.id}`, {
        method: 'PATCH',
        body: JSON.stringify({ status }),
        skipErrorToast: true,
      });
      push(`Rule ${status.toLowerCase()}`);
      setEditError('');
      await load({ soft: true });
    } catch (err) {
      const msg = err.message || 'Update failed';
      if (/quote not found/i.test(msg) || err.code === 'HALLUCINATED_QUOTE') {
        setEditError('quote not found in source');
        push('quote not found in source');
      } else {
        push(msg);
      }
    } finally {
      setBusy(false);
    }
  }

  async function saveAdvanced() {
    if (!canWrite || !selected) return;
    const err = validateEditPayload(editJson, facts);
    setEditError(err || '');
    if (err) return;
    setBusy(true);
    try {
      const parsed = JSON.parse(editJson);
      const result = await apiJson(`/api/v2/policy/rules/${selected.id}`, {
        method: 'PATCH',
        body: JSON.stringify({ ...parsed, status: 'EDITED' }),
        skipErrorToast: true,
      });
      if (result.editError) {
        setEditError(result.editError);
        push(result.editError);
      } else {
        setEditError('');
        push('Rule saved');
      }
      await load({ soft: true });
    } catch (e) {
      const msg = e.message || 'Save failed';
      const shown = /quote not found/i.test(msg) ? 'quote not found in source' : msg;
      setEditError(shown);
      push(shown);
    } finally {
      setBusy(false);
    }
  }

  async function patchFact(factPath, op, value) {
    if (!canWrite || !selected) return;
    const next = { fact: factPath, op, value };
    if (op === 'EXISTS') delete next.value;
    await patchWhen(next);
  }

  async function patchWhen(when) {
    if (!canWrite || !selected) return;
    setBusy(true);
    try {
      await apiJson(`/api/v2/policy/rules/${selected.id}`, {
        method: 'PATCH',
        body: JSON.stringify({ when, status: 'EDITED' }),
      });
      await load({ soft: true });
    } catch (err) {
      push(err.message || 'Edit failed');
    } finally {
      setBusy(false);
    }
  }

  const citeIds = new Set(selected?.source?.chunkIds || []);

  useEffect(() => {
    const id = selected?.source?.chunkIds?.[0];
    if (!id) return undefined;
    const el = document.getElementById(`chunk-${id}`);
    if (el) {
      el.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }
    return undefined;
  }, [selected?.id, selected?.source?.chunkIds]);

  return (
    <div className="flex h-[calc(100vh-3.5rem)] flex-col bg-sv-bg">
      <header className="flex items-center justify-between border-b border-sv-border px-4 py-2">
        <div className="flex items-center gap-3">
          <Button variant="ghost" className="px-2 py-1 text-xs" onClick={() => navigate('/app/policies?tab=rules')}>
            ← Rules
          </Button>
          <div>
            <h1 className="text-sm font-semibold text-sv-fg">
              {set?.name || 'Policy review'} · v{set?.version}
            </h1>
            <p className="text-xs text-sv-muted">{set?.status}</p>
          </div>
        </div>
        <div className="flex gap-2">
          {canWrite && canRerun ? (
            <Button
              className="px-3 py-1 text-xs"
              variant="ghost"
              disabled={rerunBusy}
              onClick={rerunFailed}
            >
              {rerunBusy ? 'Re-running…' : 'Re-run compile'}
            </Button>
          ) : null}
          {canWrite && set?.status === 'DRAFT' ? (
            <Button
              className="px-3 py-1 text-xs"
              variant="danger"
              disabled={busy}
              onClick={async () => {
                try {
                  await apiJson(`/api/v2/policy/sets/${setId}`, { method: 'DELETE' });
                  push('Draft deleted');
                  navigate('/app/policies?tab=rules');
                } catch (err) {
                  push(err.message || 'Delete failed');
                }
              }}
            >
              Delete draft
            </Button>
          ) : null}
          {canWrite && set?.status === 'DRAFT' ? (
            <Button
              className="px-3 py-1 text-xs"
              variant="ghost"
              disabled={busy}
              onClick={async () => {
                try {
                  const r = await apiJson(`/api/v2/policy/sets/${setId}/repair-sources`, {
                    method: 'POST',
                  });
                  push(
                    `Repaired sources on ${r.repaired ?? 0} rule(s)` +
                      (r.skipped ? ` · ${r.skipped} skipped` : ''),
                  );
                  await load({ soft: true });
                } catch (err) {
                  push(err.message || 'Repair failed');
                }
              }}
            >
              Repair sources
            </Button>
          ) : null}
          {(set?.openConflictCount > 0 || (set?.conflicts || []).some((c) => c.status === 'OPEN')) ? (
            <Button
              className="px-3 py-1 text-xs"
              variant="ghost"
              onClick={() => setConflictOpen(true)}
            >
              Conflicts ({set.openConflictCount || (set.conflicts || []).filter((c) => c.status === 'OPEN').length})
            </Button>
          ) : null}
          {canWrite && set?.status === 'DRAFT' ? (
            <Button
              className="px-3 py-1 text-xs"
              variant="ghost"
              onClick={async () => {
                try {
                  const j = await apiJson(`/api/v2/policy/sets/${setId}/json`);
                  setRulesJsonText(JSON.stringify(j.rules, null, 2));
                  setRulesJsonOpen(true);
                } catch (err) {
                  push(err.message || 'Failed to load JSON');
                }
              }}
            >
              Edit as JSON
            </Button>
          ) : null}
          {canWrite && set?.status === 'DRAFT' ? (
            <Button
              className="px-3 py-1 text-xs"
              disabled={busy || !canSubmit}
              title={
                canSubmit
                  ? 'Submit for approval'
                  : 'Pre-flight must be fully green (see checklist)'
              }
              onClick={async () => {
                try {
                  await apiJson(`/api/v2/policy/sets/${setId}/submit`, { method: 'POST' });
                  push('Submitted for approval');
                  await load({ soft: true });
                } catch (err) {
                  push(err.message || 'Submit failed');
                }
              }}
            >
              Submit for approval
            </Button>
          ) : null}
        </div>
      </header>

      {preflight?.checklist ? (
        <div className="border-b border-sv-border bg-sv-elevated/30 px-4 py-2">
          <p className="mb-1 text-xs font-semibold uppercase tracking-wide text-sv-muted">
            Pre-flight {preflight.canSubmit ? '· ready' : '· blocked'}
          </p>
          <ul className="flex flex-wrap gap-3 text-xs">
            {(preflight.checklist || []).map((c) => (
              <li key={c.id} className={c.pass ? 'text-sv-muted' : 'text-risk-critical'}>
                {c.pass ? '✓' : '✗'} {c.label || c.id}: {c.detail || c.message}
              </li>
            ))}
          </ul>
        </div>
      ) : null}

      {(set?.conflicts || []).length > 0 ? (
        <div className="border-b border-sv-border bg-risk-alert/5 px-4 py-2">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <p className="text-xs font-semibold uppercase tracking-wide text-risk-alert">
              Conflicts ({(set.conflicts || []).filter((c) => c.status === 'OPEN').length} open)
            </p>
            <Button className="px-2 py-1 text-xs" variant="ghost" onClick={() => setConflictOpen(true)}>
              Open conflict panel
            </Button>
          </div>
          <ul className="mt-1 space-y-1 text-xs text-sv-muted">
            {(set.conflicts || []).slice(0, 5).map((c) => (
              <li key={c.id}>
                <Badge tone={c.advisory ? 'neutral' : 'danger'}>{c.type}</Badge>{' '}
                {c.summary || c.why} · {c.status}
                {c.resolution === 'KEEP_BOTH' ? (
                  <span className="ml-1 text-risk-alert">kept both</span>
                ) : null}
              </li>
            ))}
          </ul>
        </div>
      ) : null}

      {loading || !set ? (
        <p className="p-6 text-sm text-sv-muted">Loading…</p>
      ) : (
        <div className="grid min-h-0 flex-1 grid-cols-1 lg:grid-cols-2">
          <section className="min-h-0 overflow-y-auto border-r border-sv-border p-4">
            <h2 className="mb-2 text-xs font-semibold uppercase tracking-wide text-sv-muted">
              Source {docMeta?.title ? `· ${docMeta.title}` : ''}
            </h2>
            {chunks.length === 0 ? (
              <p className="text-sm text-sv-muted">Select a rule to load its cited document.</p>
            ) : (
              <ul className="space-y-3">
                {chunks.map((c) => {
                  const cited = citeIds.has(c.id);
                  const quote = selected?.source?.quote || '';
                  const text = c.text || '';
                  const highlighted = cited && quote ? highlightQuote(text, quote) : text;
                  return (
                    <li
                      key={c.id}
                      id={`chunk-${c.id}`}
                      className={`rounded border p-3 text-sm ${
                        cited ? 'border-risk-watch/50 bg-risk-watch/5' : 'border-sv-border'
                      }`}
                    >
                      <div className="mb-1 flex flex-wrap items-center justify-between gap-2 text-xs text-sv-muted">
                        <span>
                          #{c.ordinal} {c.headingPath || ''}{' '}
                          {c.pageNo != null ? `p.${c.pageNo}` : ''}
                        </span>
                        {canWrite && set.status === 'DRAFT' ? (
                          <Button
                            className="px-2 py-0.5 text-[11px]"
                            variant="ghost"
                            onClick={() =>
                              addRuleManually({
                                documentId: docMeta?.id || selected?.source?.documentId,
                                chunkId: c.id,
                                quote: (c.text || '').replace(/\s+/g, ' ').slice(0, 200),
                                clauseRef: c.headingPath || '',
                              })
                            }
                          >
                            Add rule from this clause
                          </Button>
                        ) : null}
                      </div>
                      <pre className="whitespace-pre-wrap font-sans text-sv-fg">{highlighted}</pre>
                    </li>
                  );
                })}
              </ul>
            )}
          </section>

          <section className="min-h-0 overflow-y-auto p-4">
            <h2 className="mb-2 text-xs font-semibold uppercase tracking-wide text-sv-muted">
              Rules · {ruleCount} {ruleCount === 1 ? 'rule' : 'rules'}
            </h2>
            {ruleCount === 0 ? (
              <EmptyRulesState
                diagnostics={diagnostics}
                canRerun={canRerun}
                rerunBusy={rerunBusy}
                onRerun={rerunFailed}
              />
            ) : (
              <>
              <ul className="mb-4 max-h-[36vh] space-y-2 overflow-y-auto pr-1">
                {reviewRules.map((r) => (
                  <li key={r.id}>
                    <RuleCard
                      rule={r}
                      selected={r.id === selectedRuleId}
                      onSelect={() => setSelectedRuleId(r.id)}
                      onShowInDocument={() => setSelectedRuleId(r.id)}
                    />
                  </li>
                ))}
              </ul>
              {rejectedRules.length > 0 ? (
                <div className="mb-4 rounded border border-risk-critical/40 bg-risk-critical/5">
                  <button
                    type="button"
                    className="flex w-full items-center justify-between px-3 py-2 text-left text-xs font-semibold uppercase tracking-wide text-risk-critical"
                    onClick={() => setRejectedOpen((v) => !v)}
                    aria-expanded={rejectedOpen}
                  >
                    <span>
                      Rejected by validator · {rejectedRules.length}
                    </span>
                    <span aria-hidden>{rejectedOpen ? '▾' : '▸'}</span>
                  </button>
                  {rejectedOpen ? (
                    <ul className="max-h-[28vh] space-y-2 overflow-y-auto border-t border-risk-critical/20 px-2 py-2">
                      {rejectedRules.map((r) => (
                        <li key={r.id}>
                          <RuleCard
                            rule={r}
                            selected={r.id === selectedRuleId}
                            onSelect={() => setSelectedRuleId(r.id)}
                            rejected
                            onShowInDocument={() => setSelectedRuleId(r.id)}
                            onEditToFix={
                              canWrite && set.status === 'DRAFT'
                                ? () =>
                                    isUnrepairableRejection(r)
                                      ? addRuleManually({
                                          quote: r.source?.quote,
                                          clauseRef: r.source?.clauseRef,
                                          documentId: r.source?.documentId,
                                          chunkId: r.source?.chunkIds?.[0],
                                          title: r.title,
                                          when: r.when,
                                        })
                                      : editToFix(r)
                                : undefined
                            }
                            editLabel={
                              isUnrepairableRejection(r) ? 'Add rule manually for this clause' : 'Edit to fix'
                            }
                          />
                        </li>
                      ))}
                    </ul>
                  ) : null}
                </div>
              ) : null}

              {couldNotRule.length > 0 ? (
                <div className="mb-4 rounded border border-risk-watch/40 bg-risk-watch/5">
                  <p className="px-3 py-2 text-xs font-semibold uppercase tracking-wide text-risk-watch">
                    Could not be turned into a rule · {couldNotRule.length}
                  </p>
                  <ul className="max-h-[28vh] space-y-2 overflow-y-auto border-t border-risk-watch/20 px-2 py-2">
                    {couldNotRule.map((c) => (
                      <li
                        key={`${c.chunkId}-${c.documentId}`}
                        className="rounded border border-sv-border bg-sv-bg px-3 py-2 text-sm"
                      >
                        <p className="font-medium text-sv-fg">
                          {c.clauseRef || c.headingPath || 'Clause'}
                          {c.pageNo != null ? (
                            <span className="ml-2 text-xs text-sv-muted">p.{c.pageNo}</span>
                          ) : null}
                        </p>
                        {c.quote || c.textPreview ? (
                          <p className="mt-1 border-l-2 border-sv-border pl-2 text-xs italic text-sv-muted">
                            “{(c.quote || c.textPreview || '').slice(0, 160)}
                            {(c.quote || c.textPreview || '').length > 160 ? '…' : ''}”
                          </p>
                        ) : null}
                        <p className="mt-1 text-xs text-sv-muted">{c.reason || 'Model returned empty/placeholder'}</p>
                        {canWrite && set.status === 'DRAFT' ? (
                          <Button
                            className="mt-2 px-2 py-1 text-xs"
                            disabled={busy}
                            onClick={() =>
                              addRuleManually({
                                quote: c.quote || c.textPreview,
                                clauseRef: c.clauseRef,
                                documentId: c.documentId,
                                chunkId: c.chunkId,
                              })
                            }
                          >
                            Add rule manually
                          </Button>
                        ) : null}
                      </li>
                    ))}
                  </ul>
                </div>
              ) : null}
              </>
            )}

            {selected ? (
              <div className="space-y-3 rounded border border-sv-border bg-sv-elevated/30 p-3">
                {(selected.warnings || []).length > 0 ? (
                  <div className="space-y-1">
                    {selected.warnings.map((w, i) => (
                      <p
                        key={i}
                        className={`text-xs ${
                          w.code === 'POSSIBLE_DUPLICATE' ? 'text-risk-watch font-medium' : 'text-risk-watch'
                        }`}
                      >
                        ⚠ {w.code}: {w.message}
                      </p>
                    ))}
                  </div>
                ) : null}
                {selected.status === 'REJECTED' && canWrite && set.status === 'DRAFT' ? (
                  <Button
                    className="px-2 py-1 text-xs"
                    disabled={busy}
                    onClick={() =>
                      isUnrepairableRejection(selected)
                        ? addRuleManually({
                            quote: selected.source?.quote,
                            clauseRef: selected.source?.clauseRef,
                            documentId: selected.source?.documentId,
                            chunkId: selected.source?.chunkIds?.[0],
                            title: selected.title,
                            when: selected.when,
                          })
                        : editToFix(selected)
                    }
                  >
                    {isUnrepairableRejection(selected)
                      ? 'Add rule manually for this clause'
                      : 'Edit to fix'}
                  </Button>
                ) : null}
                {editError ? (
                  <p className="text-xs text-risk-critical" role="alert">
                    {editError}
                  </p>
                ) : null}
                {canWrite && set.status === 'DRAFT' && selected.status !== 'REJECTED' ? (
                  <div className="flex flex-wrap gap-2">
                    <Button className="px-2 py-1 text-xs" disabled={busy} onClick={() => setStatus('ACCEPTED')}>
                      Accept
                    </Button>
                    <Button className="px-2 py-1 text-xs" disabled={busy} onClick={() => setStatus('REJECTED')}>
                      Reject
                    </Button>
                    <Button
                      className="px-2 py-1 text-xs"
                      variant="danger"
                      disabled={busy}
                      onClick={async () => {
                        const reason = window.prompt('Delete reason (min 5 chars)');
                        if (!reason || reason.trim().length < 5) {
                          push('Reason required (min 5 chars)');
                          return;
                        }
                        try {
                          await apiJson(
                            `/api/v2/policy/sets/${setId}/rules/${encodeURIComponent(selected.ruleId)}`,
                            {
                              method: 'DELETE',
                              body: JSON.stringify({ reason: reason.trim() }),
                            },
                          );
                          push('Rule deleted');
                          setSelectedRuleId(null);
                          await load({ soft: true });
                        } catch (err) {
                          push(err.message || 'Delete failed');
                        }
                      }}
                    >
                      Delete
                    </Button>
                    <Button
                      className="px-2 py-1 text-xs"
                      variant="ghost"
                      onClick={() => setAdvanced((v) => !v)}
                    >
                      {advanced ? 'Hide JSON' : 'Advanced JSON'}
                    </Button>
                  </div>
                ) : null}
                {canWrite && set.status === 'DRAFT' && selected.status === 'REJECTED' ? (
                  <Button
                    className="px-2 py-1 text-xs"
                    variant="ghost"
                    onClick={() => setAdvanced((v) => !v)}
                  >
                    {advanced ? 'Hide JSON' : 'Advanced JSON'}
                  </Button>
                ) : null}

                {!advanced && canWrite && set.status === 'DRAFT' ? (
                  <ConditionBuilder
                    key={`${selected.id}-${builderFocusKey}`}
                    facts={facts}
                    when={selected.when}
                    error={builderError}
                    onApply={(whenNext) => {
                      const err = validateWhenObject(whenNext, facts);
                      setBuilderError(err || '');
                      if (err) return;
                      if (whenNext && whenNext.fact) {
                        patchFact(whenNext.fact, whenNext.op, whenNext.value);
                      } else {
                        patchWhen(whenNext);
                      }
                    }}
                  />
                ) : null}

                {advanced ? (
                  <div>
                    <textarea
                      className="h-48 w-full rounded border border-sv-border bg-sv-bg p-2 font-mono text-xs"
                      value={editJson}
                      onChange={(e) => {
                        setEditJson(e.target.value);
                        setEditError(validateEditPayload(e.target.value, facts) || '');
                      }}
                    />
                    {editError ? (
                      <p className="mt-1 text-xs text-risk-critical">{editError}</p>
                    ) : null}
                    <Button
                      className="mt-2 px-2 py-1 text-xs"
                      disabled={busy || Boolean(editError)}
                      onClick={saveAdvanced}
                    >
                      Save JSON
                    </Button>
                  </div>
                ) : null}

                <KeywordsPanel
                  setId={setId}
                  keywords={set.keywords || []}
                  canWrite={canWrite && set.status === 'DRAFT'}
                  onChanged={() => load({ soft: true })}
                />

                {deletedRules.length > 0 ? (
                  <div className="mt-4 border-t border-sv-border pt-3">
                    <button
                      type="button"
                      className="text-xs font-medium text-sv-muted"
                      onClick={() => setDeletedOpen((v) => !v)}
                    >
                      {deletedOpen ? '▾' : '▸'} Deleted rules · {deletedRules.length}
                    </button>
                    {deletedOpen ? (
                      <ul className="mt-2 space-y-1 text-xs">
                        {deletedRules.map((r) => (
                          <li key={r.id} className="flex items-center justify-between gap-2">
                            <span>
                              {r.ruleId} · {r.title}
                              {r.deleteReason ? ` — ${r.deleteReason}` : ''}
                            </span>
                            {canWrite && set.status === 'DRAFT' ? (
                              <Button
                                className="px-2 py-0.5 text-[11px]"
                                variant="ghost"
                                onClick={async () => {
                                  try {
                                    await apiJson(
                                      `/api/v2/policy/sets/${setId}/rules/${encodeURIComponent(r.ruleId)}/restore`,
                                      { method: 'POST' },
                                    );
                                    push('Rule restored');
                                    await load({ soft: true });
                                  } catch (err) {
                                    push(err.message || 'Restore failed');
                                  }
                                }}
                              >
                                Restore
                              </Button>
                            ) : null}
                          </li>
                        ))}
                      </ul>
                    ) : null}
                  </div>
                ) : null}
              </div>
            ) : null}
          </section>
        </div>
      )}

      {rulesJsonOpen ? (
        <Modal open title="Edit draft rules as JSON" onClose={() => setRulesJsonOpen(false)}>
          <p className="mb-2 text-xs text-sv-muted">
            Paste or edit the full rules array. Save runs the full validator; invalid rules cannot become
            ACCEPTED. Removing a rule from the array soft-deletes it (same audit as Delete).
          </p>
          <textarea
            className="h-80 w-full rounded border border-sv-border bg-sv-bg p-2 font-mono text-xs"
            value={rulesJsonText}
            onChange={(e) => setRulesJsonText(e.target.value)}
            spellCheck={false}
          />
          <div className="mt-3 flex justify-end gap-2">
            <Button variant="ghost" onClick={() => setRulesJsonOpen(false)}>
              Cancel
            </Button>
            <Button
              disabled={busy}
              onClick={async () => {
                try {
                  const rules = JSON.parse(rulesJsonText);
                  if (!Array.isArray(rules)) throw new Error('Root must be a JSON array');
                  await apiJson(`/api/v2/policy/sets/${setId}/rules-json`, {
                    method: 'PUT',
                    body: JSON.stringify({ rules }),
                  });
                  push('Rules JSON saved');
                  setRulesJsonOpen(false);
                  await load({ soft: true });
                } catch (err) {
                  push(err.message || 'Save failed');
                }
              }}
            >
              Save
            </Button>
          </div>
        </Modal>
      ) : null}

      <ConflictPanel
        open={conflictOpen}
        onClose={() => setConflictOpen(false)}
        conflicts={set?.conflicts || []}
        canResolve={canWrite && set?.status === 'DRAFT'}
        push={push}
        onResolved={async () => {
          await load({ soft: true });
        }}
      />
    </div>
  );
}

function OriginBadge({ origin }) {
  if (origin === 'LLM_MOCK') return <Badge tone="warn">LLM(mock)</Badge>;
  if (origin === 'MANUAL') return <Badge tone="accent">MANUAL</Badge>;
  if (origin === 'DEMO_FIXTURE') return <Badge tone="neutral">demo</Badge>;
  if (origin === 'LLM') return <Badge tone="accent">LLM</Badge>;
  return origin ? <Badge tone="neutral">{origin}</Badge> : null;
}

OriginBadge.propTypes = { origin: PropTypes.string };

function RuleCard({ rule, selected, onSelect, rejected, onEditToFix, editLabel, onShowInDocument }) {
  const dup = (rule.warnings || []).find((w) => w.code === 'POSSIBLE_DUPLICATE');
  const rejectReason = rejected
    ? (rule.warnings || []).map((w) => `${w.code}: ${w.message}`).join(' · ')
    : '';
  const docName = rule.documentTitle || rule.source?.documentTitle;
  const clauseRef = rule.source?.clauseRef;
  const quote = rule.source?.quote;
  return (
    <div
      className={`w-full rounded border px-3 py-2 text-left text-sm ${
        selected
          ? 'border-sv-accent bg-sv-accent/10'
          : rejected
            ? 'border-risk-critical/40 bg-sv-bg'
            : 'border-sv-border hover:bg-sv-elevated/40'
      }`}
    >
      <button type="button" className="w-full text-left" onClick={onSelect}>
        <div className="flex flex-wrap items-center gap-2">
          <span className="font-medium text-sv-fg">{rule.title}</span>
          <Badge tone={SEVERITY_TONE[rule.severity] || 'neutral'}>{rule.severity}</Badge>
          <Badge tone={STATUS_TONE[rule.status] || 'neutral'}>{rule.status}</Badge>
          <OriginBadge origin={rule.origin} />
          {dup ? <Badge tone="warn">possible duplicate</Badge> : null}
          {(rule.warnings || []).some((w) => w.code === 'CONFLICT') ? (
            <Badge tone="danger">conflict</Badge>
          ) : null}
          {rule.source?.kind === 'ADMIN_DIRECTIVE' || rule.source?.adminDirective ? (
            <Badge tone="warn">admin directive</Badge>
          ) : null}
        </div>
        {(docName || clauseRef) ? (
          <p className="mt-1 text-xs text-sv-muted">
            {docName ? <span className="font-medium text-sv-fg">{docName}</span> : null}
            {docName && clauseRef ? ' · ' : null}
            {clauseRef ? <span>{clauseRef}</span> : null}
          </p>
        ) : null}
        <p className="mt-1 text-xs text-sv-muted">{rule.plainEnglish}</p>
        {rejectReason ? (
          <p className="mt-1 text-xs text-risk-critical">{rejectReason}</p>
        ) : null}
        {quote ? (
          <p className="mt-1 border-l-2 border-sv-border pl-2 text-xs italic text-sv-muted">
            “{quote}”
          </p>
        ) : null}
      </button>
      <div className="mt-2 flex flex-wrap gap-2">
        {onShowInDocument && rule.source?.documentId ? (
          <Button className="px-2 py-1 text-xs" variant="ghost" onClick={onShowInDocument}>
            Show in document
          </Button>
        ) : null}
        {onEditToFix ? (
          <Button className="px-2 py-1 text-xs" onClick={onEditToFix}>
            {editLabel || 'Edit to fix'}
          </Button>
        ) : null}
      </div>
    </div>
  );
}

RuleCard.propTypes = {
  rule: PropTypes.object.isRequired,
  selected: PropTypes.bool,
  onSelect: PropTypes.func,
  rejected: PropTypes.bool,
  onEditToFix: PropTypes.func,
  editLabel: PropTypes.string,
  onShowInDocument: PropTypes.func,
};

/** Rejected cards worth showing: clause ref + non-empty condition. */
function isMeaningfulRejection(rule) {
  const ref = rule?.source?.clauseRef;
  if (!ref || String(ref).trim() === '' || String(ref).toLowerCase() === 'null') return false;
  return hasCondition(rule?.when);
}

function isUnrepairableRejection(rule) {
  const ref = rule?.source?.clauseRef;
  const noRef = !ref || String(ref).trim() === '' || String(ref).toLowerCase() === 'null';
  return noRef && !hasCondition(rule?.when);
}

function hasCondition(when) {
  if (!when || typeof when !== 'object') return false;
  if (Object.keys(when).length === 0) return false;
  return collectLeaves(when).length > 0;
}

function validateWhenObject(when, facts) {
  if (!when || typeof when !== 'object') return 'Condition is required';
  const leaves = collectLeaves(when);
  if (leaves.length === 0) return 'Condition has no leaf facts';
  const nonDisc = new Set([
    'session.durationSec',
    'time.hourLocal',
    'time.isBusinessHours',
    'voice.syntheticScore',
    'voice.speakerMismatch',
    'relationship.daysSinceLastContact',
    'relationship.isFirstContact',
  ]);
  if (leaves.length === 1 && nonDisc.has(leaves[0].fact)) {
    return 'A single non-discriminating fact is not allowed';
  }
  const byPath = Object.fromEntries((facts || []).map((f) => [f.path, f]));
  for (const leaf of leaves) {
    if (leaf.op !== 'EXISTS') {
      if (leaf.value === undefined || leaf.value === null || leaf.value === '') {
        return `Empty value for ${leaf.fact}`;
      }
    }
    const def = byPath[leaf.fact];
    if (!def) return `Unknown fact: ${leaf.fact}`;
    if (def.enum && def.enum.length && typeof leaf.value === 'string' && !def.enum.includes(leaf.value)) {
      return `${leaf.fact} value "${leaf.value}" is not in catalogue enum`;
    }
    if (def.type === 'boolean' && typeof leaf.value !== 'boolean' && leaf.op !== 'EXISTS') {
      return `${leaf.fact} requires a boolean`;
    }
    if (def.type === 'number' && leaf.op !== 'EXISTS' && typeof leaf.value !== 'number') {
      return `${leaf.fact} requires a number`;
    }
  }
  return '';
}

function validateEditPayload(raw, facts) {
  try {
    const parsed = JSON.parse(raw);
    return validateWhenObject(parsed.when, facts);
  } catch {
    return 'Invalid JSON';
  }
}

function collectLeaves(node, out = []) {
  if (!node || typeof node !== 'object') return out;
  if (Array.isArray(node.all)) node.all.forEach((c) => collectLeaves(c, out));
  else if (Array.isArray(node.any)) node.any.forEach((c) => collectLeaves(c, out));
  else if (node.not) collectLeaves(node.not, out);
  else if (node.fact) out.push(node);
  return out;
}

function EmptyRulesState({ diagnostics, canRerun, rerunBusy, onRerun }) {
  const summary =
    diagnostics?.summary ||
    diagnostics?.error ||
    'No rules were produced for this policy set.';
  const counts = diagnostics?.counts || {};
  return (
    <div className="rounded border border-dashed border-sv-border bg-sv-elevated/30 px-4 py-6">
      <p className="text-sm font-medium text-sv-fg">No rules in this draft</p>
      <p className="mt-2 text-sm text-sv-muted">{summary}</p>
      {diagnostics?.compilationStatus === 'COMPLETED_NO_RULES' ? (
        <p className="mt-2 text-xs text-risk-watch">
          Compile finished as COMPLETED_NO_RULES — the draft was left empty on purpose so you can
          re-run failed chunks or adjust the source documents.
        </p>
      ) : null}
      {Object.keys(counts).length > 0 ? (
        <ul className="mt-3 grid gap-1 text-xs text-sv-muted sm:grid-cols-2">
          {Object.entries(counts).map(([k, v]) =>
            Number(v) > 0 ? (
              <li key={k}>
                {k}: {v}
              </li>
            ) : null,
          )}
        </ul>
      ) : null}
      {canRerun ? (
        <div className="mt-4">
          <Button className="px-3 py-1 text-xs" disabled={rerunBusy} onClick={onRerun}>
            {rerunBusy ? 'Re-running…' : 'Re-run compile'}
          </Button>
          <p className="mt-2 text-xs text-sv-muted">
            Re-processes only chunks that timed out, failed schema checks, returned empty, or were
            rejected by validation.
          </p>
        </div>
      ) : null}
    </div>
  );
}

EmptyRulesState.propTypes = {
  diagnostics: PropTypes.object,
  canRerun: PropTypes.bool,
  rerunBusy: PropTypes.bool,
  onRerun: PropTypes.func,
};

function highlightQuote(text, quote) {
  if (!quote || !text) return text;
  const norm = (s) =>
    s
      .toLowerCase()
      .replace(/[\u2018\u2019\u201A\u201B`]/g, "'")
      .replace(/[\u201C\u201D\u201E\u201F«»]/g, '"')
      .replace(/[\u2010-\u2015\u2212]/g, '-')
      .replace(/\s+/g, ' ')
      .trim();
  const nText = norm(text);
  const nQuote = norm(quote);
  if (!nQuote) return text;
  const nIdx = nText.indexOf(nQuote);
  if (nIdx < 0) {
    // Fallback: first 40 chars raw
    const idx = text.toLowerCase().indexOf(quote.toLowerCase().slice(0, Math.min(40, quote.length)));
    if (idx < 0) return text;
    return (
      <>
        {text.slice(0, idx)}
        <mark className="bg-risk-watch/30 text-sv-fg">
          {text.slice(idx, Math.min(idx + quote.length, text.length))}
        </mark>
        {text.slice(Math.min(idx + quote.length, text.length))}
      </>
    );
  }
  // Map normalised index back to original by walking both strings
  let ti = 0;
  let ni = 0;
  let start = -1;
  let end = -1;
  const targetEnd = nIdx + nQuote.length;
  while (ti < text.length && ni <= targetEnd) {
    const ch = text[ti];
    const isWs = /\s/.test(ch);
    if (ni === nIdx && start < 0) start = ti;
    if (isWs) {
      // skip run of whitespace in both
      while (ti < text.length && /\s/.test(text[ti])) ti += 1;
      if (ni < nText.length && nText[ni] === ' ') ni += 1;
      continue;
    }
    ni += 1;
    ti += 1;
    if (ni === targetEnd) {
      end = ti;
      break;
    }
  }
  if (start < 0 || end < 0) return text;
  return (
    <>
      {text.slice(0, start)}
      <mark className="bg-risk-watch/30 text-sv-fg">{text.slice(start, end)}</mark>
      {text.slice(end)}
    </>
  );
}

function ConditionBuilder({ facts, when, onApply, error }) {
  const firstLeaf = useMemo(() => {
    const leaves = collectLeaves(when);
    return leaves[0] || null;
  }, [when]);

  const [fact, setFact] = useState(firstLeaf?.fact || facts[0]?.path || '');
  const [op, setOp] = useState(firstLeaf?.op || 'EQ');
  const [value, setValue] = useState(
    firstLeaf?.value === undefined || firstLeaf?.value === null
      ? ''
      : Array.isArray(firstLeaf.value)
        ? firstLeaf.value.join(',')
        : String(firstLeaf.value)
  );
  const [group, setGroup] = useState(
    when?.all ? 'all' : when?.any ? 'any' : 'leaf'
  );

  useEffect(() => {
    if (!firstLeaf) return;
    setFact(firstLeaf.fact || facts[0]?.path || '');
    setOp(firstLeaf.op || 'EQ');
    setValue(
      firstLeaf.value === undefined || firstLeaf.value === null
        ? ''
        : Array.isArray(firstLeaf.value)
          ? firstLeaf.value.join(',')
          : String(firstLeaf.value)
    );
    setGroup(when?.all ? 'all' : when?.any ? 'any' : 'leaf');
  }, [firstLeaf, facts, when]);

  const selectedFact = (facts || []).find((f) => f.path === fact);
  const valueInvalid =
    op !== 'EXISTS' &&
    (value === '' ||
      (selectedFact?.enum?.length &&
        selectedFact.type === 'string' &&
        !selectedFact.enum.includes(value) &&
        op !== 'IN' &&
        op !== 'NOT_IN'));

  function parseValue(raw, operator, factDef) {
    if (operator === 'EXISTS') return undefined;
    if (factDef?.type === 'boolean') {
      if (raw === 'true') return true;
      if (raw === 'false') return false;
      return raw;
    }
    if (raw === 'true') return true;
    if (raw === 'false') return false;
    if (/^-?\d+(\.\d+)?$/.test(raw)) return Number(raw);
    if (operator === 'IN' || operator === 'NOT_IN') {
      return raw.split(',').map((s) => s.trim()).filter(Boolean);
    }
    return raw;
  }

  return (
    <div className="space-y-2 border-t border-sv-border pt-3">
      <p className="text-xs font-medium text-sv-fg">Condition builder</p>
      <p className="text-xs text-sv-muted">
        Current: {when ? JSON.stringify(when) : '—'}
      </p>
      {error ? <p className="text-xs text-risk-critical">{error}</p> : null}
      <div className="flex flex-wrap gap-2">
        <select
          className="rounded border border-sv-border bg-sv-bg px-2 py-1 text-xs"
          value={group}
          onChange={(e) => setGroup(e.target.value)}
        >
          <option value="leaf">Single fact</option>
          <option value="all">AND group (all)</option>
          <option value="any">OR group (any)</option>
        </select>
        <select
          className="rounded border border-sv-border bg-sv-bg px-2 py-1 text-xs"
          value={fact}
          onChange={(e) => {
            setFact(e.target.value);
            setValue('');
          }}
        >
          {facts.map((f) => (
            <option key={f.path} value={f.path}>
              {f.path}
            </option>
          ))}
        </select>
        <select
          className="rounded border border-sv-border bg-sv-bg px-2 py-1 text-xs"
          value={op}
          onChange={(e) => setOp(e.target.value)}
        >
          {['EQ', 'NE', 'GT', 'GTE', 'LT', 'LTE', 'IN', 'NOT_IN', 'CONTAINS', 'EXISTS'].map((o) => (
            <option key={o} value={o}>
              {o}
            </option>
          ))}
        </select>
        {selectedFact?.enum?.length && op !== 'EXISTS' && op !== 'IN' && op !== 'NOT_IN' ? (
          <select
            className="rounded border border-sv-border bg-sv-bg px-2 py-1 text-xs"
            value={value}
            onChange={(e) => setValue(e.target.value)}
          >
            <option value="">Select…</option>
            {selectedFact.enum.map((v) => (
              <option key={v} value={v}>
                {v}
              </option>
            ))}
          </select>
        ) : selectedFact?.type === 'boolean' && op !== 'EXISTS' ? (
          <select
            className="rounded border border-sv-border bg-sv-bg px-2 py-1 text-xs"
            value={value}
            onChange={(e) => setValue(e.target.value)}
          >
            <option value="">Select…</option>
            <option value="true">true</option>
            <option value="false">false</option>
          </select>
        ) : (
          <input
            className="rounded border border-sv-border bg-sv-bg px-2 py-1 text-xs"
            value={value}
            onChange={(e) => setValue(e.target.value)}
            placeholder={op === 'IN' || op === 'NOT_IN' ? 'a,b,c' : 'value'}
            disabled={op === 'EXISTS'}
          />
        )}
        <Button
          className="px-2 py-1 text-xs"
          disabled={valueInvalid}
          title={valueInvalid ? 'Enter a valid value for this fact' : undefined}
          onClick={() => {
            const leaf = { fact, op, value: parseValue(value, op, selectedFact) };
            if (op === 'EXISTS') delete leaf.value;
            if (group === 'leaf') {
              onApply(leaf);
            } else {
              const key = group === 'all' ? 'all' : 'any';
              const existing = when && when[key] ? when[key] : [];
              onApply({ [key]: [...existing, leaf] });
            }
          }}
        >
          {group === 'leaf' ? 'Apply leaf' : 'Add to group'}
        </Button>
      </div>
      {valueInvalid ? (
        <p className="text-xs text-risk-critical">Value is empty or not valid for this fact</p>
      ) : null}
    </div>
  );
}

ConditionBuilder.propTypes = {
  facts: PropTypes.array,
  when: PropTypes.object,
  onApply: PropTypes.func,
  error: PropTypes.string,
};

function KeywordsPanel({ setId, keywords, canWrite, onChanged }) {
  const { push } = useToast();
  const [term, setTerm] = useState('');
  const [category, setCategory] = useState('CUSTOM');

  return (
    <div className="border-t border-sv-border pt-3">
      <p className="text-xs font-medium text-sv-fg">Keywords</p>
      <ul className="mt-1 max-h-32 space-y-1 overflow-y-auto text-xs">
        {keywords.map((k) => (
          <li key={k.id} className="flex justify-between gap-2">
            <span>
              [{k.category}/{k.lang}] {k.term}
            </span>
            {canWrite ? (
              <button
                type="button"
                className="text-risk-critical"
                onClick={async () => {
                  try {
                    await apiJson(`/api/v2/policy/keywords/${k.id}`, { method: 'DELETE' });
                    onChanged();
                  } catch (err) {
                    push(err.message || 'Delete failed');
                  }
                }}
              >
                remove
              </button>
            ) : null}
          </li>
        ))}
      </ul>
      {canWrite ? (
        <div className="mt-2 flex flex-wrap gap-2">
          <input
            className="rounded border border-sv-border bg-sv-bg px-2 py-1 text-xs"
            value={term}
            onChange={(e) => setTerm(e.target.value)}
            placeholder="term"
          />
          <select
            className="rounded border border-sv-border bg-sv-bg px-2 py-1 text-xs"
            value={category}
            onChange={(e) => setCategory(e.target.value)}
          >
            {['URGENCY', 'SECRECY', 'AUTHORITY', 'PAYMENT', 'CREDENTIAL', 'CUSTOM'].map((c) => (
              <option key={c} value={c}>
                {c}
              </option>
            ))}
          </select>
          <Button
            className="px-2 py-1 text-xs"
            onClick={async () => {
              try {
                await apiJson(`/api/v2/policy/sets/${setId}/keywords`, {
                  method: 'POST',
                  body: JSON.stringify({ term, category, lang: 'en' }),
                });
                setTerm('');
                onChanged();
              } catch (err) {
                push(err.message || 'Add failed');
              }
            }}
          >
            Add
          </Button>
        </div>
      ) : null}
    </div>
  );
}

KeywordsPanel.propTypes = {
  setId: PropTypes.string,
  keywords: PropTypes.array,
  canWrite: PropTypes.bool,
  onChanged: PropTypes.func,
};
