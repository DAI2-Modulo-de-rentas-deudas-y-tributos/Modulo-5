import { useState } from "react";

/**
 * Gráfico de barras de una sola serie.
 *
 * Una serie sola no lleva leyenda: el título ya dice qué se está midiendo. Se etiqueta
 * directamente sólo el valor máximo —una cifra sobre cada barra no se lee— y el resto
 * queda en el tooltip y en la vista de tabla, que siempre está disponible.
 *
 * `data`: [{ key, label, value, hint }]
 */
const FILL = "#2563A8";

export default function BarChart({
  data = [],
  orientation = "vertical",
  formatValue = (value) => value,
  emptyText = "Sin datos para el período seleccionado.",
}) {
  const [hovered, setHovered] = useState(null);

  if (data.length === 0) {
    return <p className="py-8 text-center text-[13px] text-neutral-400">{emptyText}</p>;
  }

  const max = Math.max(...data.map((d) => d.value), 0) || 1;
  const maxKey = data.find((d) => d.value === Math.max(...data.map((x) => x.value)))?.key;

  if (orientation === "horizontal") {
    return (
      <div className="flex flex-col gap-3">
        {data.map((item) => (
          <div
            key={item.key}
            className="relative"
            onMouseEnter={() => setHovered(item.key)}
            onMouseLeave={() => setHovered(null)}
          >
            <div className="mb-1 flex items-baseline justify-between gap-3">
              <span className="text-[12px] text-neutral-600">{item.label}</span>
              <span className="text-[12px] font-semibold tabular-nums text-neutral-700">
                {formatValue(item.value)}
              </span>
            </div>
            {/* Riel de una tonalidad más clara del mismo azul: el estado se lee en toda la barra. */}
            <div className="h-3 w-full overflow-hidden rounded-r-[4px] bg-neutral-100">
              <div
                className="h-full rounded-r-[4px] transition-[width] duration-500"
                style={{
                  width: `${Math.max((item.value / max) * 100, 1)}%`,
                  backgroundColor: FILL,
                  opacity: hovered && hovered !== item.key ? 0.55 : 1,
                }}
              />
            </div>
            {hovered === item.key && item.hint && (
              <p className="mt-1 text-[11px] text-neutral-400">{item.hint}</p>
            )}
          </div>
        ))}
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-2">
      <div className="relative flex h-44 items-end gap-2 border-b border-neutral-200 pt-6">
        {/* Grilla de fondo: hairline sólida, recesiva. */}
        <div aria-hidden="true" className="pointer-events-none absolute inset-x-0 top-6 bottom-0">
          {[0, 0.5, 1].map((step) => (
            <div
              key={step}
              className="absolute inset-x-0 border-t border-neutral-100"
              style={{ top: `${step * 100}%` }}
            />
          ))}
        </div>

        {data.map((item) => {
          const height = Math.max((item.value / max) * 100, 2);
          const isMax = item.key === maxKey;
          return (
            <div
              key={item.key}
              className="relative flex min-w-0 flex-1 flex-col items-center justify-end self-stretch"
              onMouseEnter={() => setHovered(item.key)}
              onMouseLeave={() => setHovered(null)}
            >
              {(isMax || hovered === item.key) && (
                <span className="absolute -top-5 whitespace-nowrap text-[11px] font-semibold tabular-nums text-neutral-600">
                  {formatValue(item.value)}
                </span>
              )}
              <div
                className="w-full max-w-[24px] rounded-t-[4px] transition-[height] duration-500"
                style={{
                  height: `${height}%`,
                  backgroundColor: FILL,
                  opacity: hovered && hovered !== item.key ? 0.55 : 1,
                }}
                role="img"
                aria-label={`${item.label}: ${formatValue(item.value)}`}
              />
            </div>
          );
        })}
      </div>

      <div className="flex gap-2">
        {data.map((item) => (
          <span
            key={item.key}
            className="min-w-0 flex-1 truncate text-center text-[11px] text-neutral-400"
          >
            {item.label}
          </span>
        ))}
      </div>
    </div>
  );
}
