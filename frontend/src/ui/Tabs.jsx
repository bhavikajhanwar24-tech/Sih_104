import PropTypes from 'prop-types';
import clsx from 'clsx';

/**
 * @param {Object} props
 * @param {{ id: string, label: string }[]} props.tabs
 * @param {string} props.value
 * @param {(id: string) => void} props.onChange
 */
export function Tabs({ tabs, value, onChange }) {
  return (
    <div role="tablist" className="flex gap-1 border-b border-sv-border">
      {tabs.map((tab) => {
        const selected = tab.id === value;
        return (
          <button
            key={tab.id}
            type="button"
            role="tab"
            aria-selected={selected}
            onClick={() => onChange(tab.id)}
            className={clsx(
              'relative px-3 py-2 text-sm transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-sv-accent',
              selected ? 'font-medium text-sv-accent' : 'text-sv-muted hover:text-sv-fg',
            )}
          >
            {tab.label}
            {selected ? (
              <span className="absolute inset-x-1 -bottom-px h-0.5 bg-sv-accent" aria-hidden />
            ) : null}
          </button>
        );
      })}
    </div>
  );
}

Tabs.propTypes = {
  tabs: PropTypes.arrayOf(
    PropTypes.shape({ id: PropTypes.string.isRequired, label: PropTypes.string.isRequired }),
  ).isRequired,
  value: PropTypes.string.isRequired,
  onChange: PropTypes.func.isRequired,
};
