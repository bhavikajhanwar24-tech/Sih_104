import { useEffect, useState } from 'react';
import PropTypes from 'prop-types';
import { apiJson } from '@/services/api.js';
import { Badge, Button, Input, Modal } from '@/ui';

const TYPE_TONE = {
  THRESHOLD_CONFLICT: 'danger',
  LEVEL_CONFLICT: 'warn',
  DUPLICATE: 'accent',
  SUBSUMED: 'warn',
  OPPOSING: 'danger',
  ADVISORY: 'neutral',
};

/**
 * Side-by-side conflict resolution — Keep this / Discard that on each rule.
 */
export function ConflictPanel({
  open,
  onClose,
  conflicts = [],
  onResolved,
  push,
  canResolve = true,
}) {
  const [items, setItems] = useState(conflicts);
  const [activeId, setActiveId] = useState(conflicts[0]?.id || null);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    setItems(conflicts);
    if (conflicts.length && !conflicts.some((c) => c.id === activeId)) {
      setActiveId(conflicts[0].id);
    }
  }, [conflicts, activeId]);

  const openItems = items.filter((c) => c.status === 'OPEN');
  const active = items.find((c) => c.id === activeId) || openItems[0] || items[0] || null;

  async function keepRule(keepRuleId) {
    if (!active || !canResolve) return;
    setBusy(true);
    try {
      const result = await apiJson(`/api/v2/policy/conflicts/${active.id}/resolve`, {
        method: 'POST',
        body: JSON.stringify({
          keepRuleId,
          reason: reason.trim() || undefined,
        }),
      });
      push(result.message || `Kept ${keepRuleId}`);
      const remaining = (result.openConflicts || []).filter((c) => c.status === 'OPEN');
      setItems(remaining);
      if (remaining.length === 0) {
        setActiveId(null);
      } else if (!remaining.some((c) => c.id === activeId)) {
        setActiveId(remaining[0].id);
      }
      setReason('');
      onResolved?.(result);
    } catch (err) {
      push(err.message || 'Resolve failed');
    } finally {
      setBusy(false);
    }
  }

  async function resolve(resolution) {
    if (!active || !canResolve) return;
    if ((resolution === 'KEEP_BOTH' || resolution === 'DEFER') && reason.trim().length < 15) {
      push('Reason must be at least 15 characters');
      return;
    }
    setBusy(true);
    try {
      const result = await apiJson(`/api/v2/policy/conflicts/${active.id}/resolve`, {
        method: 'POST',
        body: JSON.stringify({ resolution, reason: reason.trim() || undefined }),
      });
      push(result.message || `Conflict resolved: ${resolution}`);
      const remaining = (result.openConflicts || []).filter((c) => c.status === 'OPEN');
      // For KEEP_BOTH / DEFER, refetch from result or drop current
      if (resolution === 'DEFER') {
        setItems((prev) =>
          prev.map((c) => (c.id === active.id ? { ...c, ...result } : c)),
        );
      } else {
        setItems(remaining.length ? remaining : items.filter((c) => c.id !== active.id && c.status === 'OPEN'));
        if (remaining.length) setActiveId(remaining[0].id);
        else setActiveId(null);
      }
      setReason('');
      onResolved?.(result);
    } catch (err) {
      push(err.message || 'Resolve failed');
    } finally {
      setBusy(false);
    }
  }

  if (!open) return null;

  return (
    <Modal open={open} onClose={onClose} title={`Conflicts (${openItems.length} open)`} wide>
      <div className="flex max-h-[80vh] flex-col gap-3 text-sm">
        {items.length === 0 ? (
          <p className="text-sv-muted">No open conflicts — you can submit the draft when ready.</p>
        ) : (
          <>
            <div className="flex flex-wrap gap-2">
              {items.map((c) => (
                <button
                  key={c.id}
                  type="button"
                  className={`rounded border px-2 py-1 text-xs ${
                    (active?.id || activeId) === c.id
                      ? 'border-sv-accent bg-sv-accent/10'
                      : 'border-sv-border'
                  }`}
                  onClick={() => setActiveId(c.id)}
                >
                  <Badge tone={TYPE_TONE[c.type] || 'neutral'}>{c.type}</Badge>
                  {c.advisory ? ' possible' : ''} · {c.status}
                </button>
              ))}
            </div>

            {active ? (
              <div className="space-y-3 overflow-y-auto">
                <div className="rounded border border-risk-alert/40 bg-risk-alert/10 px-3 py-2 text-xs">
                  <p className="font-medium">Why these conflict</p>
                  <p>{active.why || active.summary}</p>
                  {active.advisory ? (
                    <p className="mt-1 text-sv-muted">Advisory — never blocks submit.</p>
                  ) : null}
                </div>

                <div className="grid gap-3 md:grid-cols-2">
                  <RuleCard
                    label="Rule A"
                    rule={active.ruleA}
                    canKeep={canResolve && active.status === 'OPEN' && !active.advisory}
                    busy={busy}
                    onKeep={() => keepRule(active.ruleAId || active.ruleA?.ruleId)}
                  />
                  <RuleCard
                    label="Rule B"
                    rule={active.ruleB}
                    canKeep={canResolve && active.status === 'OPEN' && !active.advisory}
                    busy={busy}
                    onKeep={() => keepRule(active.ruleBId || active.ruleB?.ruleId)}
                  />
                </div>

                {canResolve && active.status === 'OPEN' ? (
                  <div className="space-y-2 border-t border-sv-border pt-3">
                    <p className="text-xs text-sv-muted">
                      Click <strong>Keep this</strong> on the rule you want. The other is soft-deleted
                      from the draft and its keywords are removed. Submit the draft to update live policy.
                    </p>
                    <label className="block text-xs">
                      Reason (required for Keep both / Defer, min 15 chars)
                      <Input value={reason} onChange={(e) => setReason(e.target.value)} />
                    </label>
                    <div className="flex flex-wrap gap-2">
                      <Button
                        className="px-2 py-1 text-xs"
                        variant="ghost"
                        disabled={busy}
                        onClick={() => resolve('KEEP_BOTH')}
                      >
                        Keep both
                      </Button>
                      <Button
                        className="px-2 py-1 text-xs"
                        variant="ghost"
                        disabled={busy}
                        onClick={() => resolve('DEFER')}
                      >
                        Defer
                      </Button>
                    </div>
                  </div>
                ) : active.status !== 'OPEN' ? (
                  <p className="text-xs text-sv-muted">
                    Resolution: {active.resolution || '—'}
                    {active.reason ? ` — ${active.reason}` : ''}
                  </p>
                ) : null}
              </div>
            ) : null}
          </>
        )}
        <div className="flex justify-end">
          <Button variant="ghost" onClick={onClose}>
            Close
          </Button>
        </div>
      </div>
    </Modal>
  );
}

function RuleCard({ label, rule, canKeep, busy, onKeep }) {
  if (!rule) {
    return (
      <div className="rounded border border-sv-border p-3 text-xs text-sv-muted">{label}: —</div>
    );
  }
  const source = rule.source || {};
  const admin = source.kind === 'ADMIN_DIRECTIVE' || source.adminDirective;
  const role = rule.conflictRole;
  return (
    <div className="flex flex-col rounded border border-sv-border p-3 text-xs">
      <div className="mb-1 flex flex-wrap items-center gap-1">
        <span className="font-medium">{label}</span>
        {role === 'NEW' ? <Badge tone="accent">NEW</Badge> : null}
        {role === 'LIVE' ? <Badge tone="warn">LIVE</Badge> : null}
        {role === 'EXISTING' ? <Badge tone="neutral">EXISTING</Badge> : null}
        {rule.origin === 'MANUAL' ? <Badge tone="accent">MANUAL</Badge> : null}
        {admin ? <Badge tone="warn">admin directive</Badge> : null}
        <Badge tone="neutral">{rule.status || '—'}</Badge>
      </div>
      <p className="font-medium text-sv-fg">{rule.title || rule.ruleId}</p>
      <p className="mt-1 text-sv-muted">
        {rule.documentTitle || source.documentTitle || (admin ? 'No document' : 'Document')}
        {source.clauseRef ? ` · ${source.clauseRef}` : ''}
      </p>
      {source.quote ? (
        <p className="mt-1 italic text-sv-muted">“{String(source.quote).slice(0, 160)}”</p>
      ) : null}
      {admin && source.basis ? (
        <p className="mt-1 text-sv-muted">Basis: {String(source.basis).slice(0, 160)}</p>
      ) : null}
      <p className="mt-2">{rule.plainEnglish || rule.firesWhen || '—'}</p>
      <p className="text-sv-muted">
        Level {rule.then?.minLevel ?? '—'} · {rule.origin || '—'}
      </p>
      {canKeep ? (
        <Button className="mt-3 px-2 py-1 text-xs" disabled={busy} onClick={onKeep}>
          Keep this
        </Button>
      ) : null}
    </div>
  );
}

RuleCard.propTypes = {
  label: PropTypes.string,
  rule: PropTypes.object,
  canKeep: PropTypes.bool,
  busy: PropTypes.bool,
  onKeep: PropTypes.func,
};

ConflictPanel.propTypes = {
  open: PropTypes.bool,
  onClose: PropTypes.func,
  conflicts: PropTypes.array,
  onResolved: PropTypes.func,
  push: PropTypes.func,
  canResolve: PropTypes.bool,
};
