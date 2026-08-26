/**
 * Rejilla de campos de una ficha: etiqueta arriba, valor abajo.
 * `items`: [{ label, value, span }] — `span: 2` ocupa el ancho completo.
 */
export default function FieldGrid({ items = [], columns = 2 }) {
  const gridClass = columns === 3 ? "sm:grid-cols-3" : columns === 4 ? "sm:grid-cols-4" : "sm:grid-cols-2";

  return (
    <dl className={`grid grid-cols-1 gap-4 ${gridClass}`}>
      {items
        .filter((item) => item)
        .map((item) => (
          <div key={item.label} className={item.span === 2 ? "sm:col-span-2" : ""}>
            <dt className="text-[10px] font-semibold uppercase tracking-[0.1em] text-neutral-400">
              {item.label}
            </dt>
            <dd className="mt-0.5 text-[13px] text-neutral-700 break-words">
              {item.value ?? <span className="text-neutral-300">—</span>}
            </dd>
          </div>
        ))}
    </dl>
  );
}
