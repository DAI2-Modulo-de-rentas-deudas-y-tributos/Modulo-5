import { icons } from "lucide-react";

export default function EmptyState({ iconName = "Inbox", title, description }) {
  const Icon = icons[iconName];
  return (
    <div className="flex flex-col items-center justify-center gap-3 px-5 py-16 text-center">
      {Icon && (
        <span className="flex h-12 w-12 items-center justify-center rounded-full bg-neutral-50 ring-1 ring-neutral-100">
          <Icon className="h-5 w-5 text-neutral-300" strokeWidth={1.5} />
        </span>
      )}
      <p className="text-[14px] font-semibold text-neutral-600">{title}</p>
      {description && (
        <p className="text-[13px] text-neutral-400 max-w-sm leading-relaxed">{description}</p>
      )}
    </div>
  );
}
