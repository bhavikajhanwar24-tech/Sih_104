import { useEffect, useMemo, useState } from 'react';
import PropTypes from 'prop-types';
import { apiJson } from '@/services/api.js';
import { Badge, Button, Input, Modal } from '@/ui';

const MODALITIES = [
  { value: 'must', label: 'Must' },
  { value: 'must_not', label: 'Must not' },
  { value: 'requires_approval', label: 'Requires approval' },
  { value: 'threshold', label: 'Threshold' },
];

/**
 * Guided manual Add Rule — DOCUMENT CLAUSE or ADMIN DIRECTIVE.
 * Goes through the same POST /sets/{id}/rules pipeline as compile.
 */
export function AddRuleModal({
  open,
  onClose,
  setId,
  facts = [],
  documents = [],
  prefill = {},
  onCreated,
  push,
}) {
  const [sourceKind, setSourceKind] = useState(
    prefill.sourceKind === 'ADMIN_DIRECTIVE' ? 'ADMIN_DIRECTIVE' : 'DOCUMENT_CLAUSE'
  );
  const [documentId, setDocumentId] = useState(prefill.documentId || '');
  const [chunks, setChunks] = useState([]);
  const [chunkId, setChunkId] = useState(prefill.chunkId || '');
  const [selectedSentence, setSelectedSentence] = useState(prefill.quote || '');
  const [basis, setBasis] = useState('');
  const [reference, setReference] = useState('');
  const [fact, setFact] = useState(prefill.when?.all?.[0]?.fact || facts[0]?.path || 'ask.type');
  const [op, setOp] = useState(prefill.when?.all?.[0]?.op || 'EQ');
  const [value, setValue] = useState(
    prefill.when?.all?.[0]?.value != null ? String(prefill.when.all[0].value) : ''
  );
  const [amountFact, setAmountFact] = useState('ask.amountInr');
  const [amountOp, setAmountOp] = useState('GT');
  const [amountValue, setAmountValue] = useState('');
  const [minLevel, setMinLevel] = useState(2);
  const [modality, setModality] = useState('must_not');
  const [keywords, setKeywords] = useState('');
  const [title, setTitle] = useState('');
  const [advanced, setAdvanced] = useState(false);
  const [rawJson, setRawJson] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    if (!open) return;
    setSourceKind(prefill.sourceKind === 'ADMIN_DIRECTIVE' ? 'ADMIN_DIRECTIVE' : 'DOCUMENT_CLAUSE');
    setDocumentId(prefill.documentId || '');
    setChunkId(prefill.chunkId || '');
    setSelectedSentence(prefill.quote || '');
    setError('');
  }, [open, prefill]);

  useEffect(() => {
    if (!documentId || sourceKind !== 'DOCUMENT_CLAUSE') {
      setChunks([]);
      return;
    }
    let cancelled = false;
    (async () => {
      try {
        const data = await apiJson(`/api/v2/policies/documents/${documentId}/chunks`);
        if (!cancelled) {
          setChunks(data.items || data.chunks || []);
        }
      } catch {
        if (!cancelled) setChunks([]);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [documentId, sourceKind]);

  const selectedChunk = useMemo(
    () => chunks.find((c) => c.id === chunkId) || null,
    [chunks, chunkId]
  );

  const sentences = useMemo(() => {
    const text = selectedChunk?.text || '';
    return text
      .split(/(?<=[.!?])\s+/)
      .map((s) => s.trim())
      .filter((s) => s.length > 8);
  }, [selectedChunk]);

  const whenPreview = useMemo(() => {
    const leaves = [{ fact, op, value: parseValue(value, op, facts.find((f) => f.path === fact)) }];
    if (amountValue !== '' && !Number.isNaN(Number(amountValue))) {
      leaves.push({
        fact: amountFact,
        op: amountOp,
        value: Number(amountValue),
      });
    }
    return { all: leaves };
  }, [fact, op, value, amountFact, amountOp, amountValue, facts]);

  const plainEnglish = useMemo(() => {
    const parts = whenPreview.all.map((l) => `${l.fact} ${l.op} ${JSON.stringify(l.value)}`);
    return `IF ${parts.join(' AND ')} THEN minLevel ≥ ${minLevel} (${modality})`;
  }, [whenPreview, minLevel, modality]);

  const firesWhen = useMemo(() => `fires when ${plainEnglish.replace(/^IF /, '').split(' THEN')[0]}`, [plainEnglish]);
  const doesNotFire = useMemo(
    () => `does NOT fire when the condition above is false`,
    []
  );

  async function submit() {
    setError('');
    setBusy(true);
    try {
      let body;
      if (advanced && rawJson.trim()) {
        body = JSON.parse(rawJson);
      } else if (sourceKind === 'ADMIN_DIRECTIVE') {
        if (basis.trim().length < 15) {
          throw new Error('Basis / who decided must be at least 15 characters');
        }
        body = {
          when: whenPreview,
          then: { minLevel, scoreBoost: 0.2, reasonCode: 'POLICY_MANUAL' },
          modality,
          keywords: keywords
            .split(',')
            .map((s) => s.trim())
            .filter(Boolean),
          title: title || undefined,
          source: {
            kind: 'ADMIN_DIRECTIVE',
            basis: basis.trim(),
            reference: reference.trim() || undefined,
          },
          status: 'ACCEPTED',
        };
      } else {
        if (!documentId || !chunkId) {
          throw new Error('Pick a document and click a sentence in the clause');
        }
        if (!selectedSentence) {
          throw new Error('Click a sentence in the clause to set the quote');
        }
        body = {
          when: whenPreview,
          then: { minLevel, scoreBoost: 0.2, reasonCode: 'POLICY_MANUAL' },
          modality,
          keywords: keywords
            .split(',')
            .map((s) => s.trim())
            .filter(Boolean),
          title: title || undefined,
          source: {
            kind: 'DOCUMENT_CLAUSE',
            documentId,
            chunkId,
            chunkIds: [chunkId],
            quote: selectedSentence.slice(0, 200),
            clauseRef: prefill.clauseRef || undefined,
          },
          status: 'ACCEPTED',
        };
      }
      const updated = await apiJson(`/api/v2/policy/sets/${setId}/rules`, {
        method: 'POST',
        body: JSON.stringify(body),
      });
      const conflicts = updated.newConflicts || [];
      push(
        conflicts.length
          ? `Manual rule added — ${conflicts.length} conflict(s) to resolve`
          : updated.draftMessage || 'Manual rule added (ACCEPTED in draft)'
      );
      onCreated?.(updated);
      onClose();
    } catch (err) {
      setError(err.message || 'Could not add rule');
      push(err.message || 'Could not add rule');
    } finally {
      setBusy(false);
    }
  }

  if (!open) return null;

  return (
    <Modal open={open} onClose={onClose} title="Add rule" wide>
      <div className="max-h-[80vh] space-y-4 overflow-y-auto text-sm">
        <div className="flex flex-wrap gap-2">
          <Button
            variant={sourceKind === 'DOCUMENT_CLAUSE' ? 'primary' : 'ghost'}
            className="px-2 py-1 text-xs"
            onClick={() => setSourceKind('DOCUMENT_CLAUSE')}
          >
            Document clause
          </Button>
          <Button
            variant={sourceKind === 'ADMIN_DIRECTIVE' ? 'primary' : 'ghost'}
            className="px-2 py-1 text-xs"
            onClick={() => setSourceKind('ADMIN_DIRECTIVE')}
          >
            Admin directive
          </Button>
          <label className="ml-auto flex items-center gap-2 text-xs text-sv-muted">
            <input
              type="checkbox"
              checked={advanced}
              onChange={(e) => setAdvanced(e.target.checked)}
            />
            Advanced JSON
          </label>
        </div>

        {advanced ? (
          <textarea
            className="h-48 w-full rounded border border-sv-border bg-sv-bg p-2 font-mono text-xs"
            value={rawJson}
            onChange={(e) => setRawJson(e.target.value)}
            placeholder='{"when":{...},"then":{"minLevel":2},"source":{...}}'
          />
        ) : (
          <>
            {sourceKind === 'DOCUMENT_CLAUSE' ? (
              <div className="space-y-2">
                <label className="block text-xs font-medium">Document</label>
                <select
                  className="w-full rounded border border-sv-border bg-sv-bg px-2 py-1.5 text-xs"
                  value={documentId}
                  onChange={(e) => {
                    setDocumentId(e.target.value);
                    setChunkId('');
                    setSelectedSentence('');
                  }}
                >
                  <option value="">Select document…</option>
                  {documents.map((d) => (
                    <option key={d.id} value={d.id}>
                      {d.title || d.id}
                    </option>
                  ))}
                </select>
                {chunks.length > 0 ? (
                  <div className="space-y-1">
                    <label className="block text-xs font-medium">Clause (click a sentence)</label>
                    <select
                      className="w-full rounded border border-sv-border bg-sv-bg px-2 py-1.5 text-xs"
                      value={chunkId}
                      onChange={(e) => {
                        setChunkId(e.target.value);
                        setSelectedSentence('');
                      }}
                    >
                      <option value="">Select clause…</option>
                      {chunks.map((c) => (
                        <option key={c.id} value={c.id}>
                          {(c.headingPath || c.clauseRef || `para ${c.ordinal + 1}`).slice(0, 80)}
                        </option>
                      ))}
                    </select>
                    {selectedChunk ? (
                      <div className="max-h-40 space-y-1 overflow-y-auto rounded border border-sv-border p-2">
                        {sentences.map((s) => (
                          <button
                            key={s.slice(0, 40)}
                            type="button"
                            className={`block w-full rounded px-2 py-1 text-left text-xs ${
                              selectedSentence === s
                                ? 'bg-sv-accent/20 text-sv-fg'
                                : 'hover:bg-sv-muted/10'
                            }`}
                            onClick={() => setSelectedSentence(s)}
                          >
                            {s}
                          </button>
                        ))}
                      </div>
                    ) : null}
                    {selectedSentence ? (
                      <p className="text-xs text-sv-muted">
                        Quote: “{selectedSentence.slice(0, 120)}
                        {selectedSentence.length > 120 ? '…' : ''}”
                      </p>
                    ) : null}
                  </div>
                ) : null}
              </div>
            ) : (
              <div className="space-y-2">
                <Badge tone="warn">admin directive</Badge>
                <label className="block text-xs font-medium">Basis / who decided (min 15 chars)</label>
                <textarea
                  className="h-20 w-full rounded border border-sv-border bg-sv-bg p-2 text-xs"
                  value={basis}
                  onChange={(e) => setBasis(e.target.value)}
                  placeholder="e.g. Compliance committee decision 2026-03-12 — raise wire threshold"
                />
                <label className="block text-xs font-medium">Reference (optional)</label>
                <Input value={reference} onChange={(e) => setReference(e.target.value)} />
              </div>
            )}

            <div className="space-y-2 border-t border-sv-border pt-3">
              <p className="text-xs font-medium">When</p>
              <div className="flex flex-wrap gap-2">
                <select
                  className="rounded border border-sv-border bg-sv-bg px-2 py-1 text-xs"
                  value={fact}
                  onChange={(e) => setFact(e.target.value)}
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
                  {['EQ', 'NE', 'GT', 'GTE', 'LT', 'LTE', 'IN'].map((o) => (
                    <option key={o} value={o}>
                      {o}
                    </option>
                  ))}
                </select>
                {facts.find((f) => f.path === fact)?.enum?.length ? (
                  <select
                    className="rounded border border-sv-border bg-sv-bg px-2 py-1 text-xs"
                    value={value}
                    onChange={(e) => setValue(e.target.value)}
                  >
                    {facts
                      .find((f) => f.path === fact)
                      .enum.map((v) => (
                        <option key={v} value={v}>
                          {v}
                        </option>
                      ))}
                  </select>
                ) : (
                  <Input
                    className="w-40 text-xs"
                    value={value}
                    onChange={(e) => setValue(e.target.value)}
                  />
                )}
              </div>
              <div className="flex flex-wrap items-center gap-2">
                <span className="text-xs text-sv-muted">+ amount (optional)</span>
                <select
                  className="rounded border border-sv-border bg-sv-bg px-2 py-1 text-xs"
                  value={amountOp}
                  onChange={(e) => setAmountOp(e.target.value)}
                >
                  {['GT', 'GTE', 'LT', 'LTE', 'EQ'].map((o) => (
                    <option key={o} value={o}>
                      {o}
                    </option>
                  ))}
                </select>
                <Input
                  className="w-32 text-xs"
                  placeholder="e.g. 1000000"
                  value={amountValue}
                  onChange={(e) => setAmountValue(e.target.value)}
                />
                <span className="text-xs text-sv-muted">{amountFact}</span>
              </div>
              <p className="text-xs text-sv-muted">{firesWhen}</p>
              <p className="text-xs text-sv-muted">{doesNotFire}</p>
            </div>

            <div className="space-y-2 border-t border-sv-border pt-3">
              <p className="text-xs font-medium">Then</p>
              <div className="flex flex-wrap gap-3">
                <label className="text-xs">
                  minLevel{' '}
                  <select
                    className="rounded border border-sv-border bg-sv-bg px-2 py-1"
                    value={minLevel}
                    onChange={(e) => setMinLevel(Number(e.target.value))}
                  >
                    {[1, 2, 3, 4].map((n) => (
                      <option key={n} value={n}>
                        {n}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="text-xs">
                  modality{' '}
                  <select
                    className="rounded border border-sv-border bg-sv-bg px-2 py-1"
                    value={modality}
                    onChange={(e) => setModality(e.target.value)}
                  >
                    {MODALITIES.map((m) => (
                      <option key={m.value} value={m.value}>
                        {m.label}
                      </option>
                    ))}
                  </select>
                </label>
              </div>
              <label className="block text-xs">
                Keywords (comma-separated)
                <Input value={keywords} onChange={(e) => setKeywords(e.target.value)} />
              </label>
              <label className="block text-xs">
                Title (auto if blank)
                <Input value={title} onChange={(e) => setTitle(e.target.value)} />
              </label>
              <p className="text-xs text-sv-muted">{plainEnglish}</p>
            </div>
          </>
        )}

        {error ? <p className="text-xs text-risk-critical">{error}</p> : null}

        <div className="flex justify-end gap-2 border-t border-sv-border pt-3">
          <Button variant="ghost" onClick={onClose} disabled={busy}>
            Cancel
          </Button>
          <Button onClick={submit} disabled={busy}>
            {busy ? 'Saving…' : 'Add rule'}
          </Button>
        </div>
      </div>
    </Modal>
  );
}

function parseValue(raw, operator, factDef) {
  if (factDef?.type === 'boolean') {
    if (raw === 'true') return true;
    if (raw === 'false') return false;
  }
  if (/^-?\d+(\.\d+)?$/.test(String(raw))) return Number(raw);
  if (operator === 'IN' || operator === 'NOT_IN') {
    return String(raw)
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean);
  }
  return raw;
}

AddRuleModal.propTypes = {
  open: PropTypes.bool,
  onClose: PropTypes.func,
  setId: PropTypes.string,
  facts: PropTypes.array,
  documents: PropTypes.array,
  prefill: PropTypes.object,
  onCreated: PropTypes.func,
  push: PropTypes.func,
};
