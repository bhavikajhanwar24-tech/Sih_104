import PropTypes from 'prop-types';

const VARIANTS = ['default', 'flush'];
const STATUSES = ['loading', 'empty', 'error', 'ready'];

/**
 * Named dashboard panel with explicit loading / empty / error / ready states.
 *
 * @param {Object} props
 * @param {string} props.title
 * @param {string} [props.slot] grid slot name for debugging / future wiring
 * @param {'default'|'flush'} [props.variant='default']
 * @param {'loading'|'empty'|'error'|'ready'} [props.status='ready']
 * @param {string} [props.emptyMessage='waiting for audio']
 * @param {string} [props.errorMessage]
 * @param {React.ReactNode} [props.children] shown when status=ready
 * @param {string} [props.className]
 * @param {React.ReactNode} [props.action]
 */
export function Panel({
  title,
  slot,
  variant = 'default',
  status = 'ready',
  emptyMessage = 'waiting for audio',
  errorMessage = 'Something went wrong',
  children,
  className = '',
  action,
}) {
  const pad = variant === 'flush' ? '' : 'p-3';

  return (
    <section
      data-slot={slot}
      className={`flex min-h-0 min-w-0 flex-col overflow-hidden rounded border border-sv-border bg-sv-panel ${className}`}
    >
      <header className="flex shrink-0 items-center justify-between gap-2 border-b border-sv-border px-3 py-1.5">
        <h3 className="font-display text-[11px] font-semibold uppercase tracking-wider text-sv-muted">
          {title}
        </h3>
        {action}
      </header>
      <div className={`min-h-0 flex-1 overflow-auto ${pad}`}>
        {status === 'loading' ? (
          <StateMessage label="Loading…" />
        ) : null}
        {status === 'empty' ? (
          <StateMessage label={emptyMessage} />
        ) : null}
        {status === 'error' ? (
          <StateMessage label={errorMessage} tone="error" />
        ) : null}
        {status === 'ready' ? children : null}
      </div>
    </section>
  );
}

Panel.propTypes = {
  title: PropTypes.string.isRequired,
  slot: PropTypes.string,
  variant: PropTypes.oneOf(VARIANTS),
  status: PropTypes.oneOf(STATUSES),
  emptyMessage: PropTypes.string,
  errorMessage: PropTypes.string,
  children: PropTypes.node,
  className: PropTypes.string,
  action: PropTypes.node,
};

/**
 * @param {Object} props
 * @param {string} props.label
 * @param {'muted'|'error'} [props.tone='muted']
 */
function StateMessage({ label, tone = 'muted' }) {
  return (
    <div
      className={`flex h-full min-h-[4.5rem] items-center justify-center px-2 text-center text-xs ${
        tone === 'error' ? 'text-sv-fault' : 'text-sv-muted'
      }`}
      role="status"
    >
      {label}
    </div>
  );
}

StateMessage.propTypes = {
  label: PropTypes.string.isRequired,
  tone: PropTypes.oneOf(['muted', 'error']),
};
