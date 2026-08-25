import { NavLink } from "react-router-dom";
import { icons, X } from "lucide-react";
import logo from "../../assets/logo.png";
import { modulesForRole } from "../../config/modules.js";
import { useAuth } from "../../context/AuthContext.jsx";

/** Navegación lateral del área de trabajo: un ítem por módulo funcional. */
export default function Sidebar({ open, onClose }) {
  const { user } = useAuth();
  const modules = modulesForRole(user.role);

  const linkClasses = ({ isActive }) =>
    `flex items-center gap-3 rounded-lg px-3 py-2.5 text-[13px] font-medium transition-colors ${
      isActive
        ? "bg-white/10 text-white"
        : "text-white/60 hover:bg-white/5 hover:text-white"
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
        <div className="flex items-center justify-between gap-2 px-5 py-5">
          <div className="flex items-center gap-2">
            <img src={logo} alt="Ciudad UADE" className="h-6 w-auto object-contain" />
            <div className="leading-tight">
              <p className="text-[13px] font-bold text-white">Ciudad UADE</p>
              <p className="text-[10px] font-semibold uppercase tracking-[0.15em] text-white/40">
                Rentas
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
          <NavLink to="/rentas" end className={linkClasses} onClick={onClose}>
            {(() => {
              const Icon = icons.LayoutDashboard;
              return <Icon className="h-4 w-4 shrink-0" strokeWidth={1.5} />;
            })()}
            Panel de inicio
          </NavLink>

          <p className="mt-4 px-3 pb-1 text-[10px] font-semibold uppercase tracking-[0.15em] text-white/30">
            Operación
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

        <div className="border-t border-white/10 px-5 py-4">
          <p className="text-[11px] text-white/40 leading-relaxed">
            Módulo 5 — Rentas
            <br />
            Gestión 2026
          </p>
        </div>
      </aside>
    </>
  );
}
