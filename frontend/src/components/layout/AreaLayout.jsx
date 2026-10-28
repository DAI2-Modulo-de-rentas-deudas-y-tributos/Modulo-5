import { useState } from "react";
import { Outlet } from "react-router-dom";
import Sidebar from "./Sidebar.jsx";
import Topbar from "./Topbar.jsx";
import Footer from "./Footer.jsx";
import { modulesForRole } from "../../config/modules.js";
import { CAJA_MODULES } from "../../config/cajaModules.js";
import { AUDITORIA_MODULES } from "../../config/auditoriaModules.js";
import { PORTAL_MODULES } from "../../config/portalModules.js";
import { useAuth } from "../../context/AuthContext.jsx";
import "./workspace.css";

const AREAS = {
  rentas: {
    homePath: "/rentas",
    areaTag: "Rentas",
    areaName: "Personal de Rentas",
    footerLines: ["Módulo 5 — Rentas", "Gestión 2026"],
    footerArea: "Dirección General de Rentas",
    footerEmail: "rentas@ciudaduade.gob.ar",
    modules: (user) => modulesForRole(user.role),
  },
  caja: {
    homePath: "/caja",
    homeLabel: "Panel de caja",
    areaTag: "Caja",
    sectionLabel: "Ventanilla",
    areaLabel: "Ventanilla",
    areaName: "Caja de Rentas",
    footerArea: "Caja — Dirección General de Rentas",
    footerEmail: "caja@ciudaduade.gob.ar",
    modules: () => CAJA_MODULES,
    footerLines: (user) => ["Módulo 5 — Rentas", user.counter ?? "Ventanilla de caja"],
  },
  auditoria: {
    homePath: "/auditor",
    homeLabel: "Dashboard",
    areaTag: "Auditoría",
    sectionLabel: "Consulta",
    areaLabel: "Área de control",
    areaName: "Auditoría de Rentas",
    footerArea: "Auditoría — Dirección General de Rentas",
    footerEmail: "auditoria@ciudaduade.gob.ar",
    modules: () => AUDITORIA_MODULES,
    footerLines: () => ["Módulo 5 — Rentas", "Acceso de sólo lectura"],
  },
  portal: {
    homePath: "/portal",
    homeLabel: "Inicio",
    areaTag: "Mi cuenta",
    sectionLabel: "Consultas y trámites",
    areaLabel: "Portal",
    areaName: "Mi cuenta",
    footerArea: "Atención al Contribuyente",
    footerEmail: "contribuyentes@ciudaduade.gob.ar",
    modules: () => PORTAL_MODULES,
    footerLines: () => ["Municipalidad de Ciudad UADE", "Dirección General de Rentas"],
  },
};

/** Esqueleto compartido de las áreas; cada ruta selecciona su configuración. */
export default function AreaLayout({ area }) {
  const [menuOpen, setMenuOpen] = useState(false);
  const { user } = useAuth();
  const config = AREAS[area];
  if (!config) throw new Error(`Área de trabajo desconocida: ${area}`);

  const value = (field) => typeof field === "function" ? field(user) : field;

  return (
    <div className="workspace-app">
      <a className="workspace-skip" href="#workspace-content">Ir al contenido</a>
      <Sidebar
        open={menuOpen}
        onClose={() => setMenuOpen(false)}
        modules={config.modules(user)}
        homePath={config.homePath}
        homeLabel={config.homeLabel}
        areaTag={config.areaTag}
        sectionLabel={config.sectionLabel}
        footerLines={value(config.footerLines)}
      />
      <div className="workspace-body">
        <Topbar
          onOpenMenu={() => setMenuOpen(true)}
          menuOpen={menuOpen}
          areaLabel={config.areaLabel}
          areaName={config.areaName}
        />
        <main id="workspace-content" className="workspace-main" tabIndex={-1}>
          <Outlet />
        </main>
        <Footer areaName={config.footerArea} areaEmail={config.footerEmail} />
      </div>
    </div>
  );
}
