/** Contenedor blanco estándar de las secciones del panel. */
export default function Card({ title, description, actions, children, className = "" }) {
  return (
    <section
      className={`rounded-xl border border-neutral-200 bg-white overflow-hidden ${className}`}
    >
      {(title || actions) && (
        <header className="flex items-start justify-between gap-4 border-b border-neutral-100 px-5 py-4">
          <div>
            {title && <h2 className="text-[15px] font-semibold text-[#0F2C59]">{title}</h2>}
            {description && (
              <p className="mt-0.5 text-[13px] text-neutral-400">{description}</p>
            )}
          </div>
          {actions && <div className="flex shrink-0 items-center gap-2">{actions}</div>}
        </header>
      )}
      {children}
    </section>
  );
}
