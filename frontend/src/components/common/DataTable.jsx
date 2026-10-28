import EmptyState from "./EmptyState.jsx";

/**
 * Tabla de listados del back-office.
 *
 * `columns`: [{ key, header, render?, align?, className? }]
 * `rowKey`: función que devuelve la clave estable de cada fila.
 */
export default function DataTable({
  columns,
  rows,
  rowKey,
  loading = false,
  emptyTitle = "Sin resultados",
  emptyDescription,
  emptyIconName,
  onRowClick,
}) {
  if (loading) {
    return (
      <div className="workspace-table-loading" role="status" aria-label="Cargando información">
        <span className="sr-only">Cargando información…</span>
        {Array.from({ length: 4 }, (_, index) => (
          <div key={index} className="workspace-skeleton-row" aria-hidden="true"><span /><span /><span /></div>
        ))}
      </div>
    );
  }

  if (rows.length === 0) {
    return (
      <EmptyState
        iconName={emptyIconName}
        title={emptyTitle}
        description={emptyDescription}
      />
    );
  }

  return (
    <div className="workspace-table-region">
      <div className={`workspace-table overflow-x-auto${columns.length > 3 ? " workspace-table-wide" : ""}`}>
        <table className="w-full border-collapse text-left">
          <thead>
            <tr className="border-b border-neutral-200 bg-neutral-50/60">
              {columns.map((column) => (
                <th
                  key={column.key}
                  className={`px-4 py-3 text-[11px] font-semibold uppercase tracking-[0.08em] text-neutral-500 whitespace-nowrap first:pl-5 last:pr-5 ${
                    column.align === "right" ? "text-right" : ""
                  }`}
                >
                  {column.header}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr
                key={rowKey(row)}
                onClick={onRowClick ? () => onRowClick(row) : undefined}
                tabIndex={onRowClick ? 0 : undefined}
                onKeyDown={onRowClick ? (event) => {
                  if (event.target === event.currentTarget && (event.key === "Enter" || event.key === " ")) {
                    event.preventDefault();
                    onRowClick(row);
                  }
                } : undefined}
                className={`border-b border-neutral-100 last:border-0 transition-colors hover:bg-neutral-50/70 ${
                  onRowClick ? "cursor-pointer" : ""
                }`}
              >
                {columns.map((column) => (
                  <td
                    key={column.key}
                    className={`px-4 py-3.5 text-[13px] align-middle first:pl-5 last:pr-5 ${
                      column.align === "right"
                        ? "text-right tabular-nums font-medium text-neutral-900"
                        : "text-neutral-700"
                    } ${column.className ?? ""}`}
                  >
                    {column.render ? column.render(row) : row[column.key]}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {columns.length > 3 && <p className="workspace-table-hint">Deslizá la tabla para ver todas las columnas.</p>}
    </div>
  );
}
