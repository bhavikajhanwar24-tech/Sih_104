import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, Navigate, useSearchParams } from 'react-router-dom';
import PropTypes from 'prop-types';
import { useAuth } from '@/context/AuthContext.jsx';
import { apiFetch, apiJson } from '@/services/api.js';
import { ConflictPanel } from '@/components/policy/ConflictPanel.jsx';
import { Badge, Button, Input, Modal, Table, Tabs } from '@/ui';
import { useToast } from '@/ui/Toast.jsx';

const DOC_TABS = [
  { id: 'documents', label: 'Documents' },
  { id: 'archived', label: 'Archived' },
  { id: 'live-rules', label: 'Live Rules' },
  { id: 'rules', label: 'Rules' },
  { id: 'simulate', label: 'Simulate' },
  { id: 'keywords', label: 'Keywords' },
  { id: 'versions', label: 'Versions' },
  { id: 'approvals', label: 'Approvals' },
];

const STATUS_TONE = {
  UPLOADED: 'accent',
  EXTRACTED: 'success',
  FAILED: 'danger',
  ARCHIVED: 'neutral',
};

const STATUS_LABEL = {
  UPLOADED: 'Extracting',
  EXTRACTED: 'Ready',
  FAILED: 'Failed',
  ARCHIVED: 'Archived',
};

function formatBytes(n) {
  if (n == null) return '—';
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / (1024 * 1024)).toFixed(1)} MB`;
}

function formatDate(iso) {
  if (!iso) return '—';
  try {
    return new Date(iso).toLocaleString();
  } catch {
    return iso;
  }
}

/**
 * F5 documents + F6 rules / versions / approvals.
 */
export function PoliciesPage() {
  const { hasPermission } = useAuth();
  const { push } = useToast();
  const [searchParams, setSearchParams] = useSearchParams();
  const tab = searchParams.get('tab') || 'documents';
  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(true);
  const [uploading, setUploading] = useState(false);
  const [dragOver, setDragOver] = useState(false);
  const [viewer, setViewer] = useState(null);
  const [chunks, setChunks] = useState([]);
  const [chunksLoading, setChunksLoading] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState(null);
  const [deleteBusy, setDeleteBusy] = useState(false);
  const [sets, setSets] = useState([]);
  const [compilations, setCompilations] = useState([]);
  const [compileBusy, setCompileBusy] = useState(false);
  const [selectedDocs, setSelectedDocs] = useState([]);
  const [maxDocs, setMaxDocs] = useState(1);
  const [engineStatus, setEngineStatus] = useState(null);
  const fileRef = useRef(null);
  const pollRef = useRef(null);
  const compileBusyRef = useRef(false);
  const compilationsRef = useRef([]);
  compileBusyRef.current = compileBusy;
  compilationsRef.current = compilations;

  const canWrite = hasPermission('policies:write');
  const canApprove = hasPermission('policies:approve');
  const canDownload = hasPermission('policies:write') || hasPermission('audit:read');
  const archivedTab = tab === 'archived';
  const docsTab = tab === 'documents' || tab === 'archived';

  const setTab = (id) => {
    setSearchParams(id === 'documents' ? {} : { tab: id });
  };

  const load = useCallback(async () => {
    try {
      const tasks = [];
      if (docsTab) {
        const q = archivedTab ? 'archived=true' : 'includeArchived=false';
        tasks.push(
          apiJson(`/api/v2/policies/documents?${q}`).then((data) => {
            setItems(data.items || []);
          }),
        );
      }
      if (docsTab && !archivedTab) {
        tasks.push(
          apiJson('/api/v2/policy/compile-limits', { skipErrorToast: true })
            .catch(() => ({ maxDocumentsPerCompile: 5 }))
            .then((limits) => {
              if (limits.maxDocumentsPerCompile) setMaxDocs(limits.maxDocumentsPerCompile);
            }),
        );
      }
      if (
        tab === 'rules' ||
        tab === 'versions' ||
        tab === 'approvals' ||
        tab === 'keywords' ||
        tab === 'simulate' ||
        tab === 'live-rules'
      ) {
        tasks.push(
          Promise.all([
            apiJson('/api/v2/policy/sets', { skipErrorToast: true }).catch(() => ({ items: [] })),
            apiJson('/api/v2/policy/compilations', { skipErrorToast: true }).catch(() => ({
              items: [],
            })),
            apiJson('/api/v2/policy/compile-limits', { skipErrorToast: true }).catch(() => ({
              maxDocumentsPerCompile: 5,
            })),
          ]).then(([s, c, limits]) => {
            setSets(s.items || []);
            setCompilations(c.items || []);
            if (limits.maxDocumentsPerCompile) setMaxDocs(limits.maxDocumentsPerCompile);
          }),
        );
      }
      // Engine status only where it is shown — skip on pure document browsing.
      if (tab === 'documents' || tab === 'rules' || tab === 'live-rules' || tab === 'versions') {
        tasks.push(
          apiJson('/api/v2/policy/engine/status', { skipErrorToast: true })
            .catch(() => null)
            .then((eng) => {
              if (eng) setEngineStatus(eng);
            }),
        );
      }
      await Promise.all(tasks);
    } catch (err) {
      push(err.message || 'Failed to load');
    } finally {
      setLoading(false);
    }
  }, [push, archivedTab, docsTab, tab]);

  const reloadEngine = useCallback(async () => {
    const eng = await apiJson('/api/v2/policy/engine/reload', { method: 'POST' });
    setEngineStatus(eng);
    return eng;
  }, []);

  useEffect(() => {
    if (!hasPermission('policies:read')) return undefined;
    setLoading(true);
    load();
    pollRef.current = setInterval(() => {
      if (document.visibilityState === 'hidden') return;
      const busy = compileBusyRef.current;
      const comps = compilationsRef.current || [];
      const inFlight = busy || comps.some((c) => c.status === 'QUEUED' || c.status === 'RUNNING');
      if (!inFlight) return;
      load();
    }, 2500);
    return () => {
      if (pollRef.current) clearInterval(pollRef.current);
    };
  }, [hasPermission, load]);

  if (!hasPermission('policies:read')) {
    return <Navigate to="/app" replace />;
  }

  async function uploadFile(file) {
    if (!file || !canWrite) return;
    setUploading(true);
    try {
      const form = new FormData();
      form.append('file', file);
      form.append('docType', 'POLICY');
      form.append('title', file.name.replace(/\.[^.]+$/, ''));
      const res = await apiFetch('/api/v2/policies/documents', {
        method: 'POST',
        body: form,
        skipErrorToast: true,
      });
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error(err.message || `Upload failed (${res.status})`);
      }
      push('Document uploaded — extraction started');
      setTab('documents');
      await load();
    } catch (err) {
      push(err.message || 'Upload failed');
    } finally {
      setUploading(false);
    }
  }

  async function openChunks(doc) {
    setViewer(doc);
    setChunks([]);
    setChunksLoading(true);
    try {
      const data = await apiJson(`/api/v2/policies/documents/${doc.id}/chunks`);
      setChunks(data.items || []);
    } catch (err) {
      push(err.message || 'Failed to load chunks');
    } finally {
      setChunksLoading(false);
    }
  }

  async function downloadDoc(doc) {
    try {
      const res = await apiFetch(`/api/v2/policies/documents/${doc.id}/download`);
      if (!res.ok) throw new Error('Download failed');
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = doc.originalFilename || 'document';
      a.click();
      URL.revokeObjectURL(url);
    } catch (err) {
      push(err.message || 'Download failed');
    }
  }

  async function archiveDoc(doc) {
    if (!canWrite) return;
    try {
      await apiJson(`/api/v2/policies/documents/${doc.id}`, {
        method: 'DELETE',
        skipErrorToast: true,
      });
      push('Document archived — open the Archived tab to restore or delete permanently');
      await load();
    } catch (err) {
      push(err.message || 'Archive failed');
    }
  }

  async function restoreDoc(doc) {
    if (!canWrite) return;
    try {
      await apiJson(`/api/v2/policies/documents/${doc.id}/restore`, {
        method: 'POST',
        skipErrorToast: true,
      });
      push('Document restored');
      setTab('documents');
    } catch (err) {
      push(err.message || 'Restore failed');
    }
  }

  async function confirmPermanentDelete() {
    if (!deleteTarget?.doc) return;
    setDeleteBusy(true);
    try {
      const res = await apiFetch(
        `/api/v2/policies/documents/${deleteTarget.doc.id}/permanent-delete`,
        { method: 'POST', skipErrorToast: true },
      );
      const body = await res.json().catch(() => ({}));
      if (res.status === 409 && body.code === 'CITED_BY_RULES') {
        setDeleteTarget((prev) =>
          prev ? { ...prev, blockingRules: body.blockingRules || [] } : prev,
        );
        push(body.message || 'Blocked by citing rules');
        return;
      }
      if (res.status === 409) {
        throw new Error(body.message || 'Archive the document first');
      }
      if (!res.ok) {
        throw new Error(body.message || body.error || `Delete failed (${res.status})`);
      }
      push('Document permanently deleted');
      setDeleteTarget(null);
      await load();
    } catch (err) {
      push(err.message || 'Delete failed');
    } finally {
      setDeleteBusy(false);
    }
  }

  async function startCompile(mode) {
    if (!canWrite || selectedDocs.length === 0) return;
    setCompileBusy(true);
    try {
      const result = await apiJson('/api/v2/policy/compilations', {
        method: 'POST',
        body: JSON.stringify({ documentIds: selectedDocs, mode }),
      });
      push(`Compilation ${result.id || ''} started`);
      setTab('rules');
      await load();
    } catch (err) {
      push(err.message || 'Compile failed');
    } finally {
      setCompileBusy(false);
    }
  }

  async function resumeCompile(id) {
    if (!canWrite) return;
    try {
      await apiJson(`/api/v2/policy/compilations/${id}/resume`, { method: 'POST' });
      push('Compilation resumed');
      await load();
    } catch (err) {
      push(err.message || 'Resume failed');
    }
  }

  async function rerunFailedCompile(id) {
    if (!canWrite) return;
    try {
      await apiJson(`/api/v2/policy/compilations/${id}/rerun-failed`, { method: 'POST' });
      push('Re-running failed chunks');
      await load();
    } catch (err) {
      push(err.message || 'Re-run failed');
    }
  }

  async function cancelCompile(id) {
    if (!canWrite) return;
    try {
      await apiJson(`/api/v2/policy/compilations/${id}/cancel`, { method: 'POST' });
      push('Compilation cancelled');
      await load();
    } catch (err) {
      push(err.message || 'Cancel failed');
    }
  }

  async function deleteDraft(id) {
    if (!canWrite) return;
    try {
      await apiJson(`/api/v2/policy/sets/${id}`, { method: 'DELETE' });
      push('Draft deleted');
      await load();
    } catch (err) {
      push(err.message || 'Delete failed');
    }
  }

  const readyDocs = useMemo(
    () => items.filter((d) => d.status === 'EXTRACTED'),
    [items],
  );

  const columns = useMemo(
    () => [
      {
        key: 'title',
        header: 'Title',
        render: (row) => (
          <span
            className={`inline-flex items-center gap-2 ${
              row.status === 'ARCHIVED' ? 'text-sv-muted opacity-80' : ''
            }`}
          >
            {row.hasInjectionFlags ? (
              <span className="text-risk-watch" title="Prompt-injection phrases flagged" aria-label="Prompt-injection warning">
                ⚠
              </span>
            ) : null}
            <span>{row.title}</span>
          </span>
        ),
      },
      {
        key: 'status',
        header: 'Status',
        render: (row) => (
          <span className="inline-flex flex-col gap-1">
            <span className="inline-flex flex-wrap items-center gap-1">
              <Badge tone={STATUS_TONE[row.status] || 'neutral'}>
                {STATUS_LABEL[row.status] || row.status}
              </Badge>
              {row.hasInjectionFlags ? <Badge tone="warn">injection</Badge> : null}
            </span>
            {row.status === 'FAILED' && row.extractionError ? (
              <span className="max-w-xs text-xs text-risk-critical" title={row.extractionError}>
                {row.extractionError}
              </span>
            ) : null}
          </span>
        ),
      },
      {
        key: 'sizeBytes',
        header: 'Size',
        render: (row) => formatBytes(row.sizeBytes),
      },
      {
        key: 'uploadedAt',
        header: 'Uploaded',
        render: (row) => formatDate(row.uploadedAt),
      },
      {
        key: 'actions',
        header: 'Actions',
        render: (row) => (
          <div className="flex max-w-[28rem] flex-wrap gap-1">
            <Button className="px-2 py-1 text-xs" variant="ghost" onClick={() => openChunks(row)}>
              View text
            </Button>
            {!archivedTab && canDownload && (
              <Button className="px-2 py-1 text-xs" variant="ghost" onClick={() => downloadDoc(row)}>
                Download
              </Button>
            )}
            {!archivedTab && canWrite && (row.status === 'UPLOADED' || row.status === 'FAILED' || row.status === 'EXTRACTED') && (
              <Button
                className="px-2 py-1 text-xs"
                variant="ghost"
                onClick={async () => {
                  try {
                    await apiJson(`/api/v2/policies/documents/${row.id}/reextract`, {
                      method: 'POST',
                      skipErrorToast: true,
                    });
                    push('Extraction re-queued');
                    await load();
                  } catch (err) {
                    push(err.message || 'Re-extract failed');
                  }
                }}
              >
                {row.status === 'EXTRACTED' || row.status === 'FAILED' ? 'Re-extract' : 'Retry extract'}
              </Button>
            )}
            {!archivedTab && canWrite && (
              <Button className="px-2 py-1 text-xs" variant="ghost" onClick={() => archiveDoc(row)}>
                Archive
              </Button>
            )}
            {archivedTab && canWrite && (
              <>
                <Button className="px-2 py-1 text-xs" variant="ghost" onClick={() => restoreDoc(row)}>
                  Restore
                </Button>
                <Button
                  className="px-2 py-1 text-xs"
                  variant="danger"
                  onClick={() =>
                    setDeleteTarget({ doc: row, blockingRules: null, confirmText: '' })
                  }
                >
                  Delete permanently
                </Button>
              </>
            )}
          </div>
        ),
      },
    ],
    [canWrite, canDownload, archivedTab],
  );

  const flagged = !archivedTab && docsTab ? items.filter((d) => d.hasInjectionFlags) : [];
  const pendingSets = sets.filter((s) => s.status === 'PENDING_APPROVAL');
  const draftSets = sets.filter((s) => s.status === 'DRAFT');
  const shownCompiles = compilations
    .filter((c) =>
      ['QUEUED', 'RUNNING', 'FAILED', 'COMPLETED_NO_RULES', 'COMPLETED'].includes(c.status),
    )
    .slice(0, 12);

  return (
    <div className="mx-auto flex w-full max-w-full flex-col gap-4 overflow-x-hidden p-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold text-sv-fg">Policies</h1>
          <p className="mt-1 text-sm text-sv-muted">
            Documents, LLM-assisted rule compile, human review, and versioned approval.
          </p>
        </div>
        <EngineStatusChip status={engineStatus} />
      </div>

      <Tabs tabs={DOC_TABS} value={tab} onChange={setTab} />

      {docsTab ? (
        <>
          {flagged.length > 0 && (
            <div
              role="alert"
              className="rounded border border-risk-watch/40 bg-risk-watch/10 px-4 py-3 text-sm text-sv-fg"
            >
              <strong className="font-medium">Prompt-injection phrases flagged</strong>
              {' — '}
              {flagged.length === 1
                ? '1 document contains'
                : `${flagged.length} documents contain`}{' '}
              phrases that look like prompt-injection. Chunks will be treated as untrusted data;
              content was not deleted.
            </div>
          )}

          {canWrite && !archivedTab && (
            <div
              className={`rounded border border-dashed px-6 py-10 text-center transition ${
                dragOver ? 'border-sv-accent bg-sv-accent/5' : 'border-sv-border bg-sv-elevated/40'
              }`}
              onDragOver={(e) => {
                e.preventDefault();
                setDragOver(true);
              }}
              onDragLeave={() => setDragOver(false)}
              onDrop={(e) => {
                e.preventDefault();
                setDragOver(false);
                const f = e.dataTransfer.files?.[0];
                if (f) uploadFile(f);
              }}
            >
              <p className="text-sm text-sv-fg">Drag and drop PDF, DOCX, TXT, or MD (max 15 MB)</p>
              <p className="mt-1 text-xs text-sv-muted">
                Type is verified by content sniffing, not extension alone.
              </p>
              <div className="mt-4">
                <input
                  ref={fileRef}
                  type="file"
                  accept=".pdf,.docx,.txt,.md,application/pdf,text/plain,text/markdown"
                  className="hidden"
                  onChange={(e) => {
                    const f = e.target.files?.[0];
                    if (f) uploadFile(f);
                    e.target.value = '';
                  }}
                />
                <Button disabled={uploading} onClick={() => fileRef.current?.click()}>
                  {uploading ? 'Uploading…' : 'Choose file'}
                </Button>
              </div>
            </div>
          )}

          {canWrite && !archivedTab && readyDocs.length > 0 ? (
            <div className="rounded border border-sv-border bg-sv-elevated/40 p-4">
              <p className="text-sm font-medium text-sv-fg">Compile rules</p>
              <p className="mt-1 text-xs text-sv-muted">
                Select up to {maxDocs} extracted documents, then run FULL or INCREMENTAL compile.
                New versions <strong>keep</strong> every live ACTIVE rule and append newly extracted
                ones — resolve clashes in Conflicts (you choose which to keep).
                (TENANT_ADMIN).
              </p>
              <ul className="mt-3 max-h-40 space-y-1 overflow-y-auto text-sm">
                {readyDocs.map((d) => (
                  <li key={d.id} className="flex items-center gap-2">
                    <input
                      type="checkbox"
                      checked={selectedDocs.includes(d.id)}
                      disabled={
                        !selectedDocs.includes(d.id) && selectedDocs.length >= maxDocs
                      }
                      onChange={(e) => {
                        setSelectedDocs((prev) => {
                          if (e.target.checked) {
                            if (prev.length >= maxDocs) return prev;
                            return [...prev, d.id];
                          }
                          return prev.filter((id) => id !== d.id);
                        });
                      }}
                    />
                    <span>{d.title}</span>
                  </li>
                ))}
              </ul>
              <div className="mt-3 flex flex-wrap gap-2">
                <Button
                  disabled={compileBusy || selectedDocs.length === 0}
                  onClick={() => startCompile('FULL')}
                >
                  {compileBusy ? 'Starting…' : 'Compile (FULL)'}
                </Button>
                <Button
                  variant="ghost"
                  disabled={compileBusy || selectedDocs.length === 0}
                  onClick={() => startCompile('INCREMENTAL')}
                >
                  Incremental
                </Button>
              </div>
            </div>
          ) : null}

          {loading ? (
            <p className="text-sm text-sv-muted">Loading documents…</p>
          ) : items.length === 0 ? (
            <p className="text-sm text-sv-muted">
              {archivedTab ? 'No archived documents.' : 'No policy documents yet.'}
            </p>
          ) : (
            <Table columns={columns} rows={items} />
          )}
        </>
      ) : null}

      {tab === 'live-rules' ? (
        <LiveRulesTab
          canWrite={canWrite}
          engineStatus={engineStatus}
          onEngineReload={reloadEngine}
          onEngineStatus={setEngineStatus}
        />
      ) : null}

      {tab === 'rules' ? (
        <RulesTab
          loading={loading}
          draftSets={draftSets}
          sets={sets}
          compilations={shownCompiles}
          canWrite={canWrite}
          onResume={resumeCompile}
          onRerunFailed={rerunFailedCompile}
          onCancel={cancelCompile}
          onDeleteDraft={deleteDraft}
          onRefresh={load}
        />
      ) : null}

      {tab === 'simulate' ? <SimulateTab sets={sets} /> : null}

      {tab === 'keywords' ? (
        <KeywordsTab loading={loading} sets={sets} canWrite={canWrite} onRefresh={load} />
      ) : null}

      {tab === 'versions' ? (
        <VersionsTab loading={loading} sets={sets} canWrite={canWrite} onRefresh={load} />
      ) : null}

      {tab === 'approvals' ? (
        <ApprovalsTab
          loading={loading}
          pending={pendingSets}
          sets={sets}
          canApprove={canApprove}
          onRefresh={load}
        />
      ) : null}

      <ChunkViewerModal
        open={Boolean(viewer)}
        doc={viewer}
        chunks={chunks}
        loading={chunksLoading}
        onClose={() => setViewer(null)}
      />

      <PermanentDeleteModal
        state={deleteTarget}
        busy={deleteBusy}
        onClose={() => !deleteBusy && setDeleteTarget(null)}
        onConfirmText={(text) =>
          setDeleteTarget((prev) => (prev ? { ...prev, confirmText: text } : prev))
        }
        onSetBlocking={(rules) =>
          setDeleteTarget((prev) => (prev ? { ...prev, blockingRules: rules } : prev))
        }
        onConfirm={confirmPermanentDelete}
      />
    </div>
  );
}

function compileStatusTone(status) {
  if (status === 'FAILED') return 'danger';
  if (status === 'COMPLETED_NO_RULES') return 'warn';
  if (status === 'COMPLETED') return 'success';
  return 'accent';
}

function diagnosticSummary(c) {
  return (
    c?.diagnostics?.summary ||
    c?.progress?.summary ||
    null
  );
}

function progressCounts(progress) {
  if (!progress || typeof progress !== 'object') return null;
  const total = Number(progress.chunksTotal ?? 0);
  const done = Number(progress.chunksProcessed ?? progress.chunksDone ?? 0);
  return {
    chunks: total > 0 ? Math.min(done, total) : done || '—',
    proposed: progress.rulesProposed ?? '—',
    hallucinated:
      progress.rulesHallucinated ??
      progress.rulesRejectedHallucinated ??
      progress.rejectedHallucinated ??
      '—',
    total: progress.chunksTotal ?? '—',
  };
}

function RulesTab({
  loading,
  draftSets,
  sets,
  compilations,
  canWrite,
  onResume,
  onRerunFailed,
  onCancel,
  onDeleteDraft,
  onRefresh,
}) {
  const { push } = useToast();
  const [clauseText, setClauseText] = useState('');
  const [busy, setBusy] = useState(false);
  const [lastResult, setLastResult] = useState(null);
  const [conflictOpen, setConflictOpen] = useState(false);
  const [conflicts, setConflicts] = useState([]);

  async function submitRule() {
    if (!canWrite || !clauseText.trim()) return;
    setBusy(true);
    setLastResult(null);
    try {
      const draft = draftSets[0];
      const active = (sets || []).find((s) => s.status === 'ACTIVE');
      const body = { text: clauseText.trim() };
      if (draft?.id) body.setId = draft.id;
      else if (active?.id) body.setId = active.id;

      const data = await apiJson('/api/v2/policy/rules/from-text', {
        method: 'POST',
        body: JSON.stringify(body),
      });
      setLastResult(data);
      if (!data.ok) {
        push(data.reason || 'Could not extract a rule from that text');
        return;
      }
      const found = data.newConflicts || [];
      setConflicts(found);
      push(data.message || 'Rule added');
      setClauseText('');
      onRefresh?.();
      if (found.length > 0) {
        setConflictOpen(true);
      } else if (data.draftId) {
        // Optional: stay on tab; user can open draft
      }
    } catch (err) {
      push(err.message || 'Add rule failed');
    } finally {
      setBusy(false);
    }
  }

  if (loading && draftSets.length === 0 && sets.length === 0 && compilations.length === 0) {
    return <p className="text-sm text-sv-muted">Loading…</p>;
  }
  return (
    <div className="space-y-4">
      {canWrite ? (
        <div className="rounded border border-sv-border p-4">
          <p className="text-sm font-medium text-sv-fg">Add a rule</p>
          <p className="mt-1 text-xs text-sv-muted">
            Paste a policy clause. It is compiled into a rule, checked against live and draft rules for
            contradictions or duplicates, and keywords are extracted automatically.
          </p>
          <textarea
            className="mt-3 min-h-[100px] w-full rounded border border-sv-border bg-sv-elevated p-2 text-sm text-sv-fg"
            value={clauseText}
            onChange={(e) => setClauseText(e.target.value)}
            placeholder="e.g. Staff must not process wire transfers above INR 10,00,000 to unknown beneficiaries…"
          />
          <div className="mt-2 flex flex-wrap items-center gap-2">
            <Button disabled={busy || clauseText.trim().length < 15} onClick={submitRule}>
              {busy ? 'Checking…' : 'Add rule'}
            </Button>
            {lastResult?.draftId ? (
              <Link
                className="text-xs text-sv-accent underline"
                to={`/app/policies/review?set=${lastResult.draftId}`}
              >
                Open draft
              </Link>
            ) : null}
          </div>
          {lastResult?.ok === false ? (
            <p className="mt-2 text-xs text-risk-critical">
              {lastResult.reason || 'No enforceable rule found'}
            </p>
          ) : null}
          {lastResult?.ok && (lastResult.conflictCount || 0) > 0 ? (
            <p className="mt-2 text-xs text-risk-alert">
              {lastResult.conflictCount} conflict(s) with existing rules — choose which to keep.
            </p>
          ) : null}
        </div>
      ) : null}

      <ConflictPanel
        open={conflictOpen}
        onClose={() => setConflictOpen(false)}
        conflicts={conflicts}
        canResolve={canWrite}
        push={push}
        onResolved={async (result) => {
          onRefresh?.();
          const draftId = result?.draftId || lastResult?.draftId;
          if (draftId) {
            try {
              const s = await apiJson(`/api/v2/policy/sets/${draftId}`);
              const open = (s.conflicts || []).filter((c) => c.status === 'OPEN');
              setConflicts(open);
              if (open.length === 0) {
                setConflictOpen(false);
                push(result?.message || 'Conflict resolved — open the draft to review / submit');
              }
            } catch {
              /* ignore */
            }
          }
        }}
      />

      {compilations.length > 0 ? (
        <div className="rounded border border-sv-border p-4">
          <p className="text-sm font-medium text-sv-fg">Compilation progress</p>
          <ul className="mt-2 space-y-3 text-sm">
            {compilations.map((c) => {
              const p = progressCounts(c.progress);
              const summary = diagnosticSummary(c);
              const hasFailed = !!c.diagnostics?.hasFailedChunks;
              const suspects = (c.chunkResults || c.diagnostics?.chunkResults || []).filter(
                (r) => r.status === 'LLM_EMPTY_SUSPECT',
              );
              return (
                <li key={c.id} className="space-y-1 rounded border border-sv-border/60 px-3 py-2">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <span>
                      <Badge tone={compileStatusTone(c.status)}>{c.status}</Badge>{' '}
                      {c.mode} · chunks {p?.chunks}/{p?.total} · proposed {p?.proposed} ·
                      hallucinated {p?.hallucinated}
                    </span>
                    <div className="flex flex-wrap gap-2">
                      {canWrite && (c.status === 'QUEUED' || c.status === 'RUNNING') ? (
                        <Button
                          className="px-2 py-1 text-xs"
                          variant="ghost"
                          onClick={() => onCancel(c.id)}
                        >
                          Cancel
                        </Button>
                      ) : null}
                      {canWrite && c.status === 'FAILED' ? (
                        <Button className="px-2 py-1 text-xs" onClick={() => onResume(c.id)}>
                          Resume
                        </Button>
                      ) : null}
                      {canWrite && hasFailed ? (
                        <Button
                          className="px-2 py-1 text-xs"
                          variant="ghost"
                          title="Re-processes only chunks that timed out, failed schema checks, returned empty, or were rejected by validation"
                          onClick={() => onRerunFailed(c.id)}
                        >
                          Re-run failed
                        </Button>
                      ) : null}
                      {c.policySetId ? (
                        <Link
                          className="text-xs text-sv-accent underline"
                          to={`/app/policies/review?set=${c.policySetId}`}
                        >
                          Open draft
                        </Link>
                      ) : null}
                    </div>
                  </div>
                  {summary ? (
                    <p className="text-xs text-sv-muted">{summary}</p>
                  ) : null}
                  {c.progress?.conflictSummary || c.progress?.conflictCount ? (
                    <p className="text-xs text-risk-alert">
                      {c.progress.conflictSummary ||
                        `Conflicts: ${c.progress.conflictCount}`}
                      {c.policySetId ? (
                        <>
                          {' · '}
                          <Link
                            className="underline"
                            to={`/app/policies/review?set=${c.policySetId}`}
                          >
                            Open conflict panel
                          </Link>
                        </>
                      ) : null}
                    </p>
                  ) : null}
                  {suspects.length > 0 ? (
                    <div className="mt-1 space-y-1">
                      <p className="text-xs text-risk-watch">
                        {suspects.length} suspicious empty reply
                        {suspects.length === 1 ? '' : 'ies'} (obligation cues present)
                      </p>
                      {c.policySetId ? (
                        <Link
                          className="text-xs text-sv-accent underline"
                          to={`/app/policies/review?set=${c.policySetId}`}
                        >
                          Open draft to add rule
                        </Link>
                      ) : null}
                    </div>
                  ) : null}
                  {c.status === 'COMPLETED_NO_RULES' && c.error ? (
                    <p className="text-xs text-risk-watch">{c.error}</p>
                  ) : null}
                </li>
              );
            })}
          </ul>
        </div>
      ) : null}

      <div>
        <p className="mb-2 text-sm font-medium text-sv-fg">Draft policy sets</p>
        {draftSets.length === 0 ? (
          <p className="text-sm text-sv-muted">
            No drafts. Compile from the Documents tab, or open an existing set below.
          </p>
        ) : (
          <ul className="space-y-2">
            {draftSets.map((s) => (
              <li
                key={s.id}
                className="flex flex-wrap items-center justify-between gap-2 rounded border border-sv-border px-3 py-2"
              >
                <span className="text-sm">
                  {s.name || 'Policy set'} · v{s.version}{' '}
                  <Badge tone="accent">{s.status}</Badge>
                </span>
                <div className="flex gap-2">
                  <Link to={`/app/policies/review?set=${s.id}`}>
                    <Button className="px-2 py-1 text-xs">Review workspace</Button>
                  </Link>
                  {canWrite ? (
                    <Button
                      className="px-2 py-1 text-xs"
                      variant="ghost"
                      onClick={async () => {
                        try {
                          await apiJson(`/api/v2/policy/sets/${s.id}/submit`, { method: 'POST' });
                          push('Submitted for approval');
                          onRefresh();
                        } catch (err) {
                          push(err.message || 'Submit failed');
                        }
                      }}
                    >
                      Submit
                    </Button>
                  ) : null}
                  {canWrite ? (
                    <Button
                      className="px-2 py-1 text-xs"
                      variant="danger"
                      onClick={() => onDeleteDraft(s.id)}
                    >
                      Delete draft
                    </Button>
                  ) : null}
                </div>
              </li>
            ))}
          </ul>
        )}
      </div>

      <div>
        <p className="mb-2 text-sm font-medium text-sv-fg">All sets</p>
        {sets.length === 0 ? (
          <p className="text-sm text-sv-muted">No policy sets yet.</p>
        ) : (
          <ul className="space-y-1 text-sm">
            {sets.map((s) => (
              <li key={s.id} className="flex justify-between gap-2">
                <span>
                  v{s.version} · {s.status}
                  {s.contentSha256 ? (
                    <span className="ml-2 font-mono text-xs text-sv-muted">
                      {String(s.contentSha256).slice(0, 12)}…
                    </span>
                  ) : null}
                </span>
                <Link className="text-sv-accent underline" to={`/app/policies/review?set=${s.id}`}>
                  Open
                </Link>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}

RulesTab.propTypes = {
  loading: PropTypes.bool,
  draftSets: PropTypes.array,
  sets: PropTypes.array,
  compilations: PropTypes.array,
  canWrite: PropTypes.bool,
  onResume: PropTypes.func,
  onRerunFailed: PropTypes.func,
  onCancel: PropTypes.func,
  onDeleteDraft: PropTypes.func,
  onRefresh: PropTypes.func,
};

const KEYWORD_CATEGORIES = ['URGENCY', 'SECRECY', 'AUTHORITY', 'PAYMENT', 'CREDENTIAL', 'CUSTOM'];

function KeywordsTab({ loading, sets, canWrite, onRefresh }) {
  const { push } = useToast();
  const [setId, setSetId] = useState('');
  const [keywords, setKeywords] = useState([]);
  const [busy, setBusy] = useState(false);
  const [term, setTerm] = useState('');
  const [category, setCategory] = useState('CUSTOM');

  const activeId = useMemo(
    () => (sets || []).find((s) => s.status === 'ACTIVE')?.id || '',
    [sets],
  );

  // Prefer ACTIVE whenever it exists so Stage A lexicon matches what reviewers expect.
  useEffect(() => {
    if (!sets.length) return;
    const preferred =
      sets.find((s) => s.status === 'ACTIVE') ||
      sets.find((s) => s.status === 'DRAFT') ||
      sets[0];
    setSetId((prev) => {
      if (preferred?.status === 'ACTIVE') return preferred.id;
      if (prev && sets.some((s) => s.id === prev)) return prev;
      return preferred.id;
    });
  }, [sets]);

  const reloadKeywords = useCallback(async (id) => {
    if (!id) {
      setKeywords([]);
      return;
    }
    try {
      const s = await apiJson(`/api/v2/policy/sets/${id}`, { skipErrorToast: true });
      setKeywords((s.keywords || []).filter((k) => String(k.term || '').trim()));
    } catch {
      setKeywords([]);
    }
  }, []);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      if (!setId) {
        if (!cancelled) setKeywords([]);
        return;
      }
      await reloadKeywords(setId);
    })();
    return () => {
      cancelled = true;
    };
  }, [setId, reloadKeywords]);

  // Keep form mounted once we have sets — parent poll must not blank the term input.
  if (loading && sets.length === 0) return <p className="text-sm text-sv-muted">Loading…</p>;
  if (sets.length === 0) {
    return <p className="text-sm text-sv-muted">No policy sets yet — compile a document first.</p>;
  }

  const selected = sets.find((s) => s.id === setId);
  const byCategory = KEYWORD_CATEGORIES.map((cat) => ({
    category: cat,
    items: keywords.filter((k) => k.category === cat),
  })).filter((g) => g.items.length > 0 || canWrite);

  return (
    <div className="space-y-4">
      <p className="text-sm text-sv-muted">
        Live Stage A uses keywords on the <strong>ACTIVE</strong> set only. Terms seen on a draft /
        older version land here after that version is approved — or click Sync from rules.
      </p>
      <div className="flex flex-wrap items-center gap-2">
        <label className="text-sm text-sv-muted">Policy set</label>
        <select
          className="rounded border border-sv-border bg-sv-bg px-2 py-1 text-sm"
          value={setId}
          onChange={(e) => setSetId(e.target.value)}
        >
          {sets.map((s) => (
            <option key={s.id} value={s.id}>
              {s.name || 'Set'} · v{s.version} ({s.status})
              {s.id === activeId ? ' ← live' : ''}
            </option>
          ))}
        </select>
        {canWrite ? (
          <Button
            className="px-2 py-1 text-xs"
            variant="ghost"
            disabled={busy || !setId}
            onClick={async () => {
              setBusy(true);
              try {
                const s = await apiJson(`/api/v2/policy/sets/${setId}/keywords/rebuild`, {
                  method: 'POST',
                });
                setKeywords((s.keywords || []).filter((k) => String(k.term || '').trim()));
                push(`Synced ${s.keywords?.length ?? 0} keywords from rules`);
                onRefresh();
              } catch (err) {
                push(err.message || 'Sync failed');
              } finally {
                setBusy(false);
              }
            }}
          >
            Sync from rules
          </Button>
        ) : null}
        {selected?.status && selected.status !== 'ACTIVE' ? (
          <Badge tone="warn">Not live — switch to ACTIVE for Stage A</Badge>
        ) : null}
      </div>

      {byCategory.map((g) => (
        <div key={g.category} className="rounded border border-sv-border p-3">
          <p className="text-xs font-semibold uppercase tracking-wide text-sv-muted">{g.category}</p>
          <ul className="mt-2 space-y-1 text-sm">
            {g.items.length === 0 ? (
              <li className="text-xs text-sv-muted">No terms</li>
            ) : (
              g.items.map((k) => (
                <li key={k.id} className="flex justify-between gap-2">
                  <span>
                    {k.term}{' '}
                    <span className="text-xs text-sv-muted">({k.lang || 'en'})</span>
                  </span>
                  {canWrite ? (
                    <button
                      type="button"
                      className="text-xs text-risk-critical"
                      onClick={async () => {
                        try {
                          await apiJson(`/api/v2/policy/keywords/${k.id}`, { method: 'DELETE' });
                          setKeywords((prev) => prev.filter((x) => x.id !== k.id));
                          onRefresh();
                        } catch (err) {
                          push(err.message || 'Delete failed');
                        }
                      }}
                    >
                      remove
                    </button>
                  ) : null}
                </li>
              ))
            )}
          </ul>
        </div>
      ))}

      {canWrite ? (
        <div className="flex flex-wrap gap-2 rounded border border-dashed border-sv-border p-3">
          <input
            className="rounded border border-sv-border bg-sv-bg px-2 py-1 text-sm"
            value={term}
            onChange={(e) => setTerm(e.target.value)}
            placeholder="New term"
          />
          <select
            className="rounded border border-sv-border bg-sv-bg px-2 py-1 text-sm"
            value={category}
            onChange={(e) => setCategory(e.target.value)}
          >
            {KEYWORD_CATEGORIES.map((c) => (
              <option key={c} value={c}>
                {c}
              </option>
            ))}
          </select>
          <Button
            className="px-2 py-1 text-xs"
            disabled={busy || !term.trim() || !setId}
            onClick={async () => {
              setBusy(true);
              try {
                await apiJson(`/api/v2/policy/sets/${setId}/keywords`, {
                  method: 'POST',
                  body: JSON.stringify({ term: term.trim(), category, lang: 'en' }),
                });
                setTerm('');
                await reloadKeywords(setId);
                onRefresh();
              } catch (err) {
                push(err.message || 'Add failed');
              } finally {
                setBusy(false);
              }
            }}
          >
            Add keyword
          </Button>
        </div>
      ) : null}
    </div>
  );
}

KeywordsTab.propTypes = {
  loading: PropTypes.bool,
  sets: PropTypes.array,
  canWrite: PropTypes.bool,
  onRefresh: PropTypes.func,
};

function VersionsTab({ loading, sets, canWrite, onRefresh }) {
  const { push } = useToast();
  const [left, setLeft] = useState('');
  const [right, setRight] = useState('');
  const [diff, setDiff] = useState(null);

  useEffect(() => {
    if (sets.length >= 2) {
      setLeft(sets[1]?.id || '');
      setRight(sets[0]?.id || '');
    } else if (sets.length === 1) {
      setRight(sets[0].id);
    }
  }, [sets]);

  async function runDiff() {
    if (!left || !right) return;
    try {
      const d = await apiJson(`/api/v2/policy/sets/${left}/diff/${right}`);
      setDiff(d);
    } catch (err) {
      push(err.message || 'Diff failed');
    }
  }

  if (loading && sets.length === 0) return <p className="text-sm text-sv-muted">Loading…</p>;

  const setTone = (status) => {
    if (status === 'ACTIVE') return 'success';
    if (status === 'REJECTED') return 'danger';
    if (status === 'PENDING_APPROVAL') return 'warn';
    if (status === 'SUPERSEDED') return 'neutral';
    if (status === 'DRAFT') return 'accent';
    return 'neutral';
  };

  return (
    <div className="space-y-4">
      <p className="text-sm text-sv-muted">
        Approve / reject results show here as version status (ACTIVE, REJECTED, SUPERSEDED). The
        Approvals tab only lists items still waiting.
      </p>
      <Table
        columns={[
          { key: 'version', header: 'Version', render: (r) => `v${r.version}` },
          {
            key: 'name',
            header: 'Name',
            render: (r) => r.name || 'Policy set',
          },
          {
            key: 'status',
            header: 'Status',
            render: (r) => <Badge tone={setTone(r.status)}>{r.status}</Badge>,
          },
          {
            key: 'decided',
            header: 'Decided',
            render: (r) =>
              r.approvedAt ? (
                <span className="font-mono text-xs text-sv-muted">
                  {String(r.approvedAt).replace('T', ' ').slice(0, 19)}
                </span>
              ) : (
                <span className="text-xs text-sv-muted">—</span>
              ),
          },
          {
            key: 'comment',
            header: 'Comment',
            render: (r) => (
              <span className="line-clamp-2 text-xs text-sv-fg">{r.comment || '—'}</span>
            ),
          },
          {
            key: 'sha',
            header: 'content_sha256',
            render: (r) => (
              <span className="font-mono text-xs">
                {r.contentSha256 ? `${String(r.contentSha256).slice(0, 12)}…` : '—'}
              </span>
            ),
          },
          {
            key: 'actions',
            header: '',
            render: (r) => (
              <div className="flex gap-2">
                <Link to={`/app/policies/review?set=${r.id}`}>
                  <Button className="px-2 py-1 text-xs" variant="ghost">
                    Open
                  </Button>
                </Link>
                {canWrite && r.status === 'SUPERSEDED' ? (
                  <Button
                    className="px-2 py-1 text-xs"
                    variant="ghost"
                    onClick={async () => {
                      try {
                        await apiJson(`/api/v2/policy/sets/${r.id}/submit`, { method: 'POST' });
                        push('Rollback submitted for approval');
                        onRefresh();
                      } catch (err) {
                        push(err.message || 'Submit failed');
                      }
                    }}
                  >
                    Re-activate…
                  </Button>
                ) : null}
              </div>
            ),
          },
        ]}
        rows={sets}
      />

      <div className="rounded border border-sv-border p-4">
        <p className="text-sm font-medium text-sv-fg">Diff two versions</p>
        <div className="mt-2 flex flex-wrap gap-2">
          <select
            className="rounded border border-sv-border bg-sv-bg px-2 py-1 text-sm"
            value={left}
            onChange={(e) => setLeft(e.target.value)}
          >
            <option value="">Left…</option>
            {sets.map((s) => (
              <option key={s.id} value={s.id}>
                v{s.version} ({s.status})
              </option>
            ))}
          </select>
          <select
            className="rounded border border-sv-border bg-sv-bg px-2 py-1 text-sm"
            value={right}
            onChange={(e) => setRight(e.target.value)}
          >
            <option value="">Right…</option>
            {sets.map((s) => (
              <option key={s.id} value={s.id}>
                v{s.version} ({s.status})
              </option>
            ))}
          </select>
          <Button className="px-2 py-1 text-xs" onClick={runDiff}>
            Compare
          </Button>
        </div>
        {diff ? (
          <div className="mt-3 grid gap-3 text-xs md:grid-cols-3">
            <div>
              <p className="font-medium text-sv-fg">Added</p>
              <ul className="mt-1 list-disc pl-4">
                {(diff.added || []).map((r) => (
                  <li key={r.ruleId || r.id}>{r.title || r.ruleId}</li>
                ))}
              </ul>
            </div>
            <div>
              <p className="font-medium text-sv-fg">Removed</p>
              <ul className="mt-1 list-disc pl-4">
                {(diff.removed || []).map((r) => (
                  <li key={r.ruleId || r.id}>{r.title || r.ruleId}</li>
                ))}
              </ul>
            </div>
            <div>
              <p className="font-medium text-sv-fg">Changed</p>
              <ul className="mt-1 list-disc pl-4">
                {(diff.changed || []).map((r) => (
                  <li key={(r.after || r.before || r).ruleId || r.id}>
                    {(r.after || r.before || r).title || (r.after || r.before || r).ruleId}
                  </li>
                ))}
              </ul>
            </div>
          </div>
        ) : null}
      </div>
    </div>
  );
}

VersionsTab.propTypes = {
  loading: PropTypes.bool,
  sets: PropTypes.array,
  canWrite: PropTypes.bool,
  onRefresh: PropTypes.func,
};

function ApprovalsTab({ loading, pending, sets, canApprove, onRefresh }) {
  const { push } = useToast();
  const [comment, setComment] = useState({});

  const recentDecisions = (sets || [])
    .filter((s) => s.approvedAt && ['ACTIVE', 'REJECTED', 'SUPERSEDED'].includes(s.status))
    .slice(0, 12);

  const setTone = (status) => {
    if (status === 'ACTIVE') return 'success';
    if (status === 'REJECTED') return 'danger';
    if (status === 'SUPERSEDED') return 'neutral';
    return 'warn';
  };

  if (loading && (!pending || pending.length === 0) && recentDecisions.length === 0) {
    return <p className="text-sm text-sv-muted">Loading…</p>;
  }
  if (!canApprove) {
    return (
      <div className="space-y-4">
        <p className="text-sm text-sv-muted">
          Approving / rejecting requires the POLICY_APPROVER role (policies:approve). As tenant
          admin you can still see outcomes under <strong>Versions</strong> and <strong>Audit</strong>.
        </p>
        {recentDecisions.length > 0 ? (
          <RecentDecisionsList decisions={recentDecisions} setTone={setTone} />
        ) : null}
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div>
        <p className="mb-2 text-sm font-medium text-sv-fg">Inbox (pending)</p>
        {pending.length === 0 ? (
          <p className="text-sm text-sv-muted">
            Nothing waiting — approved / rejected sets leave this list. See recent decisions below
            or Policies → Versions.
          </p>
        ) : (
          <ul className="space-y-3">
            {pending.map((s) => (
              <li key={s.id} className="rounded border border-sv-border p-4">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <span className="text-sm font-medium">
                    {s.name || 'Policy set'} · v{s.version}
                  </span>
                  <Link
                    className="text-xs text-sv-accent underline"
                    to={`/app/policies/review?set=${s.id}`}
                  >
                    Review rules
                  </Link>
                </div>
                <Input
                  className="mt-2"
                  label="Comment (required to reject)"
                  value={comment[s.id] || ''}
                  onChange={(e) => setComment((prev) => ({ ...prev, [s.id]: e.target.value }))}
                />
                <div className="mt-2 flex gap-2">
                  <Button
                    className="px-2 py-1 text-xs"
                    onClick={async () => {
                      try {
                        await apiJson(`/api/v2/policy/sets/${s.id}/approve`, {
                          method: 'POST',
                          body: JSON.stringify({ comment: comment[s.id] || '' }),
                        });
                        push('Approved — set is ACTIVE');
                        onRefresh();
                      } catch (err) {
                        push(err.message || 'Approve failed');
                      }
                    }}
                  >
                    Approve
                  </Button>
                  <Button
                    className="px-2 py-1 text-xs"
                    variant="danger"
                    onClick={async () => {
                      try {
                        await apiJson(`/api/v2/policy/sets/${s.id}/reject`, {
                          method: 'POST',
                          body: JSON.stringify({ comment: comment[s.id] || '' }),
                        });
                        push('Rejected');
                        onRefresh();
                      } catch (err) {
                        push(err.message || 'Reject failed');
                      }
                    }}
                  >
                    Reject
                  </Button>
                </div>
              </li>
            ))}
          </ul>
        )}
      </div>

      <RecentDecisionsList decisions={recentDecisions} setTone={setTone} />
    </div>
  );
}

function RecentDecisionsList({ decisions, setTone }) {
  if (!decisions.length) {
    return (
      <p className="text-sm text-sv-muted">No approve / reject decisions recorded yet for this tenant.</p>
    );
  }
  return (
    <div>
      <p className="mb-2 text-sm font-medium text-sv-fg">Recent decisions</p>
      <ul className="space-y-2">
        {decisions.map((s) => (
          <li
            key={s.id}
            className="flex flex-wrap items-center justify-between gap-2 rounded border border-sv-border px-3 py-2 text-sm"
          >
            <span>
              {s.name || 'Policy set'} · v{s.version}{' '}
              <Badge tone={setTone(s.status)}>{s.status}</Badge>
            </span>
            <span className="text-xs text-sv-muted">
              {s.approvedAt ? String(s.approvedAt).replace('T', ' ').slice(0, 19) : ''}
              {s.comment ? ` · ${s.comment}` : ''}
            </span>
            <Link className="text-xs text-sv-accent underline" to={`/app/policies/review?set=${s.id}`}>
              Open
            </Link>
          </li>
        ))}
      </ul>
    </div>
  );
}

RecentDecisionsList.propTypes = {
  decisions: PropTypes.array,
  setTone: PropTypes.func,
};

ApprovalsTab.propTypes = {
  loading: PropTypes.bool,
  pending: PropTypes.array,
  sets: PropTypes.array,
  canApprove: PropTypes.bool,
  onRefresh: PropTypes.func,
};

function PermanentDeleteModal({ state, busy, onClose, onConfirmText, onConfirm, onSetBlocking }) {
  useEffect(() => {
    if (!state?.doc) return undefined;
    let cancelled = false;
    (async () => {
      try {
        const data = await apiJson(
          `/api/v2/policies/documents/${state.doc.id}/delete-blockers`,
          { skipErrorToast: true },
        );
        if (!cancelled) onSetBlocking(data.blockingRules || []);
      } catch (err) {
        if (!cancelled) onSetBlocking([]);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [state?.doc?.id]); // eslint-disable-line react-hooks/exhaustive-deps

  if (!state?.doc) return null;
  const doc = state.doc;
  const blocking = state.blockingRules;
  const blocked = Array.isArray(blocking) && blocking.length > 0;
  const typed = (state.confirmText || '').trim();
  const confirmOk = typed === 'DELETE' && !blocked && !busy && blocking != null;

  return (
    <Modal
      open
      title="Delete permanently"
      onClose={onClose}
      footer={
        <>
          <Button variant="ghost" disabled={busy} onClick={onClose}>
            Cancel
          </Button>
          <Button variant="danger" disabled={!confirmOk} onClick={onConfirm}>
            {busy ? 'Deleting…' : 'Delete permanently'}
          </Button>
        </>
      }
    >
      <p className="text-sm text-sv-fg">
        Permanently delete <strong>{doc.originalFilename || doc.title}</strong>? This cannot be
        undone. File bytes, extracted text, and chunks will be removed.
      </p>
      {blocking == null ? (
        <p className="mt-3 text-xs text-sv-muted">Checking for citing rules…</p>
      ) : blocked ? (
        <div
          role="alert"
          className="mt-3 rounded border border-risk-critical/40 bg-risk-critical/10 px-3 py-2 text-sm"
        >
          <p className="font-medium text-risk-critical">Blocked by citing rules</p>
          <ul className="mt-2 list-disc space-y-1 pl-5 text-sv-fg">
            {blocking.map((r) => (
              <li key={r.id}>
                <Link className="text-sv-accent underline" to={`/app/policies?rule=${r.id}`}>
                  {r.title || r.id}
                </Link>
              </li>
            ))}
          </ul>
        </div>
      ) : (
        <p className="mt-3 text-xs text-sv-muted">
          Type <span className="font-mono text-sv-fg">DELETE</span> to confirm.
        </p>
      )}
      <div className="mt-3">
        <Input
          id="confirm-delete"
          label="Confirmation"
          value={state.confirmText}
          disabled={blocked || busy || blocking == null}
          onChange={(e) => onConfirmText(e.target.value)}
          placeholder="DELETE"
          autoComplete="off"
        />
      </div>
    </Modal>
  );
}

PermanentDeleteModal.propTypes = {
  state: PropTypes.object,
  busy: PropTypes.bool,
  onClose: PropTypes.func,
  onConfirmText: PropTypes.func,
  onConfirm: PropTypes.func,
  onSetBlocking: PropTypes.func,
};

function flagPhrases(flags) {
  if (!Array.isArray(flags) || flags.length === 0) return [];
  return flags.map((f) => (typeof f === 'string' ? f : f?.phrase)).filter(Boolean);
}

function ChunkViewerModal({ open, doc, chunks, loading, onClose }) {
  if (!open || !doc) return null;
  const docPhrases = flagPhrases(doc.injectionFlags);
  return (
    <Modal open={open} onClose={onClose} title={doc.title || 'Document text'} wide>
      {doc.hasInjectionFlags || docPhrases.length > 0 ? (
        <div role="alert" className="mb-3 rounded border border-risk-watch/40 bg-risk-watch/10 px-3 py-2 text-xs">
          Injection flags: {docPhrases.join(', ') || 'present'}
        </div>
      ) : null}
      {doc.status === 'FAILED' && doc.extractionError ? (
        <p className="mb-3 text-sm text-risk-critical">{doc.extractionError}</p>
      ) : null}
      {loading ? (
        <p className="text-sm text-sv-muted">Loading chunks…</p>
      ) : chunks.length === 0 ? (
        <p className="text-sm text-sv-muted">
          {doc.status === 'UPLOADED' ? 'Still extracting…' : 'No chunks yet.'}
        </p>
      ) : (
        <ul className="max-h-[60vh] space-y-3 overflow-y-auto">
          {chunks.map((c) => {
            const flagged = c.hasInjectionFlags || (c.injectionFlags && c.injectionFlags.length > 0);
            return (
              <li
                key={c.id}
                className={`rounded border p-3 ${
                  flagged
                    ? 'border-risk-watch/50 bg-risk-watch/5'
                    : 'border-sv-border bg-sv-elevated/50'
                }`}
              >
                <div className="mb-1 flex flex-wrap items-center gap-2 text-xs text-sv-muted">
                  <span>#{c.ordinal}</span>
                  {c.headingPath ? <span>{c.headingPath}</span> : null}
                  {c.pageNo != null ? <span>p.{c.pageNo}</span> : null}
                  {flagged ? (
                    <span
                      className="inline-flex items-center gap-1 text-risk-watch"
                      title={`Injection flags: ${flagPhrases(c.injectionFlags).join(', ')}`}
                      aria-label="Prompt-injection warning"
                    >
                      <span aria-hidden>⚠</span>
                      <span>untrusted</span>
                    </span>
                  ) : null}
                </div>
                <pre className="whitespace-pre-wrap font-sans text-sm text-sv-fg">{c.text}</pre>
              </li>
            );
          })}
        </ul>
      )}
    </Modal>
  );
}

ChunkViewerModal.propTypes = {
  open: PropTypes.bool,
  doc: PropTypes.object,
  chunks: PropTypes.array,
  loading: PropTypes.bool,
  onClose: PropTypes.func,
};

function EngineStatusChip({ status }) {
  if (!status) {
    return (
      <span className="rounded border border-sv-border px-2 py-1 text-xs text-sv-muted">
        Engine …
      </span>
    );
  }
  if (status.state === 'NO_POLICY') {
    return (
      <span className="rounded border border-risk-watch/40 bg-risk-watch/10 px-2 py-1 text-xs text-risk-watch">
        No ACTIVE policy
      </span>
    );
  }
  const loaded = status.cacheLoadedAt ? relativeAgo(status.cacheLoadedAt) : '—';
  const mismatch = status.inSync === false;
  return (
    <span
      className={`rounded border px-2 py-1 text-xs ${
        mismatch
          ? 'border-risk-alert/50 bg-risk-alert/10 text-risk-alert'
          : 'border-sv-border bg-sv-elevated/50 text-sv-fg'
      }`}
    >
      {mismatch ? 'Engine out of sync · ' : ''}
      Active v{status.activePolicyVersion ?? '?'} · {status.ruleCount ?? 0} rules · loaded {loaded}
      {status.avgEvalMicros != null ? ` · avg ${status.avgEvalMicros}µs` : ''}
      {status.inSync ? ' · in sync' : ''}
    </span>
  );
}

EngineStatusChip.propTypes = {
  status: PropTypes.object,
};

function relativeAgo(iso) {
  try {
    const ms = Date.now() - new Date(iso).getTime();
    if (ms < 60_000) return 'just now';
    const min = Math.round(ms / 60_000);
    if (min < 60) return `${min} min ago`;
    const hr = Math.round(min / 60);
    return `${hr} h ago`;
  } catch {
    return iso;
  }
}

const DEFAULT_SIM_FACTS = {
  'ask.type': 'WIRE_TRANSFER',
  'ask.amountInr': 2500000,
  'ask.beneficiaryKnown': false,
  'ask.amountInr.assertedByCaller': true,
  'caller.matchType': 'NONE',
  'relationship.isFirstContact': true,
  'time.isBusinessHours': true,
};

function shortSha(sha) {
  if (!sha) return '—';
  const s = String(sha);
  return s.length <= 12 ? s : `${s.slice(0, 8)}…`;
}

function LiveRulesTab({ canWrite, engineStatus, onEngineReload, onEngineStatus }) {
  const { push } = useToast();
  const [, setSearchParams] = useSearchParams();
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [selected, setSelected] = useState(() => new Set());
  const [jsonDrawer, setJsonDrawer] = useState(null);
  const [setJson, setSetJson] = useState(null);
  const [deleteDlg, setDeleteDlg] = useState(null);
  const [reason, setReason] = useState('');
  const [confirmEmpty, setConfirmEmpty] = useState(false);
  const [busy, setBusy] = useState(false);
  const [downstreamRule, setDownstreamRule] = useState(null);
  const onEngineStatusRef = useRef(onEngineStatus);
  onEngineStatusRef.current = onEngineStatus;

  const load = useCallback(async ({ soft = false } = {}) => {
    if (!soft) setLoading(true);
    try {
      const d = await apiJson('/api/v2/policy/live-rules', { skipErrorToast: true });
      setData(d);
      if (d?.engine) {
        onEngineStatusRef.current?.(d.engine);
      }
    } catch (err) {
      push(err.message || 'Failed to load live rules');
      setData(null);
    } finally {
      setLoading(false);
    }
  }, [push]);

  useEffect(() => {
    load();
  }, [load]);

  const rules = data?.rules || [];
  const coverage = data?.coverage || {};
  const eng = data?.engine || engineStatus || {};
  const mismatch = eng.inSync === false;

  function toggle(id) {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  async function viewJson(ruleId) {
    try {
      const j = await apiJson(`/api/v2/policy/live-rules/${encodeURIComponent(ruleId)}/json`);
      setJsonDrawer(j);
    } catch (err) {
      push(err.message || 'Failed to load JSON');
    }
  }

  async function copyJson(ruleId) {
    try {
      const j = await apiJson(`/api/v2/policy/live-rules/${encodeURIComponent(ruleId)}/json`);
      await navigator.clipboard.writeText(JSON.stringify(j.rule || j.json, null, 2));
      push('Rule JSON copied');
    } catch (err) {
      push(err.message || 'Copy failed');
    }
  }

  async function openSetJson() {
    if (!data?.policySet?.id) return;
    try {
      const j = await apiJson(`/api/v2/policy/sets/${data.policySet.id}/json`);
      setSetJson(j);
    } catch (err) {
      push(err.message || 'Failed to load set JSON');
    }
  }

  function simulateRule(row, expectFire = true) {
    const facts =
      expectFire !== false
        ? row.factsTemplate?.shouldFire || DEFAULT_SIM_FACTS
        : row.factsTemplate?.shouldNotFire || DEFAULT_SIM_FACTS;
    setSearchParams({
      tab: 'simulate',
      ruleId: row.ruleId,
      facts: JSON.stringify(facts),
    });
  }

  function openSource(row) {
    if (!row.documentId) {
      push('No source document on this rule');
      return;
    }
    const q = new URLSearchParams({
      tab: 'documents',
      doc: String(row.documentId),
      clause: String(row.clauseRef || ''),
    });
    setSearchParams(q);
  }

  async function confirmDelete() {
    if (!deleteDlg || reason.trim().length < 5) {
      push('Reason must be at least 5 characters');
      return;
    }
    setBusy(true);
    try {
      const ruleIds =
        deleteDlg.mode === 'bulk' ? [...selected] : [deleteDlg.rule.ruleId];
      if (deleteDlg.mode === 'draft') {
        for (const rid of ruleIds) {
          await apiJson(`/api/v2/policy/sets/${deleteDlg.draftId}/rules/${encodeURIComponent(rid)}`, {
            method: 'DELETE',
            body: JSON.stringify({ reason: reason.trim() }),
          });
        }
        push('Rule removed from draft');
      } else {
        await apiJson(`/api/v2/policy/sets/${data.policySet.id}/removal-drafts`, {
          method: 'POST',
          body: JSON.stringify({
            ruleIds,
            reason: reason.trim(),
            confirmEmptyPolicy: confirmEmpty,
          }),
        });
        push('Removal draft created — submit & approve to take effect');
      }
      setDeleteDlg(null);
      setReason('');
      setConfirmEmpty(false);
      setSelected(new Set());
      await load();
    } catch (err) {
      push(err.message || 'Delete failed');
    } finally {
      setBusy(false);
    }
  }

  if (loading) {
    return <p className="text-sm text-sv-muted">Loading live rules…</p>;
  }

  if (!data || data.state === 'NO_POLICY') {
    return (
      <p className="text-sm text-sv-muted">
        No ACTIVE policy set. Approve a draft to load rules into the runtime engine.
      </p>
    );
  }

  const columns = [
    {
      key: 'sel',
      header: '',
      render: (row) =>
        canWrite ? (
          <input
            type="checkbox"
            checked={selected.has(row.ruleId)}
            onChange={() => toggle(row.ruleId)}
          />
        ) : null,
    },
    { key: 'ruleId', header: 'Rule ID', render: (r) => <span className="font-mono text-xs">{r.ruleId}</span> },
    { key: 'title', header: 'Title', render: (r) => r.title || '—' },
    {
      key: 'source',
      header: 'Source',
      render: (r) => (
        <span className="text-xs">
          {r.clauseRef || '—'}
          {r.documentId ? (
            <button type="button" className="ml-1 text-sv-accent underline" onClick={() => openSource(r)}>
              open
            </button>
          ) : null}
        </span>
      ),
    },
    {
      key: 'fires',
      header: 'Fires / does not',
      render: (r) => (
        <div className="max-w-xs text-xs text-sv-muted">
          <div>{r.firesWhen}</div>
          <div className="mt-0.5 opacity-80">{r.doesNotFireWhen}</div>
        </div>
      ),
    },
    {
      key: 'level',
      header: 'Level / band',
      render: (r) => (
        <span className="text-xs">
          {r.minLevel} · floor {r.band?.floor ?? '—'} / cap {r.band?.cap ?? '—'}
        </span>
      ),
    },
    { key: 'severity', header: 'Severity', render: (r) => <Badge tone="neutral">{r.severity}</Badge> },
    { key: 'origin', header: 'Origin', render: (r) => (
      r.origin === 'MANUAL' ? <Badge tone="accent">MANUAL</Badge> : (r.origin || '—')
    ) },
    {
      key: 'status',
      header: 'Status',
      render: (r) => (
        <div className="flex flex-col gap-1">
          <Badge tone="success">{r.status}</Badge>
          {r.removalPending ? (
            <span className="text-[10px] text-risk-watch">{r.removalPendingLabel}</span>
          ) : null}
        </div>
      ),
    },
    {
      key: 'fires7',
      header: 'Fire 7d',
      render: (r) => (r.fireCount7d == null ? '—' : r.fireCount7d),
    },
    {
      key: 'warn',
      header: 'Warnings',
      render: (r) =>
        (r.validationWarnings || []).length === 0 ? (
          '—'
        ) : (
          <div className="flex flex-wrap gap-1">
            {(r.validationWarnings || []).map((w) => (
              <Badge key={w.code} tone="warn">
                {w.code}
              </Badge>
            ))}
          </div>
        ),
    },
    {
      key: 'actions',
      header: 'Actions',
      render: (r) => (
        <div className="flex flex-wrap gap-1">
          <Button className="px-1.5 py-0.5 text-[11px]" variant="ghost" onClick={() => viewJson(r.ruleId)}>
            JSON
          </Button>
          <Button className="px-1.5 py-0.5 text-[11px]" variant="ghost" onClick={() => copyJson(r.ruleId)}>
            Copy
          </Button>
          <Button className="px-1.5 py-0.5 text-[11px]" variant="ghost" onClick={() => simulateRule(r, true)}>
            Simulate
          </Button>
          <Button
            className="px-1.5 py-0.5 text-[11px]"
            variant="ghost"
            onClick={() => setDownstreamRule(r)}
          >
            Downstream
          </Button>
          {canWrite ? (
            <Button
              className="px-1.5 py-0.5 text-[11px]"
              variant="danger"
              onClick={() => setDeleteDlg({ mode: 'active', rule: r })}
            >
              Delete
            </Button>
          ) : null}
        </div>
      ),
    },
  ];

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <p className="text-sm font-medium text-sv-fg">Live Rules</p>
          <p className="mt-1 text-xs text-sv-muted">
            ACCEPTED / EDITED rules from the ACTIVE set — exactly what the runtime evaluates.
          </p>
          <div className="mt-2 flex flex-wrap gap-3 text-xs text-sv-muted">
            <span>Policy v{data.policySet?.version}</span>
            <span>sha {shortSha(data.policySet?.contentSha256)}</span>
            <span>{data.ruleCount ?? rules.length} rules</span>
            <span>loaded {eng.cacheLoadedAt ? relativeAgo(eng.cacheLoadedAt) : '—'}</span>
            <span>avg {eng.avgEvalMicros ?? '—'}µs</span>
          </div>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button variant="ghost" onClick={openSetJson}>
            Whole-set JSON
          </Button>
          <Button variant="ghost" onClick={load}>
            Refresh
          </Button>
        </div>
      </div>

      {mismatch ? (
        <div className="flex flex-wrap items-center justify-between gap-3 rounded border border-risk-alert/40 bg-risk-alert/10 px-3 py-2 text-sm text-risk-alert">
          <span>
            Engine cache differs from database — engine v{eng.engineVersion ?? '—'} (
            {shortSha(eng.engineSha)}) vs database v{eng.databaseVersion ?? '—'} (
            {shortSha(eng.databaseSha)})
          </span>
          {canWrite ? (
            <Button
              onClick={async () => {
                try {
                  await onEngineReload();
                  push('Engine reloaded');
                  await load();
                } catch (err) {
                  push(err.message || 'Reload failed');
                }
              }}
            >
              Reload
            </Button>
          ) : null}
        </div>
      ) : (
        <div className="rounded border border-sv-border/60 bg-sv-elevated/30 px-3 py-2 text-xs text-sv-muted">
          Engine in sync with database
        </div>
      )}

      <div className="grid gap-3 sm:grid-cols-4">
        {[
          ['Enforceable clauses', coverage.enforceableClauses],
          ['Rules live', coverage.rulesLive],
          ['Procedural', coverage.procedural],
          ['Unmapped', coverage.unmapped],
        ].map(([label, val]) => (
          <div key={label} className="rounded border border-sv-border bg-sv-elevated/40 px-3 py-2">
            <p className="text-[11px] uppercase tracking-wide text-sv-muted">{label}</p>
            <p className="mt-1 text-lg font-semibold text-sv-fg">{val ?? 0}</p>
          </div>
        ))}
      </div>

      {canWrite && selected.size > 0 ? (
        <div className="flex items-center gap-2">
          <Button
            variant="danger"
            onClick={() => setDeleteDlg({ mode: 'bulk', rule: { ruleId: `${selected.size} rules` } })}
          >
            Remove selected ({selected.size})
          </Button>
        </div>
      ) : null}

      <Table columns={columns} rows={rules} />

      {downstreamRule ? (
        <Modal
          open
          title={`Downstream · ${downstreamRule.ruleId}`}
          onClose={() => setDownstreamRule(null)}
        >
          <pre className="max-h-80 overflow-auto rounded bg-sv-bg p-3 font-mono text-xs text-sv-fg">
            {JSON.stringify(downstreamRule.downstream, null, 2)}
          </pre>
        </Modal>
      ) : null}

      {jsonDrawer ? (
        <Modal open title={`Rule JSON · ${jsonDrawer.rule?.ruleId || ''}`} onClose={() => setJsonDrawer(null)}>
          <div className="mb-2 flex flex-wrap gap-2 text-xs">
            <Badge tone={jsonDrawer.schemaValid ? 'success' : 'danger'}>
              schema {jsonDrawer.schemaValid ? 'valid' : 'invalid'}
            </Badge>
            {(jsonDrawer.validator?.checks || []).map((c) => (
              <Badge key={c.check} tone={c.result === 'PASS' ? 'success' : 'danger'}>
                {c.check}: {c.result}
              </Badge>
            ))}
          </div>
          <pre className="max-h-96 overflow-auto rounded bg-sv-bg p-3 font-mono text-xs text-sv-fg">
            {JSON.stringify(jsonDrawer.rule || jsonDrawer.json, null, 2)}
          </pre>
        </Modal>
      ) : null}

      {setJson ? (
        <Modal open title="Live rules JSON (whole set)" onClose={() => setSetJson(null)}>
          <div className="mb-2 flex flex-wrap gap-2">
            <Button
              variant="ghost"
              onClick={() => {
                const blob = new Blob([JSON.stringify(setJson, null, 2)], { type: 'application/json' });
                const url = URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.href = url;
                a.download = `live-rules-v${setJson.version}-${shortSha(setJson.contentSha256)}.json`;
                a.click();
                URL.revokeObjectURL(url);
              }}
            >
              Download JSON
            </Button>
            <Button
              variant="ghost"
              onClick={async () => {
                try {
                  const v = await apiJson(`/api/v2/policy/sets/${setJson.policySetId}/rules-json/validate`, {
                    method: 'POST',
                    body: JSON.stringify({ rules: setJson.rules }),
                  });
                  push(v.ok ? 'Validation PASS' : 'Validation FAIL — see console');
                  // eslint-disable-next-line no-console
                  console.log('rules-json validate', v);
                } catch (err) {
                  push(err.message || 'Validate failed');
                }
              }}
            >
              Validate
            </Button>
            <span className="text-xs text-sv-muted">
              Read-only for ACTIVE. Edit-as-JSON only in a DRAFT via Rules review.
            </span>
          </div>
          <pre className="max-h-96 overflow-auto rounded bg-sv-bg p-3 font-mono text-xs text-sv-fg">
            {JSON.stringify(setJson.rules, null, 2)}
          </pre>
        </Modal>
      ) : null}

      {deleteDlg ? (
        <Modal
          open
          title={deleteDlg.mode === 'draft' ? 'Delete from draft' : 'Remove from live policy'}
          onClose={() => !busy && setDeleteDlg(null)}
        >
          <p className="text-sm text-sv-muted">
            {deleteDlg.mode === 'bulk'
              ? `Create a removal draft for ${selected.size} selected rule(s). They keep firing until a different user approves.`
              : `Rule ${deleteDlg.rule?.ruleId} — ${deleteDlg.rule?.title || ''}. Creates (or reuses) a DRAFT without this rule; takes effect only after submit → approve.`}
          </p>
          {deleteDlg.rule?.downstream ? (
            <pre className="mt-2 max-h-32 overflow-auto rounded bg-sv-bg p-2 font-mono text-[11px]">
              {JSON.stringify(deleteDlg.rule.downstream, null, 2)}
            </pre>
          ) : null}
          <label className="mt-3 flex flex-col gap-1 text-xs text-sv-muted">
            Reason (min 5 chars)
            <Input value={reason} onChange={(e) => setReason(e.target.value)} />
          </label>
          <label className="mt-2 flex items-center gap-2 text-xs text-sv-muted">
            <input
              type="checkbox"
              checked={confirmEmpty}
              onChange={(e) => setConfirmEmpty(e.target.checked)}
            />
            Confirm: this tenant may end up with no enforceable rules
          </label>
          <div className="mt-4 flex justify-end gap-2">
            <Button variant="ghost" disabled={busy} onClick={() => setDeleteDlg(null)}>
              Cancel
            </Button>
            <Button variant="danger" disabled={busy} onClick={confirmDelete}>
              {busy ? 'Working…' : 'Confirm removal request'}
            </Button>
          </div>
        </Modal>
      ) : null}
    </div>
  );
}

LiveRulesTab.propTypes = {
  canWrite: PropTypes.bool,
  engineStatus: PropTypes.object,
  onEngineReload: PropTypes.func,
  onEngineStatus: PropTypes.func,
};

function SimulateTab({ sets }) {
  const { push } = useToast();
  const [searchParams] = useSearchParams();
  const preRule = searchParams.get('ruleId');
  const preFacts = searchParams.get('facts');
  const [policySetId, setPolicySetId] = useState('active');
  const [factsJson, setFactsJson] = useState(() => {
    if (preFacts) {
      try {
        return JSON.stringify(JSON.parse(preFacts), null, 2);
      } catch {
        return preFacts;
      }
    }
    return JSON.stringify(DEFAULT_SIM_FACTS, null, 2);
  });
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState(null);

  useEffect(() => {
    if (preFacts) {
      try {
        setFactsJson(JSON.stringify(JSON.parse(preFacts), null, 2));
      } catch {
        setFactsJson(preFacts);
      }
    }
  }, [preFacts]);

  const active = sets.find((s) => s.status === 'ACTIVE');
  const choices = [
    { id: 'active', label: active ? `ACTIVE (v${active.version})` : 'ACTIVE (none)' },
    ...sets.map((s) => ({ id: s.id, label: `v${s.version} · ${s.status} · ${s.name || s.id}` })),
  ];

  async function run() {
    setBusy(true);
    setResult(null);
    try {
      let facts;
      try {
        facts = JSON.parse(factsJson);
      } catch {
        throw new Error('Facts must be valid JSON');
      }
      const body = {
        policySetId: policySetId === 'active' ? 'active' : policySetId,
        facts,
      };
      const data = await apiJson('/api/v2/policy/simulate', {
        method: 'POST',
        body: JSON.stringify(body),
      });
      setResult(data);
    } catch (err) {
      push(err.message || 'Simulation failed');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <div>
        <p className="text-sm font-medium text-sv-fg">Rule simulation</p>
        <p className="mt-1 text-xs text-sv-muted">
          Evaluate ACCEPTED/EDITED rules against a fact bag. Simulations are never audited as real
          decisions. Missing facts yield UNDETERMINED rules (three-valued logic).
          {preRule ? (
            <span className="ml-1 text-sv-fg"> Preselected from Live Rules: {preRule}.</span>
          ) : null}
        </p>
      </div>
      <div className="flex flex-wrap items-end gap-3">
        <label className="flex flex-col gap-1 text-xs text-sv-muted">
          Policy set
          <select
            className="rounded border border-sv-border bg-sv-elevated px-2 py-1.5 text-sm text-sv-fg"
            value={policySetId}
            onChange={(e) => setPolicySetId(e.target.value)}
          >
            {choices.map((c) => (
              <option key={c.id} value={c.id}>
                {c.label}
              </option>
            ))}
          </select>
        </label>
        <Button disabled={busy} onClick={run}>
          {busy ? 'Running…' : 'Run evaluation'}
        </Button>
      </div>
      <label className="flex flex-col gap-1 text-xs text-sv-muted">
        Facts (JSON)
        <textarea
          className="min-h-[180px] rounded border border-sv-border bg-sv-elevated p-3 font-mono text-xs text-sv-fg"
          value={factsJson}
          onChange={(e) => setFactsJson(e.target.value)}
          spellCheck={false}
        />
      </label>

      {result ? (
        <div className="space-y-3 rounded border border-sv-border bg-sv-elevated/40 p-4">
          <div className="flex flex-wrap gap-3 text-sm">
            <Badge tone={result.state === 'NO_POLICY' ? 'danger' : 'success'}>{result.state}</Badge>
            <span className="text-sv-fg">
              Floor level: <strong>{result.minLevel ?? 0}</strong>
            </span>
            <span className="text-sv-muted">Score {Number(result.policyScore || 0).toFixed(3)}</span>
            <span className="text-sv-muted">{result.evaluationMicros}µs</span>
            {result.policyVersion != null ? (
              <span className="text-sv-muted">v{result.policyVersion}</span>
            ) : null}
            {result.simulation ? <Badge tone="accent">simulation</Badge> : null}
            {preRule ? (
              <Badge
                tone={(result.firedRules || []).some((f) => f.ruleId === preRule) ? 'success' : 'neutral'}
              >
                {preRule}: {(result.firedRules || []).some((f) => f.ruleId === preRule) ? 'FIRED' : 'not fired'}
              </Badge>
            ) : null}
          </div>

          <div>
            <p className="text-sm font-medium text-sv-fg">
              Fired rules ({(result.firedRules || []).length})
            </p>
            {(result.firedRules || []).length === 0 ? (
              <p className="mt-1 text-xs text-sv-muted">None fired.</p>
            ) : (
              <ul className="mt-2 space-y-2">
                {(result.firedRules || []).map((r) => (
                  <li
                    key={r.ruleId}
                    className="rounded border border-sv-border/80 bg-sv-bg/40 px-3 py-2 text-sm"
                  >
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="font-medium text-sv-fg">{r.title || r.ruleId}</span>
                      <Badge tone="neutral">{r.severity}</Badge>
                      <span className="text-xs text-sv-muted">minLevel {r.minLevel}</span>
                      <span className="text-xs text-sv-muted">{r.reasonCode}</span>
                    </div>
                    {r.sourceRef?.clauseRef || r.sourceRef?.quote ? (
                      <p className="mt-1 text-xs text-sv-muted">
                        {r.sourceRef.clauseRef ? `${r.sourceRef.clauseRef}: ` : ''}
                        {r.sourceRef.quote
                          ? `"${String(r.sourceRef.quote).slice(0, 160)}${
                              String(r.sourceRef.quote).length > 160 ? '…' : ''
                            }"`
                          : null}
                      </p>
                    ) : null}
                  </li>
                ))}
              </ul>
            )}
          </div>

          <div>
            <p className="text-sm font-medium text-sv-fg">
              Undetermined ({(result.undeterminedRules || []).length})
            </p>
            {(result.undeterminedRules || []).length === 0 ? (
              <p className="mt-1 text-xs text-sv-muted">All evaluable rules resolved.</p>
            ) : (
              <>
                <p className="mt-1 text-xs text-sv-muted">
                  {(result.undeterminedRules || []).length} rules could not be evaluated
                  {result.unknownFacts?.length
                    ? `: missing ${result.unknownFacts.join(', ')}`
                    : ''}
                </p>
                <ul className="mt-2 space-y-1 text-sm text-sv-muted">
                  {(result.undeterminedRules || []).map((u) => (
                    <li key={u.ruleId}>
                      {u.title || u.ruleId}
                      {u.missingFacts?.length ? (
                        <span className="text-xs"> — needs {u.missingFacts.join(', ')}</span>
                      ) : null}
                    </li>
                  ))}
                </ul>
              </>
            )}
          </div>
        </div>
      ) : null}
    </div>
  );
}

SimulateTab.propTypes = {
  sets: PropTypes.array,
};

