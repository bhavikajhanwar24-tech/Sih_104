import PropTypes from 'prop-types';

/**
 * @param {Object} props
 * @param {{ key: string, header: string, render?: (row: any) => React.ReactNode }[]} props.columns
 * @param {any[]} props.rows
 * @param {(row: any) => string} [props.rowKey]
 */
export function Table({ columns, rows, rowKey }) {
  return (
    <div className="overflow-x-auto rounded-lg border border-sv-border">
      <table className="min-w-full divide-y divide-sv-border text-left text-sm">
        <thead className="bg-sv-elevated">
          <tr>
            {columns.map((col) => (
              <th
                key={col.key}
                scope="col"
                className="px-4 py-3 font-display text-xs font-semibold uppercase tracking-wide text-sv-muted"
              >
                {col.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-sv-border bg-sv-panel">
          {rows.length === 0 ? (
            <tr>
              <td colSpan={columns.length} className="px-4 py-8 text-center text-sv-muted">
                No rows
              </td>
            </tr>
          ) : (
            rows.map((row, i) => (
              <tr key={rowKey ? rowKey(row) : row.id || i} className="hover:bg-sv-elevated/60">
                {columns.map((col) => (
                  <td key={col.key} className="px-4 py-3 text-sv-fg">
                    {col.render ? col.render(row) : row[col.key]}
                  </td>
                ))}
              </tr>
            ))
          )}
        </tbody>
      </table>
    </div>
  );
}

Table.propTypes = {
  columns: PropTypes.arrayOf(
    PropTypes.shape({
      key: PropTypes.string.isRequired,
      header: PropTypes.string.isRequired,
      render: PropTypes.func,
    }),
  ).isRequired,
  rows: PropTypes.array.isRequired,
  rowKey: PropTypes.func,
};
