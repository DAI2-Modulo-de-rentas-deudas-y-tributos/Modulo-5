import { useState } from "react";
import { Outlet } from "react-router-dom";
import Sidebar from "./Sidebar.jsx";
import Topbar from "./Topbar.jsx";
import Footer from "./Footer.jsx";
import { modulesForRole } from "../../config/modules.js";
import { useAuth } from "../../context/AuthContext.jsx";
import "./workspace.css";

/** Shell del área de trabajo: navegación por módulos + contenido + footer institucional. */
export default function RentasLayout() {
  const [menuOpen, setMenuOpen] = useState(false);
  const { user } = useAuth();

  return (
    <div className="workspace-app">
      <a className="workspace-skip" href="#workspace-content">Ir al contenido</a>
      <Sidebar
        open={menuOpen}
        onClose={() => setMenuOpen(false)}
        modules={modulesForRole(user.role)}
        homePath="/rentas"
        areaTag="Rentas"
        footerLines={["Módulo 5 — Rentas", "Gestión 2026"]}
      />

      <div className="workspace-body">
        <Topbar onOpenMenu={() => setMenuOpen(true)} menuOpen={menuOpen} areaName="Personal de Rentas" />
        <main id="workspace-content" className="workspace-main" tabIndex={-1}>
          <Outlet />
        </main>
        <Footer areaName="Dirección General de Rentas" areaEmail="rentas@ciudaduade.gob.ar" />
      </div>
    </div>
  );
}
