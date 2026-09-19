import { useMemo, useState } from 'react';
import PropTypes from 'prop-types';
import { MicControl } from '@/components/MicControl.jsx';
import { Badge } from '@/components/ui/Badge.jsx';
import { CHANNEL_PROFILES } from '@/contracts';
import { useAuth } from '@/context/AuthContext.jsx';
import {
  CONSENT_LANGS,
  CONSENT_STEPS,
  canEnrol,
  canErase,
  nextConsentStep,
} from '@/lib/consentFlow.js';
import { apiFetch } from '@/services/api.js';

const PURPOSE = 'VOICE_PASSPORT_ENROLMENT';
const NOTICE_VERSION = 'notice-v1-2026';

const NOTICE = Object.freeze({
  [CONSENT_LANGS.EN]:
    'I consent to enrolment of a Voice Passport biometric template for speaker verification on bank calls. Purpose is limited to VOICE_PASSPORT_ENROLMENT. I may withdraw and request erasure (DPDP §6 / §12). The embedding vector is never exposed via API.',
  [CONSENT_LANGS.HI]:
    'मैं बैंक कॉल पर वक्ता सत्यापन हेतु वॉइस पासपोर्ट बायोमेट्रिक टेम्पलेट नामांकन के लिए सहमति देता/देती हूँ। उद्देश्य केवल VOICE_PASSPORT_ENROLMENT तक सीमित है। मैं सहमति वापस ले सकता/सकती हूँ और मिटाने का अनुरोध कर सकता/सकती हूँ (DPDP §6 / §12)। एम्बेडिंग वेक्टर API से कभी नहीं लौटाया जाता।',
});

const ENROL_PROFILES = Object.freeze([
  CHANNEL_PROFILES.WEBRTC_WIDEBAND,
  CHANNEL_PROFILES.PSTN_NARROWBAND,
]);

/**
 * Consent → dual-channel enrol → metadata (never vector) → erase + tombstone.
 *
 * @param {Object} [props]
 * @param {string} [props.className]
 */
export function VoicePassportManager({ className = '' }) {
  const { username, hasRole } = useAuth();
  const [lang, setLang] = useState(CONSENT_LANGS.EN);
  const [step, setStep] = useState(CONSENT_STEPS.NOTICE);
  const [employeeId, setEmployeeId] = useState('EMP-DEMO-01');
  const [affirmed, setAffirmed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(/** @type {string | null} */ (null));
  const [profileIds, setProfileIds] = useState(/** @type {string[]} */ ([]));
  const [channelProfiles, setChannelProfiles] = useState(/** @type {string[]} */ ([]));
  const [metadata, setMetadata] = useState(/** @type {any | null} */ (null));
  const [tombstone, setTombstone] = useState(/** @type {any | null} */ (null));
  const [enrolSessionId] = useState(() => `pp-enrol-${Date.now().toString(36)}`);
  const [micProfile, setMicProfile] = useState(CHANNEL_PROFILES.WEBRTC_WIDEBAND);

  const canDelete = hasRole('COMPLIANCE') || hasRole('ADMIN');

  const notice = useMemo(() => NOTICE[lang] || NOTICE[CONSENT_LANGS.EN], [lang]);

  async function grantConsent() {
    if (!affirmed) {
      setError('Affirmative checkbox required (DPDP §6)');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const res = await apiFetch('/api/v1/passport/consent', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          employeeId: employeeId.trim(),
          purpose: PURPOSE,
          noticeVersion: NOTICE_VERSION,
          grantedBy: username || 'ui',
          method: 'AFFIRMATIVE_UI',
        }),
      });
      if (!res.ok) throw new Error(`consent HTTP ${res.status}: ${(await res.text()).slice(0, 160)}`);
      setStep(nextConsentStep(step, 'grant'));
      setTombstone(null);
      setMetadata(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'consent failed');
    } finally {
      setBusy(false);
    }
  }

  async function enrol() {
    if (!canEnrol(step)) {
      setError('Grant consent before enrolment');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      // MicControl captures live audio for the demo; Decision Plane enrol uses an opaque
      // audioRef (synthetic fixture) — Java never receives PCM.
      const res = await apiFetch('/api/v1/passport/enrol', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          employeeId: employeeId.trim(),
          channelProfile: micProfile,
          audioRef: 'synthetic:voice-passport-demo',
        }),
      });
      if (!res.ok) throw new Error(`enrol HTTP ${res.status}: ${(await res.text()).slice(0, 160)}`);
      const body = await res.json();
      const ids = Array.isArray(body.profileIds) ? body.profileIds : [];
      const channels = Array.isArray(body.channelProfiles) ? body.channelProfiles : [];
      setProfileIds(ids);
      setChannelProfiles(channels);
      setStep(nextConsentStep(CONSENT_STEPS.GRANTED, 'enrol'));

      const preferred =
        ids.find((_, i) => channels[i] === CHANNEL_PROFILES.WEBRTC_WIDEBAND) || ids[0];
      if (preferred) await loadMetadata(preferred);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'enrol failed');
    } finally {
      setBusy(false);
    }
  }

  async function loadMetadata(profileId) {
    setBusy(true);
    setError(null);
    try {
      const res = await apiFetch(`/api/v1/passport/${encodeURIComponent(profileId)}`);
      if (!res.ok) throw new Error(`metadata HTTP ${res.status}`);
      const body = await res.json();
      if (body && (body.embedding || body.vector)) {
        throw new Error('security regression: vector returned in metadata');
      }
      setMetadata(body);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'metadata failed');
    } finally {
      setBusy(false);
    }
  }

  async function erase(profileId) {
    if (!canDelete) {
      setError('Erasure requires COMPLIANCE or ADMIN');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const res = await apiFetch(`/api/v1/passport/${encodeURIComponent(profileId)}`, {
        method: 'DELETE',
      });
      if (!res.ok) throw new Error(`erase HTTP ${res.status}: ${(await res.text()).slice(0, 160)}`);
      const cert = await res.json();
      setTombstone(cert);
      setMetadata(null);
      setProfileIds((prev) => prev.filter((id) => id !== profileId));
      setStep(nextConsentStep(CONSENT_STEPS.ENROLLED, 'erase'));
    } catch (e) {
      setError(e instanceof Error ? e.message : 'erase failed');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className={`flex flex-col gap-3 ${className}`} data-testid="voice-passport-manager">
      <p className="text-[11px] text-sv-muted">
        Step <span className="font-mono text-sv-accent">{step}</span> · enrol targets{' '}
        {ENROL_PROFILES.join(' + ')} · metadata never includes the vector (DPDP §11).
      </p>

      <div className="flex flex-wrap gap-2">
        {[CONSENT_LANGS.EN, CONSENT_LANGS.HI].map((code) => (
          <button
            key={code}
            type="button"
            onClick={() => setLang(code)}
            className={`rounded border px-2 py-1 font-mono text-[10px] uppercase ${
              lang === code
                ? 'border-sv-accent text-sv-accent'
                : 'border-sv-border text-sv-muted hover:text-sv-fg'
            }`}
            data-testid={`consent-lang-${code}`}
          >
            {code}
          </button>
        ))}
        <Badge variant={step === CONSENT_STEPS.ERASED ? 'fault' : 'accent'}>{step}</Badge>
      </div>

      <label className="block text-[11px] uppercase tracking-wide text-sv-muted">
        Employee ID
        <input
          className="mt-1 w-full rounded border border-sv-border bg-sv-bg px-2 py-1.5 font-mono text-xs text-sv-fg"
          value={employeeId}
          onChange={(ev) => setEmployeeId(ev.target.value)}
          data-testid="passport-employee-id"
        />
      </label>

      <div className="rounded border border-sv-border bg-sv-bg/60 p-3 text-sm leading-relaxed text-sv-fg">
        {notice}
      </div>

      <label className="flex items-start gap-2 text-xs text-sv-fg">
        <input
          type="checkbox"
          checked={affirmed}
          onChange={(ev) => setAffirmed(ev.target.checked)}
          className="mt-0.5"
          data-testid="consent-affirm"
        />
        <span>I have read the notice and grant specific consent ({lang}).</span>
      </label>

      <div className="flex flex-wrap gap-2">
        <button
          type="button"
          disabled={busy || !affirmed}
          onClick={() => void grantConsent()}
          className="rounded bg-sv-accent px-3 py-1.5 text-xs font-medium text-sv-bg disabled:opacity-40"
          data-testid="consent-grant"
        >
          POST /passport/consent
        </button>
        <button
          type="button"
          disabled={busy || !canEnrol(step)}
          onClick={() => void enrol()}
          className="rounded border border-sv-border px-3 py-1.5 text-xs text-sv-fg hover:border-sv-accent disabled:opacity-40"
          data-testid="passport-enrol"
        >
          Enrol ({CHANNEL_PROFILES.WEBRTC_WIDEBAND} + {CHANNEL_PROFILES.PSTN_NARROWBAND})
        </button>
      </div>

      <div className="grid gap-2 md:grid-cols-2">
        <div>
          <label className="mb-1 block text-[10px] uppercase text-sv-muted">
            Mic channel for capture
          </label>
          <select
            className="mb-2 w-full rounded border border-sv-border bg-sv-bg px-2 py-1 font-mono text-[11px] text-sv-fg"
            value={micProfile}
            onChange={(ev) => setMicProfile(ev.target.value)}
          >
            {ENROL_PROFILES.map((p) => (
              <option key={p} value={p}>
                {p}
              </option>
            ))}
          </select>
          <MicControl sessionId={enrolSessionId} />
        </div>
        <div className="rounded border border-sv-border bg-sv-elevated/40 p-3">
          <p className="font-mono text-[10px] uppercase text-sv-muted">Profiles</p>
          {profileIds.length === 0 ? (
            <p className="mt-2 text-xs text-sv-muted">None enrolled yet.</p>
          ) : (
            <ul className="mt-2 space-y-2">
              {profileIds.map((id, i) => (
                <li key={id} className="flex flex-wrap items-center gap-2 text-xs">
                  <span className="font-mono text-sv-fg">{id}</span>
                  <span className="text-sv-muted">{channelProfiles[i] || '—'}</span>
                  <button
                    type="button"
                    disabled={busy}
                    onClick={() => void loadMetadata(id)}
                    className="rounded border border-sv-border px-1.5 py-0.5 text-[10px] hover:border-sv-accent"
                  >
                    GET metadata
                  </button>
                  {canErase(step) && canDelete ? (
                    <button
                      type="button"
                      disabled={busy}
                      onClick={() => void erase(id)}
                      className="rounded border border-red-500/40 px-1.5 py-0.5 text-[10px] text-red-300 hover:border-red-400"
                      data-testid={`passport-erase-${id}`}
                    >
                      DELETE erase
                    </button>
                  ) : null}
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>

      {metadata ? (
        <pre
          className="max-h-48 overflow-auto rounded border border-sv-border bg-sv-bg p-2 font-mono text-[10px] text-sv-muted"
          data-testid="passport-metadata"
        >
          {JSON.stringify(metadata, null, 2)}
        </pre>
      ) : null}

      {tombstone ? (
        <div
          className="rounded border border-amber-500/40 bg-amber-500/10 p-3"
          data-testid="passport-tombstone"
        >
          <p className="font-mono text-[10px] uppercase tracking-wide text-amber-300">
            Deletion tombstone
          </p>
          <p className="mt-1 font-mono text-[11px] text-sv-fg break-all">
            {tombstone.tombstoneHash}
          </p>
          <p className="mt-1 text-[11px] text-sv-muted">
            profile {tombstone.profileId} · erased {String(tombstone.erasedAt || '').slice(0, 19)} ·
            audit #{tombstone.auditBlockIndex}
          </p>
        </div>
      ) : null}

      {error ? (
        <p className="text-sm text-red-400" role="alert">
          {error}
        </p>
      ) : null}
    </div>
  );
}

VoicePassportManager.propTypes = {
  className: PropTypes.string,
};
