import { useEffect } from "react";
import { X } from "lucide-react";

export default function Modal({ open, title, description, onClose, children, footer }) {
  useEffect(() => {
    if (!open) return undefined;
    const onKeyDown = (event) => event.key === "Escape" && onClose();
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [open, onClose]);

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div
        className="absolute inset-0 bg-[#0F2C59]/40 backdrop-blur-[2px] animate-[modal-fade_180ms_ease-out]"
        onClick={onClose}
        aria-hidden="true"
      />
      <div
        role="dialog"
        aria-modal="true"
        aria-label={title}
        className="relative w-full max-w-lg max-h-[90vh] overflow-y-auto rounded-xl border border-neutral-200/70 bg-white
                   shadow-[0_20px_60px_-20px_rgba(15,44,89,0.45)] animate-[modal-in_200ms_cubic-bezier(0.4,0,0.2,1)]"
      >
        <div className="flex items-start justify-between gap-4 border-b border-neutral-100 px-5 py-4">
          <div className="min-w-0">
            <h2 className="text-[16px] font-bold tracking-[-0.01em] text-[#0F2C59]">{title}</h2>
            {description && (
              <p className="mt-1 text-[13px] leading-relaxed text-neutral-400">{description}</p>
            )}
          </div>
          <button
            type="button"
            onClick={onClose}
            className="shrink-0 rounded-lg p-1.5 text-neutral-400 transition-colors hover:bg-neutral-100 hover:text-[#0F2C59]"
            aria-label="Cerrar"
          >
            <X className="h-4 w-4 text-neutral-400" strokeWidth={2} />
          </button>
        </div>

        <div className="flex flex-col gap-4 px-5 py-5">{children}</div>

        {footer && (
          <div className="flex items-center justify-end gap-2 border-t border-neutral-100 bg-neutral-50/50 px-5 py-4">
            {footer}
          </div>
        )}
      </div>
    </div>
  );
}
