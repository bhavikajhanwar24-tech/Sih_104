import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';

/**
 * Public OTP-less consent acceptance (F15). No auth cookie required.
 */
export function ConsentPublicPage() {
  const { token } = useParams();
  const [preview, setPreview] = useState(null);
  const [error, setError] = useState(null);
  const [done, setDone] = useState(false);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const res = await fetch(`/api/v2/public/consent/${encodeURIComponent(token)}`);
        if (!res.ok) throw new Error('Link invalid or expired');
        const json = await res.json();
        if (!cancelled) setPreview(json);
      } catch (e) {
        if (!cancelled) setError(e.message || 'Failed to load');
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [token]);

  const accept = async () => {
    setBusy(true);
    setError(null);
    try {
      const res = await fetch(`/api/v2/public/consent/${encodeURIComponent(token)}/accept`, {
        method: 'POST',
      });
      if (!res.ok) {
        const body = await res.json().catch(() => ({}));
        throw new Error(body.error || `HTTP ${res.status}`);
      }
      setDone(true);
    } catch (e) {
      setError(e.message || 'Accept failed');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="flex min-h-screen items-center justify-center bg-gradient-to-b from-sv-bg to-sv-elevated px-4 py-12">
      <div className="w-full max-w-lg space-y-4">
        <h1 className="font-display text-3xl font-semibold text-sv-fg">SentinelVoice</h1>
        <p className="text-sm text-sv-muted">Consent for call monitoring / voice passport</p>
        {error ? <p className="text-sm text-red-400">{error}</p> : null}
        {done ? (
          <p className="rounded border border-sv-border bg-sv-elevated/50 p-4 text-sm text-sv-fg">
            Consent recorded. You can close this page.
          </p>
        ) : preview ? (
          <div className="space-y-4 rounded border border-sv-border bg-sv-elevated/40 p-5">
            <p className="text-sm text-sv-fg">
              Employee: <strong>{preview.employee?.fullName}</strong>{' '}
              <span className="font-mono text-xs text-sv-muted">({preview.employee?.employeeCode})</span>
            </p>
            <p className="text-xs uppercase tracking-wide text-sv-muted">Purpose · {preview.purpose}</p>
            <div
              className="prose prose-invert max-w-none text-sm text-sv-muted"
              dangerouslySetInnerHTML={{
                __html: String(preview.noticeText || 'No notice configured.')
                  .replace(/</g, '&lt;')
                  .replace(/>/g, '&gt;')
                  .replace(/\n/g, '<br/>'),
              }}
            />
            <p className="text-[11px] text-sv-muted">Notice version {preview.noticeVersion}</p>
            <button
              type="button"
              disabled={busy}
              onClick={accept}
              className="rounded bg-sv-accent px-4 py-2 text-sm font-medium text-sv-bg disabled:opacity-50"
            >
              {busy ? 'Recording…' : 'I accept'}
            </button>
          </div>
        ) : (
          <p className="text-sm text-sv-muted">Loading…</p>
        )}
      </div>
    </div>
  );
}
