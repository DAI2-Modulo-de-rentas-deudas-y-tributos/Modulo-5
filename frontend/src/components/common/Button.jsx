import Spinner from "../ui/Spinner.jsx";

const VARIANTS = {
  primary:
    "bg-[#0F2C59] text-white shadow-sm hover:bg-[#163d75] hover:shadow focus-visible:ring-[#0F2C59]/30",
  accent:
    "bg-[#D63031] text-white shadow-sm hover:bg-[#e74c3c] hover:shadow focus-visible:ring-[#D63031]/30",
  secondary:
    "bg-white text-neutral-700 border border-neutral-200 hover:border-neutral-300 hover:bg-neutral-50 focus-visible:ring-neutral-300",
  ghost:
    "bg-transparent text-neutral-500 hover:text-[#0F2C59] hover:bg-neutral-100 focus-visible:ring-neutral-300",
  danger:
    "bg-white text-[#D63031] border border-[#D63031]/30 hover:bg-[#D63031]/5 hover:border-[#D63031]/50 focus-visible:ring-[#D63031]/30",
};

const SIZES = {
  sm: "px-3 py-1.5 text-[12px]",
  md: "px-4 py-2.5 text-[14px]",
};

export default function Button({
  variant = "primary",
  size = "md",
  loading = false,
  disabled = false,
  className = "",
  children,
  ...props
}) {
  return (
    <button
      {...props}
      disabled={disabled || loading}
      className={`inline-flex items-center justify-center gap-2 rounded-lg font-semibold
                  transition-all duration-200 ease-[cubic-bezier(0.4,0,0.2,1)]
                  focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-offset-1
                  active:translate-y-px
                  disabled:opacity-50 disabled:cursor-not-allowed disabled:active:translate-y-0
                  ${VARIANTS[variant]} ${SIZES[size]} ${className}`}
    >
      {loading && <Spinner size="sm" className="border-white/40 border-t-white" />}
      {children}
    </button>
  );
}
