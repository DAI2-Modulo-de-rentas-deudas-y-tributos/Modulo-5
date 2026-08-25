export default function PageHeader({ label, title, highlight, description }) {
  return (
    <section className="relative w-full bg-white border-b border-neutral-200 overflow-hidden">
      {/* Top accent line */}
      <div className="absolute top-0 left-0 w-full h-1.5 bg-gradient-to-r from-[#0F2C59] to-[#D63031]" />

      <div className="relative mx-auto max-w-6xl px-5 py-10 sm:py-14">
        <div className="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-6">
          <div>
            <div className="flex items-center gap-3 mb-4">
              <div className="w-8 h-[2px] bg-[#D63031]" />
              <span className="text-[#D63031] text-[12px] font-bold tracking-[0.2em] uppercase">
                {label}
              </span>
            </div>
            <h1 className="text-[2.5rem] sm:text-[3rem] font-extrabold text-[#0F2C59] tracking-[-0.02em] leading-tight mb-3">
              {title}{" "}
              {highlight && (
                <span className="text-transparent bg-clip-text bg-gradient-to-r from-[#D63031] to-[#e74c3c]">
                  {highlight}
                </span>
              )}
            </h1>
            {description && (
              <p className="text-[16px] text-neutral-500 max-w-md font-medium leading-relaxed">
                {description}
              </p>
            )}
          </div>

          {/* Decorative subtle background element */}
          <div className="hidden sm:flex shrink-0 relative items-center justify-center w-32 h-32 opacity-10">
            <div className="absolute w-full h-full border-4 border-[#0F2C59] rounded-full" />
            <div className="absolute w-24 h-24 border-4 border-[#D63031] rounded-full translate-x-4 -translate-y-4" />
          </div>
        </div>
      </div>
    </section>
  );
}
