import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, Navigate, useSearchParams } from 'react-router-dom';
import PropTypes from 'prop-types';
import { useAuth } from '@/context/AuthContext.jsx';
import { apiFetch, apiJson } from '@/services/api.js';
import { Badge, Button, Input, Modal, Table, Tabs } from '@/ui';
import { useToast } from '@/ui/Toast.jsx';

const DOC_TABS = [
  { id: 'documents', label: 'Documents' },
  { id: 'archived', label: 'Archived' },
  { id: 'rules', label: 'Rules' },
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
  const fileRef = useRef(null);
  const pollRef = useRef(null);

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
      if (docsTab) {
        const q = archivedTab ? 'archived=true' : 'includeArchived=false';
        const data = await apiJson(`/api/v2/policies/documents?${q}`);
        setItems(data.items || []);
      }
      if (docsTab && !archivedTab) {
        const limits = await apiJson('/api/v2/policy/compile-limits', { skipErrorToast: true }).catch(
          () => ({ maxDocumentsPerCompile: 5 }),
        );
        if (limits.maxDocumentsPerCompile) setMaxDocs(limits.maxDocumentsPerCompile);
      }
      if (tab === 'rules' || tab === 'versions' || tab === 'approvals' || tab === 'keywords') {
        const [s, c, limits] = await Promise.all([
          apiJson('/api/v2/policy/sets', { skipErrorToast: true }).catch(() => ({ items: [] })),
          apiJson('/api/v2/policy/compilations', { skipErrorToast: true }).catch(() => ({ items: [] })),
          apiJson('/api/v2/policy/compile-limits', { skipErrorToast: true }).catch(() => ({
            maxDocumentsPerCompile: 5,
          })),
        ]);
        setSets(s.items || []);
        setCompilations(c.items || []);
        if (limits.maxDocumentsPerCompile) setMaxDocs(limits.maxDocumentsPerCompile);
      }
    } catch (err) {
      push(err.message || 'Failed to load');
    } finally {
      setLoading(false);
    }
  }, [push, archivedTab, docsTab, tab]);

  useEffect(() => {
    if (!hasPermission('policies:read')) return undefined;
    setLoading(true);
    load();
    pollRef.current = setInterval(() => {
      // Silent refresh — never flip loading or remount tab inputs mid-typing.
      load();
    }, 4000);
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
      <div>
        <h1 className="text-xl font-semibold text-sv-fg">Policies</h1>
        <p className="mt-1 text-sm text-sv-muted">
          Documents, LLM-assisted rule compile, human review, and versioned approval.
        </p>
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
                Select up to {maxDocs} extracted documents, then run FULL or INCREMENTAL compile
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
  return {
    chunks: progress.chunksProcessed ?? progress.chunksDone ?? '—',
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
  if (loading && draftSets.length === 0 && sets.length === 0 && compilations.length === 0) {
    return <p className="text-sm text-sv-muted">Loading…</p>;
  }
  return (
    <div className="space-y-4">
      {compilations.length > 0 ? (
        <div className="rounded border border-sv-border p-4">
          <p className="text-sm font-medium text-sv-fg">Compilation progress</p>
          <ul className="mt-2 space-y-3 text-sm">
            {compilations.map((c) => {
              const p = progressCounts(c.progress);
              const summary = diagnosticSummary(c);
              const hasFailed =
                c.diagnostics?.hasFailedChunks ||
                c.status === 'FAILED' ||
                c.status === 'COMPLETED_NO_RULES';
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

  useEffect(() => {
    if (!sets.length) return;
    const preferred =
      sets.find((s) => s.status === 'ACTIVE') ||
      sets.find((s) => s.status === 'DRAFT') ||
      sets[0];
    setSetId((prev) => prev || preferred.id);
  }, [sets]);

  useEffect(() => {
    if (!setId) {
      setKeywords([]);
      return undefined;
    }
    let cancelled = false;
    (async () => {
      try {
        const s = await apiJson(`/api/v2/policy/sets/${setId}`, { skipErrorToast: true });
        if (!cancelled) setKeywords(s.keywords || []);
      } catch {
        if (!cancelled) setKeywords([]);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [setId]);

  // Keep form mounted once we have sets — parent poll must not blank the term input.
  if (loading && sets.length === 0) return <p className="text-sm text-sv-muted">Loading…</p>;
  if (sets.length === 0) {
    return <p className="text-sm text-sv-muted">No policy sets yet — compile a document first.</p>;
  }

  const byCategory = KEYWORD_CATEGORIES.map((cat) => ({
    category: cat,
    items: keywords.filter((k) => k.category === cat),
  })).filter((g) => g.items.length > 0 || canWrite);

  return (
    <div className="space-y-4">
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
            </option>
          ))}
        </select>
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
                const s = await apiJson(`/api/v2/policy/sets/${setId}`);
                setKeywords(s.keywords || []);
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
