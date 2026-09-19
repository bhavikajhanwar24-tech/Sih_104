import PropTypes from 'prop-types';

/**
 * Placeholder for features not yet shipped.
 * @param {Object} props
 * @param {string} props.title
 * @param {string} [props.feature]
 * @param {string} [props.description]
 */
export function EmptyState({ title, feature, description }) {
  return (
    <div className="flex h-full min-h-[280px] flex-col items-center justify-center gap-3 px-6 text-center">
      <p className="font-display text-xl font-semibold text-sv-fg">{title}</p>
      {feature ? (
        <p className="rounded-md border border-dashed border-sv-border px-3 py-1 font-mono text-xs text-sv-accent">
          Coming in {feature}
        </p>
      ) : null}
      {description ? <p className="max-w-md text-sm text-sv-muted">{description}</p> : null}
    </div>
  );
}

EmptyState.propTypes = {
  title: PropTypes.string.isRequired,
  feature: PropTypes.string,
  description: PropTypes.string,
};
