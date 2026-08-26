import { useState } from "react";
import { LogOut, Menu, ChevronDown } from "lucide-react";
import { useAuth } from "../../context/AuthContext.jsx";

/** Barra superior: apertura del menú en mobile e identidad del agente logueado. */
export default function Topbar({ onOpenMenu, areaLabel = "Área de trabajo", areaName }) {
  const { user, logout } = useAuth();
  const [menuOpen, setMenuOpen] = useState(false);

  const initials = user.fullName
    .split(" ")
    .map((part) => part[0])
    .slice(0, 2)
    .join("")
    .toUpperCase();

  return (
    <header className="sticky top-0 z-20 flex items-center justify-between gap-4 border-b border-neutral-200 bg-white/90 px-5 py-3 backdrop-blur">
      <button
        type="button"
        onClick={onOpenMenu}
        className="rounded-lg p-2 text-neutral-500 hover:bg-neutral-100 lg:hidden"
        aria-label="Abrir menú"
      >
        <Menu className="h-4 w-4" strokeWidth={2} />
      </button>

      <div className="hidden flex-col leading-tight lg:flex">
        <span className="text-[11px] font-bold uppercase tracking-[0.15em] text-[#D63031]">
          {areaLabel}
        </span>
        <span className="text-[14px] font-semibold text-[#0F2C59]">{areaName}</span>
      </div>

      <div className="relative ml-auto">
        <button
          type="button"
          onClick={() => setMenuOpen((value) => !value)}
          className="flex items-center gap-2.5 rounded-lg px-2 py-1.5 transition-colors hover:bg-neutral-100"
          aria-haspopup="menu"
          aria-expanded={menuOpen}
        >
          <span className="flex h-8 w-8 items-center justify-center rounded-full bg-[#0F2C59] text-[12px] font-bold text-white">
            {initials}
          </span>
          <span className="hidden text-left leading-tight sm:block">
            <span className="block text-[13px] font-semibold text-neutral-800">
              {user.fullName}
            </span>
            <span className="block text-[11px] text-neutral-400">{user.roleLabel}</span>
          </span>
          <ChevronDown className="h-3.5 w-3.5 text-neutral-400" strokeWidth={2} />
        </button>

        {menuOpen && (
          <div
            role="menu"
            className="absolute right-0 mt-2 w-60 rounded-xl border border-neutral-200 bg-white p-1.5 shadow-[0_8px_30px_-12px_rgba(15,44,89,0.3)]"
          >
            <div className="px-3 py-2">
              <p className="text-[13px] font-semibold text-neutral-800">{user.fullName}</p>
              <p className="text-[12px] text-neutral-400 truncate">{user.email}</p>
            </div>
            <div className="my-1 h-px bg-neutral-100" />
            <button
              type="button"
              role="menuitem"
              onClick={logout}
              className="flex w-full items-center gap-2 rounded-lg px-3 py-2 text-[13px] font-medium text-[#D63031] transition-colors hover:bg-[#D63031]/5"
            >
              <LogOut className="h-4 w-4" strokeWidth={2} />
              Cerrar sesión
            </button>
          </div>
        )}
      </div>
    </header>
  );
}
