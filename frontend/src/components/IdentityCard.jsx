import PropTypes from 'prop-types';
import { Badge } from '@/components/ui/Badge.jsx';
import { useAnimatedNumber } from '@/hooks/useAnimatedNumber.js';
import { IDENTITY_VERDICTS, TRUNK_CLASSES } from '@/lib/reasonMeta.js';
import { palette, riskRamp } from '@/theme.js';

/**
 * Caller identity card — Context §12 five-stage pipeline surface.
 *
 * Trunk badges use provenance colours (task brief). Passport verdicts keep
 * {@link IDENTITY_VERDICTS.IMPERSONATION_HUMAN} and
 * {@link IDENTITY_VERDICTS.IMPERSONATION_SYNTHETIC} visually distinct —
 * different attacker class, different response.
 *
 * @param {Object} props
 * @param {TelemetryFrame | null | undefined} props.frame
 * @param {string} [props.className]
 */
export function IdentityCard({ frame, className = '' }) {
  const id = frame?.identity;
  const cli = id?.cli ?? '—';
  const trunk = id?.cliTrunk ?? 'UNKNOWN';
  const dir = directoryFields(id?.directoryMatch);
  const claimedName = id?.claimedIdentity ?? null;
  const claimedRole = id?.claimedRole ?? null;
  const mismatch = id?.cliVsClaimMismatch === true;
  const passport = id?.voicePassport;
  const presence = id?.presenceConflict;
  const cosineTarget =
    typeof passport?.cosine === 'number' ? passport.cosine : Number.NaN;
  const cosineAnim = useAnimatedNumber(
    Number.isFinite(cosineTarget) ? cosineTarget : 0,
  );
  const cosineDisplay = Number.isFinite(cosineTarget)
    ? cosineAnim.toFixed(2)
    : '—';
  const watermarkNote = watermarkAttributionNote(frame);

  return (
    <div className={`flex flex-col gap-2 text-[11px] ${className}`}>
      {/* CLI + trunk */}
      <div className="flex flex-wrap items-start justify-between gap-1.5">
        <div className="min-w-0">
          <p className="font-mono text-[9px] uppercase tracking-wider text-sv-muted">CLI</p>
          <p className="truncate font-mono text-xs text-sv-fg" title={cli}>
            {cli}
          </p>
        </div>
        <TrunkBadge trunk={trunk} />
      </div>

      {/* Directory for CLI */}
      <div>
        <p className="font-mono text-[9px] uppercase tracking-wider text-sv-muted">
          Directory (CLI)
        </p>
        {dir ? (
          <p className="truncate text-sv-fg" title={`${dir.name} · ${dir.role}`}>
            <span className="font-medium">{dir.name}</span>
            <span className="text-sv-muted"> · {dir.role}</span>
            {dir.employeeId ? (
              <span className="ml-1 font-mono text-[10px] text-sv-muted">
                ({dir.employeeId})
              </span>
            ) : null}
          </p>
        ) : (
          <p className="italic text-sv-muted">No directory match</p>
        )}
      </div>

      {/* Spoken claim — SEPARATE field from CLI directory */}
      <div className="rounded border border-sv-border/80 bg-sv-elevated/40 px-2 py-1.5">
        <p className="font-mono text-[9px] uppercase tracking-wider text-sv-muted">
          Spoken claim
        </p>
        {claimedName || claimedRole ? (
          <p className="text-sv-fg">
            <span className="font-medium">{claimedName ?? '—'}</span>
            {claimedRole ? (
              <span className="text-sv-muted"> · role {claimedRole}</span>
            ) : null}
          </p>
        ) : (
          <p className="italic text-sv-muted">No spoken identity yet</p>
        )}
      </div>

      {mismatch ? (
        <div
          className="rounded border px-2 py-1.5 text-center font-semibold uppercase tracking-wide"
          style={{
            borderColor: `${riskRamp.critical}99`,
            background: `${riskRamp.critical}22`,
            color: riskRamp.critical,
          }}
          role="alert"
        >
          CLI ↔ claim mismatch
        </div>
      ) : null}

      {/* Voice Passport */}
      <div>
        <p className="mb-0.5 font-mono text-[9px] uppercase tracking-wider text-sv-muted">
          Voice passport
        </p>
        <div className="flex flex-wrap items-center gap-1.5">
          <Badge variant={passport?.enrolled ? 'accent' : 'neutral'}>
            {passport?.enrolled ? 'Enrolled' : 'Not enrolled'}
          </Badge>
          <span className="font-mono tabular-nums text-sv-muted">
            cos <span className="text-sv-fg">{cosineDisplay}</span>
          </span>
          <VerdictBadge verdict={passport?.verdict} />
        </div>
      </div>

      {presence ? (
        <p
          className="rounded border px-2 py-1 font-mono text-[10px]"
          style={{
            borderColor: `${
              String(presence.expected || presence.observed || '').includes('ON_LEAVE') ||
              String(presence.status || '').includes('ON_LEAVE')
                ? riskRamp.critical
                : riskRamp.watch
            }66`,
            background: `${
              String(presence.expected || presence.observed || '').includes('ON_LEAVE') ||
              String(presence.status || '').includes('ON_LEAVE')
                ? riskRamp.critical
                : riskRamp.watch
            }14`,
            color: palette.fg,
          }}
        >
          <span
            className="font-semibold uppercase tracking-wide"
            style={{
              color:
                String(presence.expected || presence.observed || '').includes('ON_LEAVE') ||
                String(presence.status || '').includes('ON_LEAVE')
                  ? riskRamp.critical
                  : riskRamp.watch,
            }}
          >
            Presence ·{' '}
          </span>
          expected {presence.expected ?? '—'} · observed {presence.observed ?? '—'}
          {presence.status ? ` · ${presence.status}` : ''}
        </p>
      ) : null}

      {/* Attribution only — never a risk input (Context §10.7). */}
      {watermarkNote ? (
        <p
          className="rounded border px-2 py-1 font-mono text-[10px]"
          style={{
            borderColor: `${palette.accent}66`,
            background: `${palette.accent}14`,
            color: palette.fg,
          }}
          title="Attribution enrichment only — does not move the risk score"
        >
          <span
            className="font-semibold uppercase tracking-wide"
            style={{ color: palette.accent }}
          >
            Attribution ·{' '}
          </span>
          {watermarkNote}
        </p>
      ) : null}
    </div>
  );
}

IdentityCard.propTypes = {
  frame: PropTypes.object,
  className: PropTypes.string,
};

/**
 * Surface WATERMARK_DETECTED as an identity attribution note (not a risk cue).
 * @param {TelemetryFrame | null | undefined} frame
 * @returns {string | null}
 */
function watermarkAttributionNote(frame) {
  const reasons = Array.isArray(frame?.topReasons) ? frame.topReasons : [];
  const hit = reasons.find(
    (r) => String(r?.code || '').toUpperCase() === 'WATERMARK_DETECTED',
  );
  if (!hit) return null;
  const text = typeof hit.text === 'string' && hit.text.trim() ? hit.text.trim() : null;
  if (text) return text;
  return 'Known generative-audio watermark detected (attribution only)';
}

/**
 * @param {Object} props
 * @param {string} props.trunk
 */
function TrunkBadge({ trunk }) {
  const t = String(trunk || 'UNKNOWN').toUpperCase();
  const meta = trunkMeta(t);
  return (
    <span
      className="inline-flex max-w-full shrink-0 items-center rounded border px-1.5 py-0.5 font-mono text-[9px] font-semibold uppercase tracking-wide"
      style={{
        color: meta.fg,
        borderColor: `${meta.fg}66`,
        background: `${meta.fg}18`,
      }}
      title={`Trunk provenance: ${t}`}
    >
      {meta.label}
    </span>
  );
}

TrunkBadge.propTypes = {
  trunk: PropTypes.string.isRequired,
};

/**
 * @param {Object} props
 * @param {string | undefined} props.verdict
 */
function VerdictBadge({ verdict }) {
  const v = String(verdict || IDENTITY_VERDICTS.INCONCLUSIVE).toUpperCase();
  const meta = verdictMeta(v);
  return (
    <span
      className="inline-flex max-w-full items-center rounded border px-1.5 py-0.5 font-mono text-[9px] font-semibold uppercase tracking-wide"
      style={{
        color: meta.fg,
        borderColor: `${meta.fg}88`,
        background: meta.bg,
        ...(meta.dashed
          ? { borderStyle: 'dashed', letterSpacing: '0.06em' }
          : null),
      }}
      title={meta.title}
    >
      {meta.label}
    </span>
  );
}

VerdictBadge.propTypes = {
  verdict: PropTypes.string,
};

/** @param {string} trunk */
function trunkMeta(trunk) {
  if (trunk === TRUNK_CLASSES.INTERNAL_PBX) {
    return { label: 'Internal PBX', fg: riskRamp.clear };
  }
  if (trunk === TRUNK_CLASSES.REGISTERED_EXTERNAL) {
    return { label: 'Registered ext.', fg: riskRamp.watch };
  }
  if (trunk === TRUNK_CLASSES.UNREGISTERED_SIP) {
    return { label: 'Unregistered SIP', fg: riskRamp.critical };
  }
  if (trunk === TRUNK_CLASSES.WITHHELD) {
    return { label: 'Withheld', fg: riskRamp.critical };
  }
  // Fixture drift (EXTERNAL_SIP) → treat as unregistered-class
  if (trunk.includes('UNREG') || trunk.includes('EXTERNAL')) {
    return { label: trunk.replace(/_/g, ' '), fg: riskRamp.elevated };
  }
  return { label: trunk.replace(/_/g, ' '), fg: palette.muted };
}

/** @param {string} verdict */
function verdictMeta(verdict) {
  if (verdict === IDENTITY_VERDICTS.VERIFIED) {
    return {
      label: 'Verified',
      fg: riskRamp.clear,
      bg: `${riskRamp.clear}18`,
      title: 'Cosine match + low spoof probability',
      dashed: false,
    };
  }
  if (verdict === IDENTITY_VERDICTS.IMPERSONATION_HUMAN) {
    return {
      label: 'Human impersonation',
      fg: riskRamp.watch,
      bg: `${riskRamp.watch}20`,
      title:
        'Low cosine + low spoof — different person speaking (possible legitimate delegate)',
      dashed: false,
    };
  }
  if (verdict === IDENTITY_VERDICTS.IMPERSONATION_SYNTHETIC) {
    return {
      label: 'Synthetic clone',
      fg: riskRamp.critical,
      bg: `${riskRamp.critical}22`,
      title: 'Deepfake / TTS clone — high spoof probability',
      dashed: true,
    };
  }
  return {
    label: 'Inconclusive',
    fg: palette.muted,
    bg: `${palette.border}66`,
    title: 'Not enrolled, mid-band cosine, or channel mismatch',
    dashed: false,
  };
}

/**
 * @param {unknown} match
 * @returns {{ name: string, role: string, employeeId?: string } | null}
 */
function directoryFields(match) {
  if (!match || typeof match !== 'object') return null;
  const m = /** @type {Record<string, unknown>} */ (match);
  const name = typeof m.name === 'string' ? m.name : null;
  const role = typeof m.role === 'string' ? m.role : null;
  const employeeId = typeof m.employeeId === 'string' ? m.employeeId : undefined;
  if (!name && !role && !employeeId) return null;
  return {
    name: name ?? '—',
    role: role ?? '—',
    employeeId,
  };
}
