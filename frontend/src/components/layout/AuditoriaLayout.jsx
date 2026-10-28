import { useState } from "react";
import { Outlet } from "react-router-dom";
import Sidebar from "./Sidebar.jsx";
import Topbar from "./Topbar.jsx";
import Footer from "./Footer.jsx";
import { AUDITORIA_MODULES } from "../../config/auditoriaModules.js";
import "./workspace.css";

/** Shell del área de Auditoría: mismo esqueleto que Rentas y Caja, con sus módulos. */
export default function AuditoriaLayout() {
  const [menuOpen, setMenuOpen] = useState(false);

  return (
    <div className="workspace-app">
      <a className="workspace-skip" href="#workspace-content">Ir al contenido</a>
      <Sidebar
        open={menuOpen}
        onClose={() => setMenuOpen(false)}
        modules={AUDITORIA_MODULES}
        homePath="/auditor"
        homeLabel="Dashboard"
        areaTag="Auditoría"
        sectionLabel="Consulta"
        footerLines={["Módulo 5 — Rentas", "Acceso de sólo lectura"]}
      />

      <div className="workspace-body">
        <Topbar
          onOpenMenu={() => setMenuOpen(true)}
          menuOpen={menuOpen}
          areaLabel="Área de control"
          areaName="Auditoría de Rentas"
        />
        <main id="workspace-content" className="workspace-main" tabIndex={-1}>
          <Outlet />
        </main>
        <Footer
          areaName="Auditoría — Dirección General de Rentas"
          areaEmail="auditoria@ciudaduade.gob.ar"
        />
      </div>
    </div>
  );
}
