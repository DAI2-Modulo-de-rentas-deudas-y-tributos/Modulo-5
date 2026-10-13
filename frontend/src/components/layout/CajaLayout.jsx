import { useState } from "react";
import { Outlet } from "react-router-dom";
import Sidebar from "./Sidebar.jsx";
import Topbar from "./Topbar.jsx";
import Footer from "./Footer.jsx";
import { CAJA_MODULES } from "../../config/cajaModules.js";
import { useAuth } from "../../context/AuthContext.jsx";
import "./workspace.css";

/** Shell de la ventanilla de caja: mismo esqueleto que Rentas con sus propios módulos. */
export default function CajaLayout() {
  const [menuOpen, setMenuOpen] = useState(false);
  const { user } = useAuth();

  return (
    <div className="workspace-app">
      <a className="workspace-skip" href="#workspace-content">Ir al contenido</a>
      <Sidebar
        open={menuOpen}
        onClose={() => setMenuOpen(false)}
        modules={CAJA_MODULES}
        homePath="/caja"
        homeLabel="Panel de caja"
        areaTag="Caja"
        sectionLabel="Ventanilla"
        footerLines={["Módulo 5 — Rentas", user.counter ?? "Ventanilla de caja"]}
      />

      <div className="workspace-body">
        <Topbar onOpenMenu={() => setMenuOpen(true)} menuOpen={menuOpen} areaLabel="Ventanilla" areaName="Caja de Rentas" />
        <main id="workspace-content" className="workspace-main" tabIndex={-1}>
          <Outlet />
        </main>
        <Footer areaName="Caja — Dirección General de Rentas" areaEmail="caja@ciudaduade.gob.ar" />
      </div>
    </div>
  );
}
