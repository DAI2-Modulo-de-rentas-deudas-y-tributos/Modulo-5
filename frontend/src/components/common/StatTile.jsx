import { icons } from "lucide-react";

/** Métrica del panel de inicio. `tone` marca cuándo el número exige acción. */
const TONES = {
  neutral: { value: "text-[#0F2C59]", icon: "bg-[#0F2C59]/5 text-[#0F2C59]/50" },
  danger: { value: "text-[#D63031]", icon: "bg-[#D63031]/5 text-[#D63031]/60" },
  success: { value: "text-emerald-600", icon: "bg-emerald-50 text-emerald-500" },
};

export default function StatTile({ label, value, hint, iconName, tone = "neutral" }) {
  const Icon = icons[iconName];
  const palette = TONES[tone];
  return (
    <div className="flex items-start justify-between gap-3 rounded-xl border border-neutral-200/70 bg-white p-5">
      <div className="min-w-0">
        <p className="text-[11px] font-semibold uppercase tracking-[0.12em] text-neutral-400">
          {label}
        </p>
        <p
          className={`mt-2 text-[26px] font-extrabold tabular-nums leading-none tracking-[-0.02em] ${palette.value}`}
        >
          {value}
        </p>
        {hint && <p className="mt-2 text-[12px] text-neutral-400 truncate">{hint}</p>}
      </div>
      {Icon && (
        <span
          className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-lg ${palette.icon}`}
        >
          <Icon className="h-[18px] w-[18px]" strokeWidth={1.5} />
        </span>
      )}
    </div>
  );
}
