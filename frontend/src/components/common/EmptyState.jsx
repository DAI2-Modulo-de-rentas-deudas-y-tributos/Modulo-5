import { icons } from "lucide-react";

export default function EmptyState({ iconName = "Inbox", title, description }) {
  const Icon = icons[iconName];
  return (
    <div className="flex flex-col items-center justify-center gap-2 px-5 py-14 text-center">
      {Icon && <Icon className="h-8 w-8 text-neutral-300" strokeWidth={1.5} />}
      <p className="text-[14px] font-semibold text-neutral-600">{title}</p>
      {description && (
        <p className="text-[13px] text-neutral-400 max-w-sm leading-relaxed">{description}</p>
      )}
    </div>
  );
}
