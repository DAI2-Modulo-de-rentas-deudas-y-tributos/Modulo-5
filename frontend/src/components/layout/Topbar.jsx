import { useEffect, useRef, useState } from "react";
import { LogOut, Menu, ChevronDown, Building2 } from "lucide-react";
import { useAuth } from "../../context/AuthContext.jsx";

export default function Topbar({ onOpenMenu, menuOpen: navigationOpen = false, areaLabel = "Área de trabajo", areaName }) {
  const { user, logout } = useAuth();
  const [menuOpen, setMenuOpen] = useState(false);
  const menuRef = useRef(null);
  const triggerRef = useRef(null);
  const logoutRef = useRef(null);
  const initials = user.fullName.split(" ").map((part) => part[0]).slice(0, 2).join("").toUpperCase();

  useEffect(() => {
    if (!menuOpen) return undefined;
    logoutRef.current?.focus();
    const closeOnOutside = (event) => {
      if (!menuRef.current?.contains(event.target)) setMenuOpen(false);
    };
    const closeOnEscape = (event) => {
      if (event.key === "Escape") {
        setMenuOpen(false);
        triggerRef.current?.focus();
      }
    };
    document.addEventListener("pointerdown", closeOnOutside);
    document.addEventListener("keydown", closeOnEscape);
    return () => {
      document.removeEventListener("pointerdown", closeOnOutside);
      document.removeEventListener("keydown", closeOnEscape);
    };
  }, [menuOpen]);

  return (
    <header className="workspace-topbar">
      <button type="button" onClick={onOpenMenu} className="workspace-menu-trigger"
        aria-label="Abrir menú" aria-controls="workspace-navigation" aria-expanded={navigationOpen}>
        <Menu size={21} aria-hidden="true" />
      </button>
      <div className="workspace-topbar-area">
        <Building2 size={18} strokeWidth={1.5} aria-hidden="true" />
        <span>{areaLabel}</span><span className="workspace-topbar-divider">/</span>
        <strong>{areaName}</strong>
      </div>
      <div className="workspace-account" ref={menuRef}>
        <button ref={triggerRef} type="button" onClick={() => setMenuOpen((value) => !value)}
          onBlur={(event) => { if (!event.currentTarget.parentElement.contains(event.relatedTarget)) setMenuOpen(false); }}
          className="workspace-account-trigger" aria-label={`${user.fullName}, opciones de cuenta`}
          aria-haspopup="menu" aria-expanded={menuOpen}>
          <span className="workspace-avatar">{initials}</span>
          <span className="workspace-account-name"><strong>{user.fullName}</strong><span>{user.roleLabel}</span></span>
          <ChevronDown size={15} aria-hidden="true" />
        </button>
        {menuOpen && (
          <div role="menu" className="workspace-account-menu" aria-label="Cuenta">
            <div><strong>{user.fullName}</strong><span>{user.email}</span></div>
            <button ref={logoutRef} type="button" role="menuitem" onClick={logout}
              onBlur={(event) => { if (!menuRef.current?.contains(event.relatedTarget)) setMenuOpen(false); }}>
              <LogOut size={16} aria-hidden="true" />Cerrar sesión
            </button>
          </div>
        )}
      </div>
    </header>
  );
}
