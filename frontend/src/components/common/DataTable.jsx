import Spinner from "../ui/Spinner.jsx";
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
      <div className="flex items-center justify-center gap-3 px-5 py-14">
        <Spinner />
        <span className="text-[13px] text-neutral-400">Cargando información…</span>
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
    <div className="overflow-x-auto">
      <table className="w-full border-collapse text-left">
        <thead>
          <tr className="border-b border-neutral-200">
            {columns.map((column) => (
              <th
                key={column.key}
                className={`px-4 py-3 text-[11px] font-semibold uppercase tracking-[0.08em] text-neutral-400 whitespace-nowrap ${
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
              className={`border-b border-neutral-100 last:border-0 transition-colors ${
                onRowClick ? "cursor-pointer hover:bg-neutral-50" : ""
              }`}
            >
              {columns.map((column) => (
                <td
                  key={column.key}
                  className={`px-4 py-3 text-[13px] text-neutral-700 align-middle ${
                    column.align === "right" ? "text-right tabular-nums" : ""
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
  );
}
