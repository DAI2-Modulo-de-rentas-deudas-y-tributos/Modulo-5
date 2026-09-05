export default function PageHeader({ label, title, highlight, description }) {
  return (
    <section className="relative w-full bg-white border-b border-neutral-200 overflow-hidden">
      {/* Top accent line */}
      <div className="absolute top-0 left-0 w-full h-1.5 bg-gradient-to-r from-[#0F2C59] to-[#D63031]" />

      <div className="relative mx-auto max-w-6xl px-5 py-10 sm:py-14">
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
          <p className="text-[16px] text-neutral-500 max-w-xl font-medium leading-relaxed">
            {description}
          </p>
        )}
      </div>
    </section>
  );
}
