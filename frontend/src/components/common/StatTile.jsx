import { icons } from "lucide-react";

/** Métrica del panel de inicio. `tone` marca cuándo el número exige acción. */
const TONES = {
  neutral: "text-[#0F2C59]",
  danger: "text-[#D63031]",
  success: "text-emerald-600",
};

export default function StatTile({ label, value, hint, iconName, tone = "neutral" }) {
  const Icon = icons[iconName];
  return (
    <div className="flex items-start justify-between gap-3 rounded-xl border border-neutral-200 bg-white p-5">
      <div className="min-w-0">
        <p className="text-[11px] font-semibold uppercase tracking-[0.12em] text-neutral-400">
          {label}
        </p>
        <p className={`mt-2 text-[26px] font-extrabold tabular-nums leading-none ${TONES[tone]}`}>
          {value}
        </p>
        {hint && <p className="mt-2 text-[12px] text-neutral-400 truncate">{hint}</p>}
      </div>
      {Icon && <Icon className="h-5 w-5 shrink-0 text-neutral-300" strokeWidth={1.5} />}
    </div>
  );
}
