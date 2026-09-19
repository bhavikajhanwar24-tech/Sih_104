import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, Navigate } from 'react-router-dom';
import PropTypes from 'prop-types';
import { useAuth } from '@/context/AuthContext.jsx';
import { apiFetch, apiJson } from '@/services/api.js';
import { Badge, Button, Input, Modal, Table, Tabs } from '@/ui';
import { useToast } from '@/ui/Toast.jsx';

const DOC_TABS = [
  { id: 'documents', label: 'Documents' },
  { id: 'archived', label: 'Archived' },
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
 * F5 — Policy document upload / extract / archive / permanent delete.
 */
export function PoliciesPage() {
  const { hasPermission } = useAuth();
  const { push } = useToast();
  const [tab, setTab] = useState('documents');
  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(true);
  const [uploading, setUploading] = useState(false);
  const [dragOver, setDragOver] = useState(false);
  const [viewer, setViewer] = useState(null);
  const [chunks, setChunks] = useState([]);
  const [chunksLoading, setChunksLoading] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState(null);
  const [deleteBusy, setDeleteBusy] = useState(false);
  const fileRef = useRef(null);
  const pollRef = useRef(null);

  const canWrite = hasPermission('policies:write');
  const canDownload = hasPermission('policies:write') || hasPermission('audit:read');
  const archivedTab = tab === 'archived';

  const load = useCallback(async () => {
    try {
      const q = archivedTab ? 'archived=true' : 'includeArchived=false';
      const data = await apiJson(`/api/v2/policies/documents?${q}`);
      setItems(data.items || []);
    } catch (err) {
      push(err.message || 'Failed to load documents');
    } finally {
      setLoading(false);
    }
  }, [push, archivedTab]);

  useEffect(() => {
    if (!hasPermission('policies:read')) return undefined;
    setLoading(true);
    load();
    pollRef.current = setInterval(() => {
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
          <span className="inline-flex flex-wrap items-center gap-1">
            <Badge tone={STATUS_TONE[row.status] || 'neutral'}>
              {STATUS_LABEL[row.status] || row.status}
            </Badge>
            {row.hasInjectionFlags ? <Badge tone="warn">injection</Badge> : null}
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
          <div className="flex flex-wrap gap-1">
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
                {row.status === 'EXTRACTED' ? 'Re-extract' : 'Retry extract'}
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

  const flagged = !archivedTab ? items.filter((d) => d.hasInjectionFlags) : [];

  return (
    <div className="flex h-full flex-col gap-4 p-6">
      <div>
        <h1 className="text-xl font-semibold text-sv-fg">Policies</h1>
        <p className="mt-1 text-sm text-sv-muted">
          Upload tenant policy documents for extraction and later compile (F6). Use the Archived
          tab to restore or permanently delete.
        </p>
      </div>

      <Tabs tabs={DOC_TABS} value={tab} onChange={setTab} />

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
          phrases that look like prompt-injection. Chunks will be treated as untrusted data in F6;
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

      {loading ? (
        <p className="text-sm text-sv-muted">Loading documents…</p>
      ) : items.length === 0 ? (
        <p className="text-sm text-sv-muted">
          {archivedTab ? 'No archived documents.' : 'No policy documents yet.'}
        </p>
      ) : (
        <Table columns={columns} rows={items} />
      )}

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
