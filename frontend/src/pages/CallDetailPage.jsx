import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, Navigate, useParams } from 'react-router-dom';
import { FamilyRadar } from '@/components/FamilyRadar.jsx';
import { useAuth } from '@/context/AuthContext.jsx';
import { apiFetch, apiJson } from '@/services/api.js';
import { Badge, Button, EmptyState, Select, Table } from '@/ui';
import { useToast } from '@/ui/Toast.jsx';

/**
 * F12 — Call Detail: explainability from GET /api/v2/sessions/{id}?lang=.
 */
export function CallDetailPage() {
  const { id } = useParams();
  const { hasPermission, username } = useAuth();
  const { push } = useToast();
  const [data, setData] = useState(/** @type {any | null} */ (null));
  const [loading, setLoading] = useState(true);
  const [pdfBusy, setPdfBusy] = useState(false);
  const [reviewBusy, setReviewBusy] = useState(false);
  const [lang, setLang] = useState('en');

  const canRead = hasPermission('calls:read');
  const canAct = hasPermission('calls:act');

  const load = useCallback(async () => {
    if (!id) return;
    setLoading(true);
    try {
      const body = await apiJson(
        `/api/v2/sessions/${encodeURIComponent(id)}?lang=${encodeURIComponent(lang)}`,
        { skipErrorToast: true },
      );
      setData(body);
    } catch (err) {
      setData(null);
      push(err instanceof Error ? err.message : 'Failed to load session');
    } finally {
      setLoading(false);
    }
  }, [id, lang, push]);

  useEffect(() => {
    if (!canRead) return;
    void load();
  }, [canRead, load]);

  const meta = data?.metadata || {};
  const ticks = Array.isArray(data?.ticks) ? data.ticks : [];
  const reasons = Array.isArray(data?.reasons) ? data.reasons : [];
  const actions = Array.isArray(data?.actions) ? data.actions : [];
  const availability = data?.availability || {};
  const reviewStatus = meta.reviewStatus || 'UNREVIEWED';

  const frame = useMemo(() => buildSyntheticFrame(ticks, reasons), [ticks, reasons]);

  async function downloadPdf() {
    if (!id) return;
    setPdfBusy(true);
    try {
      const by = username || 'analyst';
      const res = await apiFetch(
        `/api/v2/sessions/${encodeURIComponent(id)}/dossier.pdf?generatedBy=${encodeURIComponent(by)}`,
      );
      if (!res.ok) throw new Error(`PDF HTTP ${res.status}`);
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `sentinelvoice-dossier-${id}.pdf`;
      a.click();
      URL.revokeObjectURL(url);
      push('Dossier PDF downloaded');
    } catch (err) {
      push(err instanceof Error ? err.message : 'PDF download failed');
    } finally {
      setPdfBusy(false);
    }
  }

  async function setReview(status) {
    if (!id || !canAct) return;
    setReviewBusy(true);
    try {
      await apiJson(`/api/v2/sessions/${encodeURIComponent(id)}/review`, {
        method: 'POST',
        body: JSON.stringify({ status }),
      });
      push(
        status === 'FALSE_POSITIVE'
          ? 'Marked false positive'
          : status === 'CONFIRMED_FRAUD'
            ? 'Marked confirmed fraud'
            : 'Review cleared',
      );
      await load();
    } catch (err) {
      push(err instanceof Error ? err.message : 'Review update failed');
    } finally {
      setReviewBusy(false);
    }
  }

  if (!canRead) {
    return <Navigate to="/app" replace />;
  }

  if (loading && !data) {
    return <p className="p-6 text-sm text-sv-muted">Loading session…</p>;
  }

  if (!data) {
    return (
      <div className="p-6">
        <EmptyState title="Session not found" description="Return to Call History and pick another row." />
        <Link to="/app/history" className="mt-4 inline-block text-sm text-sv-accent">
          ← Call History
        </Link>
      </div>
    );
  }

  const actionColumns = [
    { key: 'level', header: 'Level' },
    { key: 'action', header: 'Action' },
    { key: 'status', header: 'Status' },
    {
      key: 'startedAt',
      header: 'Started',
      render: (row) => (
        <span className="font-mono text-[11px] text-sv-muted">
          {row.startedAt ? new Date(row.startedAt).toLocaleString() : '—'}
        </span>
      ),
    },
  ];

  return (
    <div className="space-y-6 p-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <Link to="/app/history" className="text-xs text-sv-accent underline-offset-2 hover:underline">
            ← Call History
          </Link>
          <h1 className="mt-2 text-xl font-semibold text-sv-fg">
            {meta.callerName || 'Caller'} → {meta.calleeName || 'Callee'}
          </h1>
          <p className="mt-1 font-mono text-[11px] text-sv-muted">
            {meta.svSessionUuid || meta.id}
            {meta.startedAt ? ` · ${new Date(meta.startedAt).toLocaleString()}` : ''}
            {meta.durationMs != null ? ` · ${formatDuration(meta.durationMs)}` : ''}
          </p>
          <div className="mt-2 flex flex-wrap gap-2">
            {meta.peakLevel ? (
              <Badge tone="warn">
                Peak {meta.peakLevel}
                {typeof meta.peakScore === 'number' ? ` · ${meta.peakScore.toFixed(2)}` : ''}
              </Badge>
            ) : null}
            <Badge tone="neutral">{meta.finalOutcome || '—'}</Badge>
            <Badge
              tone={
                reviewStatus === 'CONFIRMED_FRAUD'
                  ? 'danger'
                  : reviewStatus === 'FALSE_POSITIVE'
                    ? 'success'
                    : 'neutral'
              }
            >
              {reviewStatus}
            </Badge>
          </div>
        </div>
        <div className="flex flex-wrap items-end gap-2">
          <Select
            label="Why language"
            className="w-36"
            value={lang}
            onChange={(e) => setLang(e.target.value)}
          >
            <option value="en">English</option>
            <option value="hi">हिन्दी</option>
          </Select>
          <Button variant="secondary" disabled={pdfBusy} onClick={() => void downloadPdf()}>
            {pdfBusy ? 'Generating…' : 'Download dossier PDF'}
          </Button>
          <Button
            variant="ghost"
            disabled={!canAct || reviewBusy || reviewStatus === 'FALSE_POSITIVE'}
            onClick={() => void setReview('FALSE_POSITIVE')}
            title={!canAct ? 'Requires calls:act' : 'Mark this alert as a false positive'}
          >
            Mark FP
          </Button>
          <Button
            variant="ghost"
            disabled={!canAct || reviewBusy || reviewStatus === 'CONFIRMED_FRAUD'}
            onClick={() => void setReview('CONFIRMED_FRAUD')}
            title={!canAct ? 'Requires calls:act' : 'Mark as confirmed fraud'}
          >
            Confirmed
          </Button>
          {reviewStatus !== 'UNREVIEWED' && canAct ? (
            <Button
              variant="ghost"
              disabled={reviewBusy}
              onClick={() => void setReview('UNREVIEWED')}
            >
              Clear review
            </Button>
          ) : null}
        </div>
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        <section className="rounded-lg border border-sv-border bg-sv-panel p-4">
          <h2 className="text-sm font-semibold text-sv-fg">Risk timeline</h2>
          <p className="mt-0.5 text-xs text-sv-muted">
            {availability.ticksNote || 'Sampled ticks from session_ticks'}
          </p>
          <TickTimeline ticks={ticks} actions={actions} />
          <TimelineLegend />
        </section>
        <section className="rounded-lg border border-sv-border bg-sv-panel p-4">
          <h2 className="text-sm font-semibold text-sv-fg">Family radar</h2>
          <p className="mt-0.5 text-xs text-sv-muted">Latest tick family_scores</p>
          <div className="mx-auto h-52 max-w-xs">
            <FamilyRadar frame={frame} />
          </div>
        </section>
      </div>

      <section className="rounded-lg border border-sv-border bg-sv-panel p-4">
        <h2 className="text-sm font-semibold text-sv-fg">More info · LLM thinking</h2>
        <p className="mt-0.5 text-xs text-sv-muted">
          Stage B / Ollama rationale for ACTIVE Live Rules on this call
        </p>
        {(() => {
          const cats = data?.extractionsLatest?.categories || {};
          const thinking =
            (typeof cats.llmThinking === 'string' && cats.llmThinking.trim()) ||
            (typeof data?.llmThinking === 'string' && data.llmThinking.trim()) ||
            '';
          return (
            <p className="mt-3 min-h-[4rem] whitespace-pre-wrap text-sm leading-relaxed text-sv-fg">
              {thinking || 'No LLM judgment was retained for this call.'}
            </p>
          );
        })()}
      </section>

      <section className="rounded-lg border border-sv-border bg-sv-panel p-4">
        <h2 className="text-sm font-semibold text-sv-fg">Keywords triggered</h2>
        <p className="mt-0.5 text-xs text-sv-muted">
          ACTIVE lexicon terms heard on this call (from your Live Rules / PDF keywords)
        </p>
        {(() => {
          const fromExtract =
            data?.extractionsLatest?.categories?.matchedKeywords || [];
          const uniq = [];
          if (Array.isArray(fromExtract)) {
            uniq.push(...fromExtract);
          }
          // Flatten matchedKeywords from any nested category maps on reasons' evidence
          for (const r of reasons) {
            const mk = r?.evidence?.matchedKeywords || r?.evidence?.keywords;
            if (Array.isArray(mk)) uniq.push(...mk);
          }
          const terms = [...new Set(uniq.map(String).filter(Boolean))];
          if (!terms.length) {
            return (
              <p className="mt-3 text-sm text-sv-muted">
                No ACTIVE keywords matched yet. Speak terms from Policies → Keywords / Live Rules.
              </p>
            );
          }
          return (
            <div className="mt-3 flex flex-wrap gap-2">
              {terms.map((t) => (
                <Badge key={t} tone="warning">
                  {t}
                </Badge>
              ))}
            </div>
          );
        })()}
      </section>

      <section className="rounded-lg border border-sv-border bg-sv-panel p-4">
        <h2 className="text-sm font-semibold text-sv-fg">Rules broken</h2>
        <p className="mt-0.5 text-xs text-sv-muted">
          Compiled Live Rules if/else that fired (plus keyword→rule links)
        </p>
        {(() => {
          const ruleReasons = reasons.filter(
            (r) =>
              r.code === 'POLICY_RULE_FIRED' ||
              r.code === 'CREDENTIAL_REQUEST' ||
              r.code === 'SECRECY_REQUESTED' ||
              r.code === 'URGENCY' ||
              r.code === 'AUTHORITY_INVOCATION' ||
              r.code === 'PROMPT_INJECTION_ATTEMPT' ||
              r.ruleId,
          );
          if (!ruleReasons.length) {
            return (
              <p className="mt-3 text-sm text-sv-muted">
                No policy/keyword rules broken on retained ticks. Acoustic-only reasons are listed
                under Why below.
              </p>
            );
          }
          return (
            <ul className="mt-3 space-y-2">
              {ruleReasons.map((r) => (
                <li
                  key={`rule-${r.seq}-${r.code}-${r.ruleId || ''}`}
                  className="rounded border border-sv-border bg-sv-bg px-3 py-2"
                >
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone="warning">{r.code}</Badge>
                    {r.ruleId ? (
                      <span className="font-mono text-[11px] text-sv-muted">rule {r.ruleId}</span>
                    ) : null}
                  </div>
                  <p className="mt-1 text-sm text-sv-fg">{r.title || r.detail}</p>
                  <ClauseCitation sourceClause={r.sourceClause} ruleId={r.ruleId} />
                </li>
              ))}
            </ul>
          );
        })()}
      </section>

      <section className="rounded-lg border border-sv-border bg-sv-panel p-4">
        <h2 className="text-sm font-semibold text-sv-fg">Why</h2>
        <p className="mt-0.5 text-xs text-sv-muted">
          Typed reason codes from session_reasons (titles via reasons*.properties · lang={lang})
        </p>
        {reasons.length === 0 ? (
          <p className="mt-3 text-sm text-sv-muted">
            No reasons retained for this session. Softphone lab calls without FeatureFrame ingest
            will not populate this panel.
          </p>
        ) : (
          <ul className="mt-3 space-y-2">
            {reasons.map((r) => (
              <li
                key={`${r.seq}-${r.code}`}
                className="rounded border border-sv-border bg-sv-bg px-3 py-2"
              >
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-mono text-xs text-sv-accent">{r.code}</span>
                  {typeof r.contribution === 'number' ? (
                    <Badge tone="neutral">contrib {r.contribution.toFixed(2)}</Badge>
                  ) : null}
                  {r.severity ? <Badge tone="neutral">{r.severity}</Badge> : null}
                  {r.family ? (
                    <span className="text-[11px] uppercase tracking-wide text-sv-muted">{r.family}</span>
                  ) : null}
                  {r.ruleId ? (
                    <span className="font-mono text-[11px] text-sv-muted">rule {r.ruleId}</span>
                  ) : null}
                  {r.policyVersion != null ? (
                    <span className="font-mono text-[11px] text-sv-muted">policy v{r.policyVersion}</span>
                  ) : null}
                </div>
                <p className="mt-1 text-sm text-sv-fg">{r.title || r.detail}</p>
                {r.detail && r.title ? (
                  <p className="mt-0.5 text-xs text-sv-muted">{r.detail}</p>
                ) : null}
                <ClauseCitation sourceClause={r.sourceClause} ruleId={r.ruleId} />
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="space-y-2">
        <h2 className="text-sm font-semibold text-sv-fg">Actions</h2>
        {actions.length === 0 ? (
          <p className="text-sm text-sv-muted">
            {availability.actionsNote ||
              'Actions: not tracked — no response-plan steps recorded for this session.'}
          </p>
        ) : (
          <Table columns={actionColumns} rows={actions} rowKey={(r) => r.id || `${r.level}-${r.stepIndex}`} />
        )}
      </section>

      <section className="rounded-lg border border-sv-border bg-sv-panel p-4">
        <h2 className="text-sm font-semibold text-sv-fg">Linguistic extractions</h2>
        {data.extractionsLatest && Object.keys(data.extractionsLatest).length > 0 ? (
          <>
            <p className="mt-0.5 text-xs text-sv-muted">{availability.extractionsNote}</p>
            <pre className="mt-2 overflow-x-auto font-mono text-[11px] text-sv-muted">
              {JSON.stringify(data.extractionsLatest, null, 2)}
            </pre>
          </>
        ) : (
          <p className="mt-2 text-sm text-sv-muted">
            Extractions: not available — F11 linguistic enums were not retained for this call
            (media/ASR path may not have run).
          </p>
        )}
      </section>

      {data.versions ? (
        <p className="font-mono text-[11px] text-sv-muted">
          Snapshots — policy v{data.versions.policyVersion ?? '—'} · fusion v
          {data.versions.fusionVersion ?? '—'} · response v
          {data.versions.responsePlanVersion ?? '—'}
        </p>
      ) : null}
    </div>
  );
}

/** @param {{ sourceClause?: any, ruleId?: string }} props */
function ClauseCitation({ sourceClause, ruleId }) {
  if (!sourceClause || typeof sourceClause !== 'object') return null;
  const documentId = sourceClause.documentId || sourceClause.document_id;
  const clauseRef = sourceClause.clauseRef || sourceClause.clause_ref || sourceClause.title;
  const quote = sourceClause.quote;
  const chunkId = sourceClause.chunkId || sourceClause.chunk_id;

  if (!documentId && !clauseRef && !quote && !ruleId) return null;

  const params = new URLSearchParams({ tab: 'documents' });
  if (documentId) params.set('doc', String(documentId));
  if (clauseRef) params.set('clause', String(clauseRef));
  if (chunkId) params.set('chunk', String(chunkId));
  if (ruleId) params.set('rule', String(ruleId));

  return (
    <div className="mt-2 rounded border border-dashed border-sv-border bg-sv-panel/50 px-2 py-1.5 text-xs text-sv-muted">
      {clauseRef ? <div className="font-mono text-[11px] text-sv-fg">Clause {clauseRef}</div> : null}
      {quote ? <p className="mt-0.5 italic">&ldquo;{String(quote).slice(0, 240)}&rdquo;</p> : null}
      {documentId ? (
        <Link
          to={`/app/policies?${params}`}
          className="mt-1 inline-block text-sv-accent underline-offset-2 hover:underline"
        >
          Open cited policy document →
        </Link>
      ) : (
        <p className="mt-1 text-[11px]">
          No documentId on this citation — policy deep-link unavailable for this reason.
        </p>
      )}
    </div>
  );
}

/** @param {{ ticks: any[], actions?: any[] }} props */
function TickTimeline({ ticks, actions = [] }) {
  if (!ticks.length) {
    return <p className="mt-4 text-sm text-sv-muted">No ticks sampled.</p>;
  }
  const scores = ticks.map((t) => (typeof t.score === 'number' ? t.score : 0));
  const minT = ticks[0].tMs;
  const maxT = ticks[ticks.length - 1].tMs || minT + 1;
  const span = Math.max(1, maxT - minT);
  const w = 480;
  const h = 140;
  const pad = 10;
  const points = ticks
    .map((t, i) => {
      const x = pad + ((t.tMs - minT) / span) * (w - pad * 2);
      const y = pad + (1 - Math.min(1, Math.max(0, scores[i]))) * (h - pad * 2);
      return `${x},${y}`;
    })
    .join(' ');

  const bands = [
    { y: 0.35, color: '#86efac', label: 'L1' },
    { y: 0.55, color: '#fde68a', label: 'L2' },
    { y: 0.75, color: '#fdba74', label: 'L3' },
    { y: 0.9, color: '#fca5a5', label: 'L4+' },
  ];

  /** @type {{ x: number, kind: string, title: string }[]} */
  const markers = [];
  ticks.forEach((t) => {
    const x = pad + ((t.tMs - minT) / span) * (w - pad * 2);
    const rules = Array.isArray(t.firedRuleIds) ? t.firedRuleIds : [];
    if (rules.length) {
      markers.push({ x, kind: 'rule', title: `Rule ${rules[0]}` });
    }
    if (t.llmState && t.llmState !== 'MISSING') {
      markers.push({ x, kind: 'llm', title: `LLM ${t.llmState}` });
    }
  });
  actions.forEach((a) => {
    const tMs = a.tMs ?? a.startedAtMs;
    if (typeof tMs !== 'number') return;
    const x = pad + ((tMs - minT) / span) * (w - pad * 2);
    markers.push({ x, kind: 'action', title: a.action || a.actionKey || 'action' });
  });

  return (
    <svg
      className="mt-3 w-full"
      viewBox={`0 0 ${w} ${h}`}
      role="img"
      aria-label="Risk score over call"
    >
      <defs>
        <linearGradient id="levelBands" x1="0" y1="1" x2="0" y2="0">
          <stop offset="0%" stopColor="#86efac" stopOpacity="0.25" />
          <stop offset="35%" stopColor="#fde68a" stopOpacity="0.2" />
          <stop offset="55%" stopColor="#fdba74" stopOpacity="0.2" />
          <stop offset="75%" stopColor="#fca5a5" stopOpacity="0.25" />
          <stop offset="100%" stopColor="#f87171" stopOpacity="0.3" />
        </linearGradient>
      </defs>
      <rect x={pad} y={pad} width={w - pad * 2} height={h - pad * 2} fill="url(#levelBands)" />
      {bands.map((band) => {
        const y = pad + (1 - band.y) * (h - pad * 2);
        return (
          <line
            key={band.label}
            x1={pad}
            x2={w - pad}
            y1={y}
            y2={y}
            stroke={band.color}
            strokeWidth={1}
            opacity={0.8}
          />
        );
      })}
      <polyline
        fill="none"
        stroke="currentColor"
        className="text-sv-accent"
        strokeWidth={2}
        points={points}
      />
      {markers.map((m, i) => (
        <g key={`${m.kind}-${i}`}>
          <circle
            cx={m.x}
            cy={pad + 6}
            r={4}
            fill={m.kind === 'rule' ? '#2563eb' : m.kind === 'action' ? '#7c3aed' : '#059669'}
          >
            <title>{m.title}</title>
          </circle>
        </g>
      ))}
    </svg>
  );
}

function TimelineLegend() {
  return (
    <div className="mt-2 flex flex-wrap gap-3 text-[11px] text-sv-muted">
      <span>
        <span className="mr-1 inline-block h-2 w-2 rounded-full bg-blue-600" /> rule fired
      </span>
      <span>
        <span className="mr-1 inline-block h-2 w-2 rounded-full bg-violet-600" /> action
      </span>
      <span>
        <span className="mr-1 inline-block h-2 w-2 rounded-full bg-emerald-600" /> linguistic state
      </span>
    </div>
  );
}

/**
 * @param {any[]} ticks
 * @param {any[]} reasons
 */
function buildSyntheticFrame(ticks, reasons) {
  const latest = ticks.length ? ticks[ticks.length - 1] : null;
  const familyScores = latest?.familyScores || {};
  /** @type {Record<string, any>} */
  const families = {};
  for (const [k, v] of Object.entries(familyScores)) {
    if (v && typeof v === 'object') {
      families[k] = {
        score: typeof v.score === 'number' ? v.score : 0,
        weight: typeof v.weight === 'number' ? v.weight : 0,
        contribution: typeof v.contribution === 'number' ? v.contribution : 0,
        available: true,
      };
    }
  }
  const missing = Array.isArray(latest?.missingFamilies) ? latest.missingFamilies : [];
  for (const m of missing) {
    if (!families[m]) {
      families[m] = { score: 0, weight: 0, contribution: 0, available: false };
    }
  }
  return {
    families,
    smoothedRisk: latest?.score ?? 0,
    topReasons: reasons.map((r) => ({
      code: r.code,
      severity: r.severity,
      text: r.detail || r.title,
      family: r.family,
    })),
  };
}

/** @param {number | null | undefined} ms */
function formatDuration(ms) {
  if (ms == null || Number.isNaN(ms)) return '—';
  const s = Math.round(ms / 1000);
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  return `${m}m ${s % 60}s`;
}
