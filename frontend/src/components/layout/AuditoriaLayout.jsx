import { useState } from "react";
import { Outlet } from "react-router-dom";
import Sidebar from "./Sidebar.jsx";
import Topbar from "./Topbar.jsx";
import Footer from "./Footer.jsx";
import { AUDITORIA_MODULES } from "../../config/auditoriaModules.js";

/** Shell del área de Auditoría: mismo esqueleto que Rentas y Caja, con sus módulos. */
export default function AuditoriaLayout() {
  const [menuOpen, setMenuOpen] = useState(false);

  return (
    <div className="flex min-h-screen">
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

      <div className="flex min-w-0 flex-1 flex-col">
        <Topbar
          onOpenMenu={() => setMenuOpen(true)}
          areaLabel="Área de control"
          areaName="Auditoría de Rentas"
        />
        <main className="flex-1">
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
