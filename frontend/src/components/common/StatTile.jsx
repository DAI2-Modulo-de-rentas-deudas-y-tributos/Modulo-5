import { icons } from "lucide-react";

/** Métrica del panel; los colores semánticos se reservan para estados del dato. */
export default function StatTile({ label, value, hint, iconName, tone = "neutral" }) {
  const Icon = icons[iconName];
  return (
    <article className="workspace-stat" data-tone={tone}>
      <div className="workspace-stat-heading">
        <p>{label}</p>
        {Icon && <Icon size={17} strokeWidth={1.5} aria-hidden="true" />}
      </div>
      <p className="workspace-stat-value">{value}</p>
      {hint && <p className="workspace-stat-hint">{hint}</p>}
    </article>
  );
}
