import { useCallback, useEffect, useState } from 'react';
import PropTypes from 'prop-types';
import { Badge } from '@/components/ui/Badge.jsx';
import { useAuth } from '@/context/AuthContext.jsx';
import { apiFetch } from '@/services/api.js';

/**
 * Session list → generate JSON dossier → evidence/reason preview → PDF + X-Document-SHA256.
 *
 * @param {Object} props
 * @param {string | null} [props.initialSessionId]
 * @param {string} [props.className]
 */
export function ForensicDossierViewer({ initialSessionId = null, className = '' }) {
  const { username } = useAuth();
  const [sessions, setSessions] = useState(/** @type {any[]} */ ([]));
  const [sessionId, setSessionId] = useState(initialSessionId ?? '');
  const [dossier, setDossier] = useState(/** @type {any | null} */ (null));
  const [pdfSha, setPdfSha] = useState(/** @type {string | null} */ (null));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(/** @type {string | null} */ (null));

  useEffect(() => {
    if (initialSessionId) setSessionId(initialSessionId);
  }, [initialSessionId]);

  const loadSessions = useCallback(async () => {
    try {
      const res = await apiFetch('/api/v2/sessions?limit=50');
      if (!res.ok) throw new Error(`sessions HTTP ${res.status}`);
      const body = await res.json();
      setSessions(Array.isArray(body.items) ? body.items : []);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'session list failed');
    }
  }, []);

  useEffect(() => {
    void loadSessions();
  }, [loadSessions]);

  async function generate() {
    if (!sessionId) return;
    setBusy(true);
    setError(null);
    setPdfSha(null);
    try {
      const res = await apiFetch(
        `/api/v2/sessions/${encodeURIComponent(sessionId)}/dossier?generatedBy=${encodeURIComponent(username || 'analyst')}`,
      );
      if (!res.ok) throw new Error(`dossier HTTP ${res.status}: ${(await res.text()).slice(0, 160)}`);
      setDossier(await res.json());
    } catch (e) {
      setDossier(null);
      setError(e instanceof Error ? e.message : 'generate failed');
    } finally {
      setBusy(false);
    }
  }

  async function downloadPdf() {
    if (!sessionId) return;
    setBusy(true);
    setError(null);
    try {
      const res = await apiFetch(
        `/api/v2/sessions/${encodeURIComponent(sessionId)}/dossier.pdf?generatedBy=${encodeURIComponent(username || 'analyst')}`,
      );
      if (!res.ok) throw new Error(`PDF HTTP ${res.status}`);
      const sha = res.headers.get('X-Document-SHA256');
      setPdfSha(sha);
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `sentinelvoice-dossier-${sessionId}.pdf`;
      a.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'PDF download failed');
    } finally {
      setBusy(false);
    }
  }

  const evidence = Array.isArray(dossier?.evidence) ? dossier.evidence : [];
  const reasons = evidence.filter((e) => e && (e.reasonCode || e.code || e.text));

  return (
    <div className={`flex flex-col gap-3 ${className}`} data-testid="forensic-dossier-viewer">
      <div className="flex flex-wrap items-end gap-2">
        <label className="min-w-[12rem] flex-1 text-[11px] uppercase tracking-wide text-sv-muted">
          Session
          <select
            className="mt-1 w-full rounded border border-sv-border bg-sv-bg px-2 py-1.5 font-mono text-xs text-sv-fg"
            value={sessionId}
            onChange={(ev) => setSessionId(ev.target.value)}
            data-testid="forensic-session-select"
          >
            <option value="">— select —</option>
            {sessions.map((s) => {
              const sid = s.svSessionUuid || s.id;
              return (
                <option key={sid} value={sid}>
                  {s.callerName || s.callerNumber || sid}
                  {s.peakLevel ? ` · ${s.peakLevel}` : ''}
                </option>
              );
            })}
          </select>
        </label>
        <button
          type="button"
          disabled={busy}
          onClick={() => void loadSessions()}
          className="rounded border border-sv-border px-2 py-1.5 text-[11px] text-sv-muted hover:text-sv-fg"
        >
          Refresh list
        </button>
        <button
          type="button"
          disabled={busy || !sessionId}
          onClick={() => void generate()}
          className="rounded bg-sv-accent px-3 py-1.5 text-xs font-medium text-sv-bg disabled:opacity-40"
          data-testid="forensic-generate"
        >
          Generate dossier
        </button>
        <button
          type="button"
          disabled={busy || !sessionId}
          onClick={() => void downloadPdf()}
          className="rounded border border-sv-border px-3 py-1.5 text-xs text-sv-fg hover:border-sv-accent disabled:opacity-40"
          data-testid="forensic-pdf"
        >
          Download PDF
        </button>
      </div>

      {pdfSha ? (
        <p className="font-mono text-[11px] text-sv-accent" data-testid="forensic-pdf-sha">
          X-Document-SHA256: {pdfSha}
        </p>
      ) : null}

      {dossier ? (
        <div className="grid gap-3 md:grid-cols-2">
          <div className="rounded border border-sv-border bg-sv-bg/50 p-3">
            <div className="flex items-center gap-2">
              <p className="font-mono text-[10px] uppercase text-sv-muted">Summary</p>
              {dossier.noFindings ? <Badge variant="live">no findings</Badge> : null}
            </div>
            <p className="mt-2 text-sm text-sv-fg">{dossier.summary || '—'}</p>
            <p className="mt-2 font-mono text-[10px] text-sv-muted break-all">
              manifest {dossier.manifestSha256 || '—'}
            </p>
          </div>
          <div className="rounded border border-sv-border bg-sv-bg/50 p-3">
            <p className="font-mono text-[10px] uppercase text-sv-muted">
              Evidence / reason preview ({reasons.length})
            </p>
            <ul className="mt-2 max-h-40 space-y-1 overflow-auto" data-testid="forensic-evidence">
              {reasons.length === 0 ? (
                <li className="text-xs text-sv-muted">No evidence items.</li>
              ) : (
                reasons.slice(0, 12).map((item, idx) => (
                  <li key={idx} className="text-xs text-sv-fg">
                    <span className="font-mono text-sv-accent">
                      {item.reasonCode || item.code || item.family || 'EVIDENCE'}
                    </span>{' '}
                    <span className="text-sv-muted">{item.text || item.detail || ''}</span>
                  </li>
                ))
              )}
            </ul>
          </div>
        </div>
      ) : (
        <p className="text-xs text-sv-muted">Generate a dossier to preview evidence and reasons.</p>
      )}

      {error ? (
        <p className="text-sm text-red-400" role="alert">
          {error}
        </p>
      ) : null}
    </div>
  );
}

ForensicDossierViewer.propTypes = {
  initialSessionId: PropTypes.string,
  className: PropTypes.string,
};
