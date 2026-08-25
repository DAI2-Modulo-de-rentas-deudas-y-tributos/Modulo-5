import { Search } from "lucide-react";

/**
 * Barra de filtros de los listados: buscador libre + selects declarativos.
 * `filters`: [{ name, label, options }]
 */
export default function FilterBar({
  searchValue = "",
  searchPlaceholder = "Buscar…",
  onSearchChange,
  filters = [],
  values = {},
  onFilterChange,
  actions,
}) {
  return (
    <div className="flex flex-col gap-3 border-b border-neutral-100 px-5 py-4 sm:flex-row sm:items-center sm:justify-between">
      <div className="flex flex-1 flex-col gap-3 sm:flex-row sm:items-center">
        {onSearchChange && (
          <div className="relative sm:w-72">
            <Search
              className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-neutral-400"
              strokeWidth={2}
            />
            <input
              type="search"
              value={searchValue}
              onChange={(event) => onSearchChange(event.target.value)}
              placeholder={searchPlaceholder}
              aria-label={searchPlaceholder}
              className="w-full rounded-lg border border-neutral-200 bg-neutral-50 py-2 pl-9 pr-3 text-[14px] text-neutral-900 placeholder-neutral-400 outline-none transition-colors focus:border-[#D63031]/40 focus:bg-white focus:ring-2 focus:ring-[#D63031]/10"
            />
          </div>
        )}

        {filters.map((filter) => (
          <select
            key={filter.name}
            value={values[filter.name] ?? ""}
            aria-label={filter.label}
            onChange={(event) => onFilterChange(filter.name, event.target.value)}
            className="rounded-lg border border-neutral-200 bg-neutral-50 px-3 py-2 text-[13px] font-medium text-neutral-700 outline-none transition-colors focus:border-[#D63031]/40 focus:bg-white focus:ring-2 focus:ring-[#D63031]/10"
          >
            <option value="">{filter.label}: todos</option>
            {filter.options.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        ))}
      </div>

      {actions && <div className="flex items-center gap-2">{actions}</div>}
    </div>
  );
}
