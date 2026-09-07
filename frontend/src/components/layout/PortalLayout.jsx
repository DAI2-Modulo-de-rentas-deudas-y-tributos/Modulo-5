import { useState } from "react";
import { Outlet } from "react-router-dom";
import Sidebar from "./Sidebar.jsx";
import Topbar from "./Topbar.jsx";
import Footer from "./Footer.jsx";
import { PORTAL_MODULES } from "../../config/portalModules.js";

/**
 * Shell del portal del contribuyente. Mismo esqueleto que las áreas internas, pero
 * de cara al ciudadano: el menú habla en primera persona ("Mis deudas") porque quien
 * mira es el titular del legajo, no un agente municipal.
 */
export default function PortalLayout() {
  const [menuOpen, setMenuOpen] = useState(false);

  return (
    <div className="flex min-h-screen">
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

      <div className="flex min-w-0 flex-1 flex-col">
        <Topbar
          onOpenMenu={() => setMenuOpen(true)}
          areaLabel="Portal"
          areaName="Mi cuenta"
        />
        <main className="flex-1">
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
