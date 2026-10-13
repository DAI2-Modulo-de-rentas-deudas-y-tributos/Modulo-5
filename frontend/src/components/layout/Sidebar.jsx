import { useEffect, useRef } from "react";
import { NavLink } from "react-router-dom";
import { icons, LayoutDashboard, X } from "lucide-react";
import logo from "../../assets/logo.png";

export default function Sidebar({
  open, onClose, modules = [], homePath, homeLabel = "Panel de inicio",
  areaTag, sectionLabel = "Operación", footerLines = [],
}) {
  const sidebarRef = useRef(null);

  useEffect(() => {
    if (!open) return undefined;
    const previousFocus = document.activeElement;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    sidebarRef.current?.querySelector("button")?.focus();
    const onKeyDown = (event) => {
      if (event.key === "Escape") onClose();
      if (event.key !== "Tab") return;
      const items = sidebarRef.current?.querySelectorAll("a, button");
      const first = items?.[0];
      const last = items?.[items.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last?.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first?.focus();
      }
    };
    const onResize = () => { if (window.innerWidth >= 1024) onClose(); };
    document.addEventListener("keydown", onKeyDown);
    window.addEventListener("resize", onResize);
    return () => {
      document.body.style.overflow = previousOverflow;
      document.removeEventListener("keydown", onKeyDown);
      window.removeEventListener("resize", onResize);
      previousFocus?.focus();
    };
  }, [open, onClose]);

  const linkClasses = ({ isActive }) => `workspace-nav-link${isActive ? " is-active" : ""}`;

  return (
    <>
      {open && <div className="workspace-nav-backdrop" onClick={onClose} aria-hidden="true" />}
      <aside ref={sidebarRef} id="workspace-navigation" className={`workspace-sidebar${open ? " is-open" : ""}`}
        role={open ? "dialog" : undefined} aria-modal={open || undefined} aria-label={open ? `Menú de ${areaTag}` : undefined}>
        <div className="workspace-brand">
          <img src={logo} alt="" width="38" height="38" />
          <div>
            <p>Ciudad UADE</p>
            <span>Dirección General de Rentas</span>
          </div>
          <button type="button" onClick={onClose} className="workspace-menu-close" aria-label="Cerrar menú">
            <X size={19} aria-hidden="true" />
          </button>
        </div>
        <div className="workspace-area-tag"><span />{areaTag}</div>
        <nav aria-label={`Navegación de ${areaTag}`} className="workspace-navigation">
          <NavLink to={homePath} end className={linkClasses} onClick={onClose}>
            <LayoutDashboard size={18} strokeWidth={1.6} aria-hidden="true" />
            <span>{homeLabel}</span>
          </NavLink>
          <p className="workspace-nav-label">{sectionLabel}</p>
          {modules.map((module) => {
            const Icon = icons[module.iconName];
            return (
              <NavLink key={module.id} to={module.path} className={linkClasses} onClick={onClose}>
                {Icon && <Icon size={18} strokeWidth={1.6} aria-hidden="true" />}
                <span>{module.label}</span>
              </NavLink>
            );
          })}
        </nav>
        <div className="workspace-sidebar-footer">
          {footerLines.map((line) => <p key={line}>{line}</p>)}
          <span>Cerca tuyo. También en línea.</span>
        </div>
      </aside>
    </>
  );
}
