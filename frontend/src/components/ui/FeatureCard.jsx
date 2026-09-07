import { ArrowUpRight, icons } from "lucide-react";

export default function FeatureCard({
  title,
  description,
  iconName,
  itemCount,
  badge,
  onClick,
  colors = {
    accentHover: "#D63031",
    iconHover: "#D63031",
    ringFocus: "#D63031",
    badgeDefault: "bg-neutral-100 text-neutral-500",
  },
  showArrow = true,
  showItemCount = true,
  itemCountLabel = "activos",
  className = "",
}) {
  const IconComponent = icons[iconName];

  const getBadgeStyle = (badgeObj) => {
    if (!badgeObj) return "";
    return badgeObj.className || colors.badgeDefault;
  };

  return (
    <button
      type="button"
      onClick={onClick}
      className={`group relative flex flex-col gap-4 rounded-xl border border-neutral-200/80 bg-white p-5 text-left
                   transition-all duration-300 ease-[cubic-bezier(0.4,0,0.2,1)]
                   hover:shadow-[0_4px_24px_-6px_rgba(0,0,0,0.1)]
                   focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-offset-2
                   ${className}`}
      style={{
        "--hover-accent": colors.accentHover,
        "--icon-hover": colors.iconHover,
        "--ring-focus": colors.ringFocus,
      }}
      onMouseEnter={(e) => {
        e.currentTarget.style.borderColor = `${colors.accentHover}33`;
      }}
      onMouseLeave={(e) => {
        e.currentTarget.style.borderColor = "rgba(229, 231, 235, 0.8)";
      }}
    >
      {/* Accent line on hover — top edge */}
      <div
        className="absolute top-0 left-3 right-3 h-0.5 rounded-full scale-x-0 origin-left transition-transform duration-300 group-hover:scale-x-100"
        style={{ backgroundColor: colors.accentHover }}
      />

      <div className="flex items-start justify-between">
        <div
          className="text-neutral-400 transition-colors duration-300"
          style={{ "--hover-color": colors.iconHover }}
        >
          {IconComponent ? (
            <IconComponent className="h-5 w-5 group-hover:text-current" strokeWidth={1.5} style={{ color: "inherit" }} />
          ) : (
            <span className="text-xs">?</span>
          )}
        </div>
        {badge && (
          <span className={`rounded-full px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide ${getBadgeStyle(badge)}`}>
            {badge.text}
          </span>
        )}
      </div>

      <div className="flex flex-col gap-1">
        <h3 className="text-[15px] font-semibold text-neutral-900 leading-snug transition-colors">
          {title}
        </h3>
        <p className="text-[13px] text-neutral-400 leading-relaxed line-clamp-2">
          {description}
        </p>
      </div>

      <div className="mt-auto flex items-center justify-between pt-1">
        {showItemCount && itemCount !== undefined && (
          <span className="text-[11px] text-neutral-300 tabular-nums">
            {itemCount} {itemCountLabel}
          </span>
        )}
        {showArrow && (
          <ArrowUpRight
            className="h-4 w-4 text-neutral-300 transition-all duration-300 group-hover:-translate-y-0.5 group-hover:translate-x-0.5"
            strokeWidth={1.5}
            style={{ color: "inherit" }}
          />
        )}
      </div>
    </button>
  );
}
