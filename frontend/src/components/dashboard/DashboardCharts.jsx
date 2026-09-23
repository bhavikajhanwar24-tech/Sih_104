/**
 * Lightweight SVG charts for F14 dashboard (no chart library).
 */

const LEVEL_COLORS = {
  LEVEL_1_SILENT: '#3d8b6e',
  LEVEL_2_SOFT_NUDGE: '#c4a35a',
  LEVEL_3_STEP_UP_MFA: '#d97706',
  LEVEL_3_HARD_INTERVENTION: '#ea580c',
  LEVEL_4_AUTO_HOLD: '#dc2626',
  LEVEL_5_TERMINATE: '#991b1b',
  UNKNOWN: '#6b7280',
};

function levelColor(level) {
  if (!level) return LEVEL_COLORS.UNKNOWN;
  for (const [k, c] of Object.entries(LEVEL_COLORS)) {
    if (String(level).includes(k.replace('LEVEL_', '')) || level === k) return c;
  }
  if (String(level).includes('LEVEL_5')) return LEVEL_COLORS.LEVEL_5_TERMINATE;
  if (String(level).includes('LEVEL_4')) return LEVEL_COLORS.LEVEL_4_AUTO_HOLD;
  if (String(level).includes('LEVEL_3')) return LEVEL_COLORS.LEVEL_3_STEP_UP_MFA;
  if (String(level).includes('LEVEL_2')) return LEVEL_COLORS.LEVEL_2_SOFT_NUDGE;
  if (String(level).includes('LEVEL_1')) return LEVEL_COLORS.LEVEL_1_SILENT;
  return LEVEL_COLORS.UNKNOWN;
}

function shortLevel(level) {
  const s = String(level || '—');
  const m = s.match(/LEVEL_(\d)/);
  return m ? `L${m[1]}` : s.slice(0, 10);
}

/**
 * Vertical bar chart — calls by max intervention level.
 * @param {{ rows?: Array<{ level: string, count: number }>, height?: number }} props
 */
export function LevelBarChart({ rows = [], height = 160 }) {
  const data = (rows || []).map((r) => ({
    label: shortLevel(r.level),
    full: r.level,
    count: Number(r.count) || 0,
    color: levelColor(r.level),
  }));
  if (!data.length) {
    return <p className="py-8 text-center text-xs text-sv-muted">No level data yet</p>;
  }
  const max = Math.max(1, ...data.map((d) => d.count));
  const pad = { t: 12, r: 8, b: 28, l: 28 };
  const w = 320;
  const h = height;
  const innerW = w - pad.l - pad.r;
  const innerH = h - pad.t - pad.b;
  const gap = 8;
  const barW = Math.max(12, (innerW - gap * (data.length - 1)) / data.length);

  return (
    <svg viewBox={`0 0 ${w} ${h}`} className="h-auto w-full" role="img" aria-label="Calls by max level">
      {[0, 0.5, 1].map((t) => {
        const y = pad.t + innerH * (1 - t);
        return (
          <g key={t}>
            <line x1={pad.l} x2={w - pad.r} y1={y} y2={y} stroke="currentColor" className="text-sv-border" strokeWidth="1" />
            <text x={pad.l - 4} y={y + 3} textAnchor="end" className="fill-sv-muted" fontSize="9" fontFamily="ui-monospace, monospace">
              {Math.round(max * t)}
            </text>
          </g>
        );
      })}
      {data.map((d, i) => {
        const bh = (d.count / max) * innerH;
        const x = pad.l + i * (barW + gap);
        const y = pad.t + innerH - bh;
        return (
          <g key={d.full}>
            <rect x={x} y={y} width={barW} height={Math.max(bh, d.count > 0 ? 2 : 0)} fill={d.color} rx="2">
              <title>{`${d.full}: ${d.count}`}</title>
            </rect>
            <text
              x={x + barW / 2}
              y={h - 10}
              textAnchor="middle"
              className="fill-sv-muted"
              fontSize="10"
              fontFamily="ui-monospace, monospace"
            >
              {d.label}
            </text>
            {d.count > 0 ? (
              <text
                x={x + barW / 2}
                y={y - 4}
                textAnchor="middle"
                className="fill-sv-fg"
                fontSize="10"
                fontFamily="ui-monospace, monospace"
              >
                {d.count}
              </text>
            ) : null}
          </g>
        );
      })}
    </svg>
  );
}

/**
 * Grouped bars — calls vs actions across today / 7d / 30d.
 * @param {{ kpis?: { today?: object, d7?: object, d30?: object }, height?: number }} props
 */
export function VolumeGroupedChart({ kpis = {}, height = 160 }) {
  const windows = [
    { key: 'today', label: 'Today' },
    { key: 'd7', label: '7d' },
    { key: 'd30', label: '30d' },
  ];
  const series = windows.map((w) => ({
    label: w.label,
    calls: Number(kpis[w.key]?.callsMonitored) || 0,
    actions: Number(kpis[w.key]?.actionsExecuted) || 0,
  }));
  const max = Math.max(1, ...series.flatMap((s) => [s.calls, s.actions]));
  const pad = { t: 16, r: 8, b: 28, l: 28 };
  const w = 320;
  const h = height;
  const innerW = w - pad.l - pad.r;
  const innerH = h - pad.t - pad.b;
  const groupW = innerW / series.length;
  const barW = Math.min(22, groupW / 2 - 4);

  return (
    <div>
      <svg viewBox={`0 0 ${w} ${h}`} className="h-auto w-full" role="img" aria-label="Calls and actions by window">
        {[0, 0.5, 1].map((t) => {
          const y = pad.t + innerH * (1 - t);
          return (
            <g key={t}>
              <line x1={pad.l} x2={w - pad.r} y1={y} y2={y} stroke="currentColor" className="text-sv-border" strokeWidth="1" />
              <text x={pad.l - 4} y={y + 3} textAnchor="end" className="fill-sv-muted" fontSize="9" fontFamily="ui-monospace, monospace">
                {Math.round(max * t)}
              </text>
            </g>
          );
        })}
        {series.map((s, i) => {
          const cx = pad.l + i * groupW + groupW / 2;
          const callsH = (s.calls / max) * innerH;
          const actH = (s.actions / max) * innerH;
          return (
            <g key={s.label}>
              <rect
                x={cx - barW - 2}
                y={pad.t + innerH - callsH}
                width={barW}
                height={Math.max(callsH, s.calls > 0 ? 2 : 0)}
                fill="#3b82f6"
                rx="2"
              >
                <title>{`Calls ${s.label}: ${s.calls}`}</title>
              </rect>
              <rect
                x={cx + 2}
                y={pad.t + innerH - actH}
                width={barW}
                height={Math.max(actH, s.actions > 0 ? 2 : 0)}
                fill="#a78bfa"
                rx="2"
              >
                <title>{`Actions ${s.label}: ${s.actions}`}</title>
              </rect>
              <text
                x={cx}
                y={h - 10}
                textAnchor="middle"
                className="fill-sv-muted"
                fontSize="10"
                fontFamily="ui-monospace, monospace"
              >
                {s.label}
              </text>
            </g>
          );
        })}
      </svg>
      <div className="mt-1 flex justify-center gap-4 text-[10px] text-sv-muted">
        <span className="inline-flex items-center gap-1">
          <span className="inline-block h-2 w-2 rounded-sm bg-[#3b82f6]" /> Calls
        </span>
        <span className="inline-flex items-center gap-1">
          <span className="inline-block h-2 w-2 rounded-sm bg-[#a78bfa]" /> Actions
        </span>
      </div>
    </div>
  );
}

/**
 * Horizontal bar list.
 * @param {{ rows?: Array<{ label: string, count: number }>, color?: string, empty?: string }} props
 */
export function HorizontalBars({ rows = [], color = '#14b8a6', empty = 'No data' }) {
  const data = (rows || []).map((r) => ({
    label: r.label,
    count: Number(r.count) || 0,
  }));
  if (!data.length) {
    return <p className="py-6 text-center text-xs text-sv-muted">{empty}</p>;
  }
  const max = Math.max(1, ...data.map((d) => d.count));
  return (
    <ul className="space-y-2" role="list">
      {data.map((d) => (
        <li key={d.label}>
          <div className="mb-0.5 flex justify-between gap-2 font-mono text-[11px]">
            <span className="truncate text-sv-fg" title={d.label}>
              {d.label}
            </span>
            <span className="shrink-0 text-sv-muted">{d.count}</span>
          </div>
          <div className="h-2 overflow-hidden rounded-full bg-sv-border/40">
            <div
              className="h-full rounded-full transition-[width] duration-500"
              style={{ width: `${(d.count / max) * 100}%`, background: color }}
            />
          </div>
        </li>
      ))}
    </ul>
  );
}

/**
 * Compact coverage meters.
 * @param {{ phonePct?: number, authPct?: number }} props
 */
export function CoverageMeters({ phonePct = 0, authPct = 0 }) {
  const items = [
    { label: 'Phone coverage', pct: Number(phonePct) || 0, color: '#22c55e' },
    { label: 'Authority coverage', pct: Number(authPct) || 0, color: '#38bdf8' },
  ];
  return (
    <div className="space-y-3">
      {items.map((it) => (
        <div key={it.label}>
          <div className="mb-1 flex justify-between text-[11px]">
            <span className="text-sv-muted">{it.label}</span>
            <span className="font-mono text-sv-fg">{Math.round(it.pct)}%</span>
          </div>
          <div className="h-2.5 overflow-hidden rounded-full bg-sv-border/40">
            <div
              className="h-full rounded-full transition-[width] duration-700"
              style={{ width: `${Math.min(100, Math.max(0, it.pct))}%`, background: it.color }}
            />
          </div>
        </div>
      ))}
    </div>
  );
}

/**
 * Donut for share of calls by level.
 * @param {{ rows?: Array<{ level: string, count: number }>, size?: number }} props
 */
export function LevelDonut({ rows = [], size = 140 }) {
  const data = (rows || [])
    .map((r) => ({ level: r.level, count: Number(r.count) || 0, color: levelColor(r.level) }))
    .filter((d) => d.count > 0);
  const total = data.reduce((a, d) => a + d.count, 0);
  if (!total) {
    return <p className="py-8 text-center text-xs text-sv-muted">No calls to chart</p>;
  }
  const r = size / 2 - 8;
  const cx = size / 2;
  const cy = size / 2;
  const stroke = 18;
  let acc = 0;
  const circumference = 2 * Math.PI * r;

  return (
    <div className="flex flex-col items-center gap-2 sm:flex-row sm:items-center sm:gap-4">
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} role="img" aria-label="Level share">
        <circle cx={cx} cy={cy} r={r} fill="none" stroke="currentColor" className="text-sv-border/40" strokeWidth={stroke} />
        {data.map((d) => {
          const frac = d.count / total;
          const dash = frac * circumference;
          const gap = circumference - dash;
          const offset = -acc * circumference + circumference * 0.25;
          acc += frac;
          return (
            <circle
              key={d.level}
              cx={cx}
              cy={cy}
              r={r}
              fill="none"
              stroke={d.color}
              strokeWidth={stroke}
              strokeDasharray={`${dash} ${gap}`}
              strokeDashoffset={offset}
              strokeLinecap="butt"
            >
              <title>{`${d.level}: ${d.count} (${Math.round(frac * 100)}%)`}</title>
            </circle>
          );
        })}
        <text x={cx} y={cy - 2} textAnchor="middle" className="fill-sv-fg" fontSize="18" fontFamily="ui-monospace, monospace" fontWeight="600">
          {total}
        </text>
        <text x={cx} y={cy + 14} textAnchor="middle" className="fill-sv-muted" fontSize="9" fontFamily="ui-monospace, monospace">
          calls
        </text>
      </svg>
      <ul className="space-y-1 text-[11px]">
        {data.map((d) => (
          <li key={d.level} className="flex items-center gap-2">
            <span className="inline-block h-2.5 w-2.5 rounded-sm" style={{ background: d.color }} />
            <span className="font-mono text-sv-muted">{shortLevel(d.level)}</span>
            <span className="font-mono text-sv-fg">{d.count}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}
