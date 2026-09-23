import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, Navigate, useSearchParams } from 'react-router-dom';
import { useAuth } from '@/context/AuthContext.jsx';
import { apiFetch, apiJson, ensureCsrf } from '@/services/api.js';
import { Badge, Button, EmptyState, Input, Modal, Select, Table, Tabs } from '@/ui';
import { useToast } from '@/ui/Toast.jsx';

const TABS = [
  { id: 'consent', label: 'Consent Register' },
  { id: 'retention', label: 'Retention' },
  { id: 'dpdp', label: 'DPDP Mapping' },
  { id: 'dsr', label: 'Data Subject Requests' },
  { id: 'fairness', label: 'Fairness' },
];

/**
 * F15 — /app/compliance
 */
export function CompliancePage() {
  const { hasPermission } = useAuth();
  const [params, setParams] = useSearchParams();
  const tab = params.get('tab') || 'consent';
  const canRead = hasPermission('compliance:read');
  const canWrite = hasPermission('compliance:write');

  if (!canRead) {
    return <Navigate to="/app" replace />;
  }

  return (
    <div className="flex h-full min-h-0 flex-col gap-4 p-6">
      <header>
        <h1 className="font-display text-2xl font-semibold text-sv-fg">Compliance</h1>
        <p className="mt-1 text-sm text-sv-muted">
          Consent, retention, notice assets, voice passports, and data-subject operations.
        </p>
      </header>
      <Tabs
        tabs={TABS}
        value={tab}
        onChange={(id) => {
          const next = new URLSearchParams(params);
          next.set('tab', id);
          setParams(next, { replace: true });
        }}
      />
      <div className="min-h-0 flex-1 overflow-auto">
        {tab === 'consent' ? <ConsentTab canWrite={canWrite} /> : null}
        {tab === 'retention' ? <RetentionTab canWrite={canWrite} /> : null}
        {tab === 'dpdp' ? <DpdpTab /> : null}
        {tab === 'dsr' ? <DsrTab canWrite={canWrite} /> : null}
        {tab === 'fairness' ? <FairnessTab /> : null}
      </div>
    </div>
  );
}

function ConsentTab({ canWrite }) {
  const { push } = useToast();
  const [status, setStatus] = useState('');
  const [purpose, setPurpose] = useState('');
  const [items, setItems] = useState([]);
  const [selected, setSelected] = useState(/** @type {Set<string>} */ (new Set()));
  const [loading, setLoading] = useState(true);
  const [linkModal, setLinkModal] = useState(/** @type {null | { employeeId: string, purpose: string }} */ (null));
  const [issuedLink, setIssuedLink] = useState(/** @type {string | null} */ (null));
  const [bulkPurpose, setBulkPurpose] = useState('MONITORING');
  const [bulkStatus, setBulkStatus] = useState('GRANTED');

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const q = new URLSearchParams();
      if (status) q.set('status', status);
      if (purpose) q.set('purpose', purpose);
      const data = await apiJson(`/api/v2/compliance/consents?${q}`);
      setItems(data.items || []);
    } catch (e) {
      push(e.message || 'Failed to load consents');
    } finally {
      setLoading(false);
    }
  }, [status, purpose, push]);

  useEffect(() => {
    load();
  }, [load]);

  const toggle = (employeeId) => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(employeeId)) next.delete(employeeId);
      else next.add(employeeId);
      return next;
    });
  };

  const bulk = async () => {
    if (!selected.size) return;
    await ensureCsrf();
    try {
      await apiJson('/api/v2/compliance/consents/bulk', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          employeeIds: [...selected],
          purpose: bulkPurpose,
          status: bulkStatus,
        }),
      });
      push(`Updated ${selected.size} employees`);
      setSelected(new Set());
      load();
    } catch (e) {
      push(e.message || 'Bulk update failed');
    }
  };

  const withdraw = async (row) => {
    await ensureCsrf();
    try {
      await apiJson('/api/v2/compliance/consents', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          employeeId: row.employeeId,
          purpose: row.purpose,
          status: 'WITHDRAWN',
          method: 'ADMIN',
        }),
      });
      push('Consent withdrawn');
      load();
    } catch (e) {
      push(e.message || 'Withdraw failed');
    }
  };

  const issueLink = async () => {
    if (!linkModal) return;
    await ensureCsrf();
    try {
      const data = await apiJson('/api/v2/compliance/consents/link', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(linkModal),
      });
      const url = `${window.location.origin}${data.path}`;
      setIssuedLink(url);
      push('Public consent link issued');
    } catch (e) {
      push(e.message || 'Link issue failed');
    }
  };

  const columns = useMemo(
    () => [
      {
        key: 'sel',
        header: '',
        render: (row) =>
          canWrite ? (
            <input
              type="checkbox"
              checked={selected.has(row.employeeId)}
              onChange={() => toggle(row.employeeId)}
              aria-label={`Select ${row.employeeName}`}
            />
          ) : null,
      },
      {
        key: 'employee',
        header: 'Employee',
        render: (row) => (
          <div>
            <div className="text-sv-fg">{row.employeeName}</div>
            <div className="font-mono text-[11px] text-sv-muted">{row.employeeCode}</div>
          </div>
        ),
      },
      { key: 'purpose', header: 'Purpose' },
      {
        key: 'status',
        header: 'Status',
        render: (row) => (
          <Badge tone={row.status === 'GRANTED' ? 'success' : row.status === 'WITHDRAWN' ? 'danger' : 'neutral'}>
            {row.status}
          </Badge>
        ),
      },
      { key: 'method', header: 'Method' },
      {
        key: 'noticeVersion',
        header: 'Notice',
        render: (row) => <span className="font-mono text-xs">v{row.noticeVersion}</span>,
      },
      {
        key: 'actions',
        header: '',
        render: (row) =>
          canWrite ? (
            <div className="flex flex-wrap gap-1">
              {row.status === 'GRANTED' ? (
                <Button variant="ghost" className="px-2 py-1 text-xs" onClick={() => withdraw(row)}>
                  Withdraw
                </Button>
              ) : null}
              <Button
                variant="ghost"
                className="px-2 py-1 text-xs"
                onClick={() => {
                  setIssuedLink(null);
                  setLinkModal({ employeeId: row.employeeId, purpose: row.purpose });
                }}
              >
                Public link
              </Button>
            </div>
          ) : null,
      },
    ],
    [canWrite, selected],
  );

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end gap-3">
        <Select
          label="Status"
          value={status}
          onChange={(e) => setStatus(e.target.value)}
          options={[
            { value: '', label: 'All' },
            { value: 'GRANTED', label: 'Granted' },
            { value: 'WITHDRAWN', label: 'Withdrawn' },
            { value: 'NOT_REQUIRED', label: 'Not required' },
          ]}
        />
        <Select
          label="Purpose"
          value={purpose}
          onChange={(e) => setPurpose(e.target.value)}
          options={[
            { value: '', label: 'All' },
            { value: 'MONITORING', label: 'Monitoring' },
            { value: 'VOICE_PASSPORT', label: 'Voice passport' },
          ]}
        />
        <Button variant="ghost" onClick={load}>
          Refresh
        </Button>
      </div>

      {canWrite && selected.size > 0 ? (
        <div className="flex flex-wrap items-end gap-2 rounded border border-sv-border bg-sv-elevated/40 p-3">
          <span className="text-sm text-sv-fg">{selected.size} selected</span>
          <Select
            value={bulkPurpose}
            onChange={(e) => setBulkPurpose(e.target.value)}
            options={[
              { value: 'MONITORING', label: 'Monitoring' },
              { value: 'VOICE_PASSPORT', label: 'Voice passport' },
            ]}
          />
          <Select
            value={bulkStatus}
            onChange={(e) => setBulkStatus(e.target.value)}
            options={[
              { value: 'GRANTED', label: 'Grant' },
              { value: 'WITHDRAWN', label: 'Withdraw' },
              { value: 'NOT_REQUIRED', label: 'Not required' },
            ]}
          />
          <Button onClick={bulk}>Apply bulk</Button>
        </div>
      ) : null}

      {loading ? (
        <p className="text-sm text-sv-muted">Loading…</p>
      ) : items.length === 0 ? (
        <EmptyState title="No consent records" description="Grant consent per employee or issue a public link." />
      ) : (
        <Table columns={columns} rows={items} rowKey={(r) => r.id} />
      )}

      <Modal
        open={!!linkModal}
        onClose={() => setLinkModal(null)}
        title="Public consent link"
      >
        <p className="mb-3 text-sm text-sv-muted">
          OTP-less token page for the employee. Share the URL once; it expires and is single-use.
        </p>
        {issuedLink ? (
          <p className="break-all font-mono text-xs text-sv-accent">{issuedLink}</p>
        ) : (
          <Button onClick={issueLink}>Issue link</Button>
        )}
      </Modal>
    </div>
  );
}

function RetentionTab({ canWrite }) {
  const { push } = useToast();
  const [data, setData] = useState(/** @type {Record<string, any> | null} */ (null));
  const [draft, setDraft] = useState({
    retentionDays: 90,
    consentNoticeText: '',
    playCallNotice: false,
    consentLanguages: 'en,hi',
  });
  const [ttsText, setTtsText] = useState('This call is monitored for security.');
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    try {
      const snap = await apiJson('/api/v2/compliance/retention');
      setData(snap);
      setDraft({
        retentionDays: snap.retentionDays ?? 90,
        consentNoticeText: snap.consentNoticeText || '',
        playCallNotice: !!snap.playCallNotice,
        consentLanguages: snap.consentLanguages || 'en,hi',
      });
    } catch (e) {
      push(e.message || 'Failed to load retention');
    }
  }, [push]);

  useEffect(() => {
    load();
  }, [load]);

  const save = async () => {
    setSaving(true);
    await ensureCsrf();
    try {
      const snap = await apiJson('/api/v2/compliance/settings', {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(draft),
      });
      setData(snap);
      push('Compliance settings saved');
    } catch (e) {
      push(e.message || 'Save failed');
    } finally {
      setSaving(false);
    }
  };

  const uploadTts = async () => {
    await ensureCsrf();
    try {
      const fd = new FormData();
      fd.append('ttsText', ttsText);
      const res = await apiFetch('/api/v2/compliance/notice-asset', { method: 'POST', body: fd });
      if (!res.ok) throw new Error(`upload HTTP ${res.status}`);
      push('Notice asset stored');
      load();
    } catch (e) {
      push(e.message || 'Upload failed');
    }
  };

  const purgeNow = async () => {
    if (!window.confirm('Run retention purge for this tenant now?')) return;
    await ensureCsrf();
    try {
      const stats = await apiJson('/api/v2/compliance/retention/purge-now', { method: 'POST' });
      push(`Purged ticks=${stats.sessionTicks} dossiers=${stats.forensicDossiers}`);
      load();
    } catch (e) {
      push(e.message || 'Purge failed');
    }
  };

  if (!data) return <p className="text-sm text-sv-muted">Loading retention…</p>;

  const stats = data.lastPurgeStats || {};

  return (
    <div className="grid max-w-3xl gap-6">
      <section className="space-y-3">
        <h2 className="font-display text-lg text-sv-fg">Settings</h2>
        <label className="block text-xs text-sv-muted">
          Retention days (7–365)
          <Input
            type="number"
            min={7}
            max={365}
            className="mt-1"
            value={draft.retentionDays}
            disabled={!canWrite}
            onChange={(e) => setDraft((d) => ({ ...d, retentionDays: Number(e.target.value) }))}
          />
        </label>
        <label className="block text-xs text-sv-muted">
          Consent notice (rich text limited)
          <textarea
            className="mt-1 min-h-[120px] w-full rounded border border-sv-border bg-sv-bg px-3 py-2 text-sm text-sv-fg"
            value={draft.consentNoticeText}
            disabled={!canWrite}
            maxLength={8000}
            onChange={(e) => setDraft((d) => ({ ...d, consentNoticeText: e.target.value }))}
          />
        </label>
        <label className="block text-xs text-sv-muted">
          Languages
          <Input
            className="mt-1"
            value={draft.consentLanguages}
            disabled={!canWrite}
            onChange={(e) => setDraft((d) => ({ ...d, consentLanguages: e.target.value }))}
          />
        </label>
        <label className="flex items-center gap-2 text-sm text-sv-fg">
          <input
            type="checkbox"
            checked={draft.playCallNotice}
            disabled={!canWrite}
            onChange={(e) => setDraft((d) => ({ ...d, playCallNotice: e.target.checked }))}
          />
          Play call-start notice audio
        </label>
        {canWrite ? (
          <div className="flex flex-wrap gap-2">
            <Button onClick={save} disabled={saving}>
              Save settings
            </Button>
            <Button variant="ghost" onClick={purgeNow}>
              Purge now
            </Button>
          </div>
        ) : null}
      </section>

      {canWrite ? (
        <section className="space-y-2">
          <h2 className="font-display text-lg text-sv-fg">Call-start announcement</h2>
          <p className="text-xs text-sv-muted">
            Upload TTS script (or WAV via API). Stored as a tenant file — not call PCM.
          </p>
          <textarea
            className="min-h-[80px] w-full rounded border border-sv-border bg-sv-bg px-3 py-2 text-sm"
            value={ttsText}
            onChange={(e) => setTtsText(e.target.value)}
          />
          <Button onClick={uploadTts}>Store TTS notice asset</Button>
          {data.noticeAssetId ? (
            <p className="font-mono text-xs text-sv-muted">Active asset {data.noticeAssetId}</p>
          ) : null}
        </section>
      ) : null}

      <section className="space-y-2">
        <h2 className="font-display text-lg text-sv-fg">Purge schedule</h2>
        <dl className="grid grid-cols-2 gap-2 text-sm">
          <dt className="text-sv-muted">Last purge</dt>
          <dd>{data.lastPurgeAt || 'Never'}</dd>
          <dt className="text-sv-muted">Next scheduled</dt>
          <dd>{data.nextScheduledPurge || '—'}</dd>
          <dt className="text-sv-muted">Ticks deleted</dt>
          <dd>{stats.sessionTicks ?? '—'}</dd>
          <dt className="text-sv-muted">Dossiers deleted</dt>
          <dd>{stats.forensicDossiers ?? '—'}</dd>
        </dl>
        <p className="text-xs text-sv-muted">{data.auditChainNote}</p>
      </section>
    </div>
  );
}

function DpdpTab() {
  const [data, setData] = useState(/** @type {Record<string, any> | null} */ (null));
  const { push } = useToast();

  useEffect(() => {
    apiJson('/api/v2/compliance/dpdp-mapping')
      .then(setData)
      .catch((e) => push(e.message || 'Failed to load mapping'));
  }, [push]);

  if (!data) return <p className="text-sm text-sv-muted">Loading…</p>;

  return (
    <div className="space-y-3">
      <p className="text-sm text-sv-muted">{data.disclaimer}</p>
      <table className="w-full border-collapse text-left text-sm">
        <thead className="text-sv-muted">
          <tr>
            <th className="border-b border-sv-border px-2 py-2">Obligation</th>
            <th className="border-b border-sv-border px-2 py-2">Feature that supports it</th>
            <th className="border-b border-sv-border px-2 py-2">Evidence</th>
          </tr>
        </thead>
        <tbody>
          {(data.items || []).map((row) => (
            <tr key={row.obligation} className="border-b border-sv-border/60">
              <td className="px-2 py-2 text-sv-fg">{row.obligation}</td>
              <td className="px-2 py-2 text-sv-muted">{row.feature}</td>
              <td className="px-2 py-2">
                {String(row.evidence).startsWith('/') ? (
                  <Link className="text-sv-accent underline-offset-2 hover:underline" to={row.evidence}>
                    {row.evidence}
                  </Link>
                ) : (
                  <span className="font-mono text-xs">{row.evidence}</span>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function DsrTab({ canWrite }) {
  const { push } = useToast();
  const [q, setQ] = useState('');
  const [hits, setHits] = useState([]);
  const [employeeId, setEmployeeId] = useState('');
  const [employeeLabel, setEmployeeLabel] = useState('');
  const [passport, setPassport] = useState(null);
  const [consentVp, setConsentVp] = useState(/** @type {string | null} */ (null));
  const [exportJson, setExportJson] = useState(null);
  const [eraseOpen, setEraseOpen] = useState(false);
  const [confirm, setConfirm] = useState('');
  const [wavFile, setWavFile] = useState(/** @type {File | null} */ (null));
  const [recordedFile, setRecordedFile] = useState(/** @type {File | null} */ (null));
  const [recording, setRecording] = useState(false);
  const [recordSecs, setRecordSecs] = useState(0);
  const [enrolError, setEnrolError] = useState(/** @type {string | null} */ (null));
  const mediaRecRef = useRef(/** @type {MediaRecorder | null} */ (null));
  const chunksRef = useRef(/** @type {Blob[]} */ ([]));
  const streamRef = useRef(/** @type {MediaStream | null} */ (null));
  const tickRef = useRef(/** @type {number | null} */ (null));

  useEffect(() => {
    return () => {
      if (tickRef.current != null) window.clearInterval(tickRef.current);
      streamRef.current?.getTracks().forEach((t) => t.stop());
      try {
        mediaRecRef.current?.stop();
      } catch {
        /* ignore */
      }
    };
  }, []);

  const search = async () => {
    try {
      const params = new URLSearchParams({ q, page: '0', size: '20' });
      const data = await apiJson(`/api/v2/directory/employees?${params}`);
      setHits(data.items || data.content || []);
    } catch (e) {
      push(e.message || 'Search failed');
    }
  };

  const refreshConsent = async (id) => {
    try {
      const data = await apiJson(`/api/v2/compliance/consents?purpose=VOICE_PASSPORT`);
      const row = (data.items || []).find((c) => c.employeeId === id);
      setConsentVp(row ? row.status : 'NONE');
    } catch {
      setConsentVp(null);
    }
  };

  const selectEmployee = async (id, label) => {
    setEmployeeId(id);
    setEmployeeLabel(label || id);
    setExportJson(null);
    setEnrolError(null);
    setWavFile(null);
    setRecordedFile(null);
    try {
      setPassport(await apiJson(`/api/v2/compliance/passports/${id}`));
    } catch {
      setPassport({ enrolled: false });
    }
    await refreshConsent(id);
  };

  const grantPassportConsent = async () => {
    await ensureCsrf();
    try {
      await apiJson('/api/v2/compliance/consents', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          employeeId,
          purpose: 'VOICE_PASSPORT',
          status: 'GRANTED',
          method: 'ADMIN',
        }),
      });
      push('VOICE_PASSPORT consent GRANTED');
      await refreshConsent(employeeId);
    } catch (e) {
      push(e.message || 'Grant consent failed');
    }
  };

  const doExport = async () => {
    if (!employeeId) return;
    try {
      const data = await apiJson(`/api/v2/compliance/employees/${employeeId}/export`);
      setExportJson(data);
      push('Export ready');
    } catch (e) {
      push(e.message || 'Export failed');
    }
  };

  const doErase = async () => {
    await ensureCsrf();
    try {
      await apiJson(`/api/v2/compliance/employees/${employeeId}/erase`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ confirm: 'ERASE' }),
      });
      push('Employee data erased');
      setEraseOpen(false);
      setConfirm('');
      selectEmployee(employeeId, employeeLabel);
    } catch (e) {
      push(e.message || 'Erase failed');
    }
  };

  const stopTracks = () => {
    streamRef.current?.getTracks().forEach((t) => t.stop());
    streamRef.current = null;
    if (tickRef.current != null) {
      window.clearInterval(tickRef.current);
      tickRef.current = null;
    }
  };

  const startRecording = async () => {
    setEnrolError(null);
    setRecordedFile(null);
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          echoCancellation: true,
          noiseSuppression: true,
          channelCount: 1,
        },
      });
      streamRef.current = stream;
      const mime = MediaRecorder.isTypeSupported('audio/webm;codecs=opus')
        ? 'audio/webm;codecs=opus'
        : MediaRecorder.isTypeSupported('audio/webm')
          ? 'audio/webm'
          : '';
      const rec = mime ? new MediaRecorder(stream, { mimeType: mime }) : new MediaRecorder(stream);
      chunksRef.current = [];
      rec.ondataavailable = (e) => {
        if (e.data?.size) chunksRef.current.push(e.data);
      };
      rec.onstop = async () => {
        stopTracks();
        setRecording(false);
        try {
          const raw = new Blob(chunksRef.current, { type: rec.mimeType || 'audio/webm' });
          const wavBlob = await mediaBlobToWav(raw);
          const file = new File([wavBlob], 'enrol-record.wav', { type: 'audio/wav' });
          setRecordedFile(file);
          push('Recording converted to WAV — ready to enrol');
        } catch (e) {
          setEnrolError(e.message || 'Could not convert recording to WAV');
          push(e.message || 'Recording convert failed');
        }
      };
      mediaRecRef.current = rec;
      setRecordSecs(0);
      tickRef.current = window.setInterval(() => setRecordSecs((s) => s + 1), 1000);
      rec.start(250);
      setRecording(true);
    } catch (e) {
      setEnrolError(e.message || 'Microphone permission denied');
      push(e.message || 'Mic unavailable');
    }
  };

  const stopRecording = () => {
    const rec = mediaRecRef.current;
    if (rec && rec.state !== 'inactive') {
      rec.stop();
    } else {
      stopTracks();
      setRecording(false);
    }
  };

  const enrolWithFile = async (file) => {
    const fd = new FormData();
    fd.append('file', file);
    const res = await apiFetch(`/api/v2/compliance/passports/${employeeId}/enrol`, {
      method: 'POST',
      body: fd,
      timeoutMs: 60_000,
    });
    if (!res.ok) {
      const body = await res.json().catch(() => ({}));
      const msg = body.message || body.error || `Enrol HTTP ${res.status}`;
      throw Object.assign(new Error(msg), { code: body.error, body });
    }
    return res.json();
  };

  const enrol = async (mode) => {
    setEnrolError(null);
    await ensureCsrf();
    try {
      let data;
      if (mode === 'wav') {
        if (!wavFile) {
          setEnrolError('Choose a WAV file first');
          return;
        }
        data = await enrolWithFile(wavFile);
      } else if (mode === 'record') {
        if (!recordedFile) {
          setEnrolError('Record a short clip first (3–10s)');
          return;
        }
        data = await enrolWithFile(recordedFile);
      } else {
        data = await apiJson(`/api/v2/compliance/passports/${employeeId}/enrol`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ audioRef: 'synthetic:enrol' }),
        });
      }
      setPassport(data);
      push(data.enrolled ? 'Passport status: Enrolled' : 'Enrol finished');
    } catch (e) {
      const code = e.code || e.body?.error;
      const msg =
        code === 'CONSENT_REQUIRED'
          ? `Blocked: ${e.message || 'VOICE_PASSPORT consent GRANTED is required'}`
          : e.message || 'Enrol failed';
      setEnrolError(msg);
      push(msg);
    }
  };

  const deletePassport = async () => {
    await ensureCsrf();
    try {
      await apiJson(`/api/v2/compliance/passports/${employeeId}`, { method: 'DELETE' });
      setPassport({ enrolled: false, employeeId });
      push('Passport deleted');
    } catch (e) {
      push(e.message || 'Delete failed');
    }
  };

  return (
    <div className="grid max-w-3xl gap-4">
      <div className="flex flex-wrap gap-2">
        <Input
          placeholder="Search employees"
          value={q}
          onChange={(e) => setQ(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && search()}
        />
        <Button onClick={search}>Search</Button>
      </div>
      <ul className="divide-y divide-sv-border rounded border border-sv-border">
        {hits.map((h) => (
          <li key={h.id}>
            <button
              type="button"
              className="flex w-full items-center justify-between px-3 py-2 text-left text-sm hover:bg-sv-elevated/50"
              onClick={() => selectEmployee(h.id, h.fullName || h.name)}
            >
              <span>{h.fullName || h.name}</span>
              <span className="font-mono text-xs text-sv-muted">{h.employeeCode}</span>
            </button>
          </li>
        ))}
      </ul>

      {employeeId ? (
        <section className="space-y-3 rounded border border-sv-border p-4">
          <p className="text-sm text-sv-fg">{employeeLabel}</p>
          <p className="font-mono text-xs text-sv-muted">{employeeId}</p>
          <p className="text-sm text-sv-fg">
            VOICE_PASSPORT consent:{' '}
            <Badge
              tone={
                consentVp === 'GRANTED' ? 'success' : consentVp === 'WITHDRAWN' ? 'danger' : 'neutral'
              }
            >
              {consentVp || '…'}
            </Badge>
          </p>
          <p className="text-sm text-sv-fg">
            Voice passport:{' '}
            {passport?.enrolled ? (
              <Badge tone="success">Enrolled · {passport.modelVersion}</Badge>
            ) : (
              <Badge tone="neutral">Not enrolled</Badge>
            )}
          </p>
          {enrolError ? <p className="text-sm text-red-400">{enrolError}</p> : null}
          {canWrite ? (
            <div className="flex flex-col gap-3">
              <div className="flex flex-wrap gap-2">
                <Button variant="ghost" onClick={grantPassportConsent}>
                  Grant VOICE_PASSPORT consent
                </Button>
                <Button onClick={doExport}>Export JSON</Button>
                <Button variant="ghost" onClick={() => enrol('synthetic')}>
                  {passport?.enrolled ? 'Re-enrol' : 'Enrol'} (synthetic)
                </Button>
                {passport?.enrolled ? (
                  <Button variant="ghost" onClick={deletePassport}>
                    Delete passport
                  </Button>
                ) : null}
                <Button
                  variant="ghost"
                  className="text-red-400"
                  onClick={() => {
                    setConfirm('');
                    setEraseOpen(true);
                  }}
                >
                  Erase employee data
                </Button>
              </div>

              <div className="space-y-2 rounded border border-sv-border/80 bg-sv-elevated/20 p-3">
                <p className="text-xs font-medium text-sv-fg">Enrol audio</p>
                <div className="flex flex-wrap items-center gap-2">
                  {!recording ? (
                    <Button variant="ghost" onClick={startRecording}>
                      Record mic
                    </Button>
                  ) : (
                    <Button onClick={stopRecording}>Stop ({recordSecs}s)</Button>
                  )}
                  {recordedFile ? (
                    <>
                      <Badge tone="success">Clip ready · {(recordedFile.size / 1024).toFixed(1)} KB</Badge>
                      <Button onClick={() => enrol('record')}>Enrol from recording</Button>
                      <Button
                        variant="ghost"
                        className="text-xs"
                        onClick={() => setRecordedFile(null)}
                      >
                        Clear
                      </Button>
                    </>
                  ) : (
                    <span className="text-[11px] text-sv-muted">Speak 3–10s, then Stop</span>
                  )}
                </div>
                <div className="flex flex-wrap items-center gap-2 border-t border-sv-border/60 pt-2">
                  <input
                    type="file"
                    accept="audio/wav,.wav"
                    className="text-xs text-sv-muted"
                    onChange={(e) => setWavFile(e.target.files?.[0] || null)}
                  />
                  <Button variant="ghost" onClick={() => enrol('wav')} disabled={!wavFile}>
                    Enrol from WAV
                  </Button>
                </div>
                <p className="text-[11px] text-sv-muted">
                  Mic capture is converted to WAV in the browser, then sent to ml-engine in RAM only
                  (never stored). Prefer a quiet room and a single speaker.
                </p>
              </div>
            </div>
          ) : null}
          {exportJson ? (
            <pre className="max-h-64 overflow-auto rounded bg-sv-elevated p-3 font-mono text-[11px] text-sv-muted">
              {JSON.stringify(exportJson, null, 2)}
            </pre>
          ) : null}
        </section>
      ) : null}

      <Modal open={eraseOpen} onClose={() => setEraseOpen(false)} title="Confirm erasure">
        <p className="mb-3 text-sm text-sv-muted">
          Deletes passport, removes phones, anonymises directory name, nulls session employee refs.
          Type <strong>ERASE</strong> to confirm.
        </p>
        <Input value={confirm} onChange={(e) => setConfirm(e.target.value)} placeholder="ERASE" />
        <div className="mt-3 flex justify-end gap-2">
          <Button variant="ghost" onClick={() => setEraseOpen(false)}>
            Cancel
          </Button>
          <Button disabled={confirm !== 'ERASE'} onClick={doErase}>
            Erase
          </Button>
        </div>
      </Modal>
    </div>
  );
}

/**
 * MediaRecorder blob (webm/ogg) → mono PCM WAV for ml-engine /enrol.
 * @param {Blob} blob
 * @returns {Promise<Blob>}
 */
async function mediaBlobToWav(blob) {
  const ab = await blob.arrayBuffer();
  const ctx = new AudioContext();
  try {
    const decoded = await ctx.decodeAudioData(ab.slice(0));
    const ch0 = decoded.getChannelData(0);
    // Downmix if multi-channel
    let samples = ch0;
    if (decoded.numberOfChannels > 1) {
      const mixed = new Float32Array(decoded.length);
      for (let c = 0; c < decoded.numberOfChannels; c++) {
        const data = decoded.getChannelData(c);
        for (let i = 0; i < data.length; i++) mixed[i] += data[i] / decoded.numberOfChannels;
      }
      samples = mixed;
    }
    return encodePcm16Wav(samples, decoded.sampleRate);
  } finally {
    await ctx.close().catch(() => {});
  }
}

/**
 * @param {Float32Array} samples
 * @param {number} sampleRate
 * @returns {Blob}
 */
function encodePcm16Wav(samples, sampleRate) {
  const numChannels = 1;
  const bitsPerSample = 16;
  const blockAlign = (numChannels * bitsPerSample) / 8;
  const byteRate = sampleRate * blockAlign;
  const dataSize = samples.length * 2;
  const buffer = new ArrayBuffer(44 + dataSize);
  const view = new DataView(buffer);
  const writeStr = (offset, str) => {
    for (let i = 0; i < str.length; i++) view.setUint8(offset + i, str.charCodeAt(i));
  };
  writeStr(0, 'RIFF');
  view.setUint32(4, 36 + dataSize, true);
  writeStr(8, 'WAVE');
  writeStr(12, 'fmt ');
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true);
  view.setUint16(22, numChannels, true);
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, byteRate, true);
  view.setUint16(32, blockAlign, true);
  view.setUint16(34, bitsPerSample, true);
  writeStr(36, 'data');
  view.setUint32(40, dataSize, true);
  let off = 44;
  for (let i = 0; i < samples.length; i++) {
    const s = Math.max(-1, Math.min(1, samples[i]));
    view.setInt16(off, s < 0 ? s * 0x8000 : s * 0x7fff, true);
    off += 2;
  }
  return new Blob([buffer], { type: 'audio/wav' });
}

function FairnessTab() {
  const [report, setReport] = useState(null);
  useEffect(() => {
    apiJson('/api/v2/compliance/fairness').then(setReport).catch(() => setReport(null));
  }, []);
  if (!report) return <p className="text-sm text-sv-muted">Loading…</p>;
  return (
    <div className="max-w-xl space-y-2">
      <Badge tone="neutral">{report.status}</Badge>
      <p className="text-sm text-sv-muted">{report.message}</p>
      <p className="text-xs text-sv-muted">F16 evaluation harness will populate group FPR series here.</p>
    </div>
  );
}
