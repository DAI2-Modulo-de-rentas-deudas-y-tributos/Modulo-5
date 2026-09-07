import { NavLink } from "react-router-dom";
import { icons, X } from "lucide-react";
import logo from "../../assets/logo.png";

/**
 * Navegación lateral del área de trabajo: un ítem por módulo funcional.
 * Es presentacional — cada layout decide qué módulos entrega y cómo se llama su área.
 */
export default function Sidebar({
  open,
  onClose,
  modules = [],
  homePath,
  homeLabel = "Panel de inicio",
  areaTag,
  sectionLabel = "Operación",
  footerLines = [],
}) {

  // El acento coral marca dónde está parado el agente; el resto queda en navy.
  const linkClasses = ({ isActive }) =>
    `relative flex items-center gap-3 rounded-lg px-3 py-2.5 text-[13px] transition-all duration-200 ${
      isActive
        ? "bg-white/10 font-semibold text-white before:absolute before:left-0 before:top-1/2 before:h-5 before:w-[3px] before:-translate-y-1/2 before:rounded-r-full before:bg-[#D63031]"
        : "font-medium text-white/60 hover:bg-white/5 hover:text-white"
    }`;

  return (
    <>
      {open && (
        <div
          className="fixed inset-0 z-30 bg-[#0F2C59]/40 lg:hidden"
          onClick={onClose}
          aria-hidden="true"
        />
      )}

      <aside
        className={`fixed inset-y-0 left-0 z-40 flex w-64 shrink-0 flex-col bg-[#0F2C59] transition-transform duration-300 lg:sticky lg:top-0 lg:h-screen lg:translate-x-0 ${
          open ? "translate-x-0" : "-translate-x-full"
        }`}
      >
        <div className="flex items-center justify-between gap-2 border-b border-white/5 px-5 py-5">
          <div className="flex items-center gap-2">
            <img src={logo} alt="Ciudad UADE" className="h-6 w-auto object-contain" />
            <div className="leading-tight">
              <p className="text-[13px] font-bold text-white">Ciudad UADE</p>
              <p className="text-[10px] font-semibold uppercase tracking-[0.15em] text-white/40">
                {areaTag}
              </p>
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="rounded p-1 text-white/60 hover:bg-white/10 lg:hidden"
            aria-label="Cerrar menú"
          >
            <X className="h-4 w-4" strokeWidth={2} />
          </button>
        </div>

        <nav className="flex flex-1 flex-col gap-1 overflow-y-auto px-3 pb-6">
          <NavLink to={homePath} end className={linkClasses} onClick={onClose}>
            {/* El inicio del área se distingue por su rótulo, no por un glifo. El hueco
                deja los rótulos alineados con los de los módulos, que sí llevan icono. */}
            <span className="h-4 w-4 shrink-0" aria-hidden="true" />
            {homeLabel}
          </NavLink>

          <p className="mt-4 px-3 pb-1 text-[10px] font-semibold uppercase tracking-[0.15em] text-white/30">
            {sectionLabel}
          </p>

          {modules.map((module) => {
            const Icon = icons[module.iconName];
            return (
              <NavLink
                key={module.id}
                to={module.path}
                className={linkClasses}
                onClick={onClose}
              >
                {Icon && <Icon className="h-4 w-4 shrink-0" strokeWidth={1.5} />}
                {module.label}
              </NavLink>
            );
          })}
        </nav>

        <div className="border-t border-white/10 bg-black/10 px-5 py-4">
          <p className="text-[11px] text-white/40 leading-relaxed">
            {footerLines.map((line, index) => (
              <span key={line}>
                {index > 0 && <br />}
                {line}
              </span>
            ))}
          </p>
        </div>
      </aside>
    </>
  );
}
