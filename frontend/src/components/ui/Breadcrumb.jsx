import { ChevronRight, Home } from "lucide-react";

export default function Breadcrumb({ items = [], onNavigate, homeLabel = "Portal de Ayuda" }) {
  return (
    <nav aria-label="Breadcrumb" className="flex items-center gap-1 flex-wrap">
      <button
        type="button"
        onClick={() => onNavigate(-1)}
        className="flex items-center gap-1 text-[13px] text-neutral-400 hover:text-[#0F2C59] transition-colors"
      >
        <Home className="h-3.5 w-3.5" strokeWidth={2} />
        <span>{homeLabel}</span>
      </button>

      {items.map((item, index) => {
        const isLast = index === items.length - 1;
        return (
          <span key={item.id || index} className="flex items-center gap-1">
            <ChevronRight className="h-3 w-3 text-neutral-300" strokeWidth={2} />
            {isLast ? (
              <span className="text-[13px] font-medium text-neutral-700">
                {item.label}
              </span>
            ) : (
              <button
                type="button"
                onClick={() => onNavigate(index)}
                className="text-[13px] text-neutral-400 hover:text-[#0F2C59] transition-colors"
              >
                {item.label}
              </button>
            )}
          </span>
        );
      })}
    </nav>
  );
}
