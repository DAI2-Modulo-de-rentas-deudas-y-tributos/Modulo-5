import { formatDateTime, labelFor } from "../../lib/format.js";

/**
 * Historial de una entidad, del hecho más antiguo al más reciente.
 * `entries`: [{ at, status, action, actor, note }] — se muestra `action` si existe,
 * y si no la etiqueta del estado.
 */
export default function HistoryTimeline({ entries = [], emptyText = "Sin movimientos registrados." }) {
  if (entries.length === 0) {
    return <p className="text-[13px] text-neutral-400">{emptyText}</p>;
  }

  return (
    <ol className="flex flex-col">
      {entries.map((entry, index) => {
        const isLast = index === entries.length - 1;
        return (
          <li key={`${entry.at}-${index}`} className="flex gap-3">
            <div className="flex flex-col items-center">
              <span
                className={`mt-1.5 h-2 w-2 shrink-0 rounded-full ${
                  isLast ? "bg-[#D63031]" : "bg-neutral-300"
                }`}
              />
              {!isLast && <span className="w-px flex-1 bg-neutral-200" />}
            </div>
            <div className={`min-w-0 flex-1 ${isLast ? "pb-0" : "pb-4"}`}>
              <p className="text-[13px] font-medium text-neutral-800">
                {entry.action ?? labelFor(entry.status)}
              </p>
              <p className="mt-0.5 text-[12px] text-neutral-400">
                {formatDateTime(entry.at)}
                {entry.actor ? ` · ${entry.actor}` : ""}
              </p>
              {entry.note && (
                <p className="mt-1 text-[12px] text-neutral-500 leading-relaxed">{entry.note}</p>
              )}
            </div>
          </li>
        );
      })}
    </ol>
  );
}
