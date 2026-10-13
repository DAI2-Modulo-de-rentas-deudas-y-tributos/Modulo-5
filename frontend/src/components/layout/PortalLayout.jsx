import { useState } from "react";
import { Outlet } from "react-router-dom";
import Sidebar from "./Sidebar.jsx";
import Topbar from "./Topbar.jsx";
import Footer from "./Footer.jsx";
import { PORTAL_MODULES } from "../../config/portalModules.js";
import "./workspace.css";

/**
 * Shell del portal del contribuyente. Mismo esqueleto que las áreas internas, pero
 * de cara al ciudadano: el menú habla en primera persona ("Mis deudas") porque quien
 * mira es el titular del legajo, no un agente municipal.
 */
export default function PortalLayout() {
  const [menuOpen, setMenuOpen] = useState(false);

  return (
    <div className="workspace-app">
      <a className="workspace-skip" href="#workspace-content">Ir al contenido</a>
      <Sidebar
        open={menuOpen}
        onClose={() => setMenuOpen(false)}
        modules={PORTAL_MODULES}
        homePath="/portal"
        homeLabel="Inicio"
        areaTag="Mi cuenta"
        sectionLabel="Consultas y trámites"
        footerLines={["Municipalidad de Ciudad UADE", "Dirección General de Rentas"]}
      />

      <div className="workspace-body">
        <Topbar
          onOpenMenu={() => setMenuOpen(true)}
          menuOpen={menuOpen}
          areaLabel="Portal"
          areaName="Mi cuenta"
        />
        <main id="workspace-content" className="workspace-main" tabIndex={-1}>
          <Outlet />
        </main>
        <Footer
          areaName="Atención al Contribuyente"
          areaEmail="contribuyentes@ciudaduade.gob.ar"
        />
      </div>
    </div>
  );
}
