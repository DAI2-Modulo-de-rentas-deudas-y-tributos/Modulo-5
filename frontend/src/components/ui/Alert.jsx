import { CheckCircle, AlertCircle, Info, X } from "lucide-react";

const VARIANTS = {
  success: {
    bg: "bg-emerald-50 border-emerald-200",
    icon: CheckCircle,
    iconColor: "text-emerald-500",
    titleColor: "text-emerald-800",
    textColor: "text-emerald-700",
  },
  error: {
    bg: "bg-red-50 border-red-200",
    icon: AlertCircle,
    iconColor: "text-red-500",
    titleColor: "text-red-800",
    textColor: "text-red-700",
  },
  info: {
    bg: "bg-blue-50 border-blue-200",
    icon: Info,
    iconColor: "text-blue-500",
    titleColor: "text-blue-800",
    textColor: "text-blue-700",
  },
};

export default function Alert({ variant = "info", title, children, onDismiss }) {
  const config = VARIANTS[variant];
  const Icon = config.icon;

  return (
    <div className={`relative flex gap-3 rounded-lg border p-4 ${config.bg}`}>
      <Icon className={`h-5 w-5 shrink-0 mt-0.5 ${config.iconColor}`} strokeWidth={2} />
      <div className="flex-1 min-w-0">
        {title && (
          <p className={`text-[14px] font-semibold ${config.titleColor}`}>{title}</p>
        )}
        <div className={`text-[13px] ${config.textColor} ${title ? "mt-1" : ""}`}>
          {children}
        </div>
      </div>
      {onDismiss && (
        <button
          type="button"
          onClick={onDismiss}
          className="shrink-0 p-0.5 rounded hover:bg-black/5 transition-colors"
          aria-label="Cerrar"
        >
          <X className="h-4 w-4 text-neutral-400" strokeWidth={2} />
        </button>
      )}
    </div>
  );
}
