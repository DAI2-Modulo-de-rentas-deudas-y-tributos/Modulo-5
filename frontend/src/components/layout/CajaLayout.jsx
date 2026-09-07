import { useState } from "react";
import { Outlet } from "react-router-dom";
import Sidebar from "./Sidebar.jsx";
import Topbar from "./Topbar.jsx";
import Footer from "./Footer.jsx";
import { CAJA_MODULES } from "../../config/cajaModules.js";
import { useAuth } from "../../context/AuthContext.jsx";

/** Shell de la ventanilla de caja: mismo esqueleto que Rentas con sus propios módulos. */
export default function CajaLayout() {
  const [menuOpen, setMenuOpen] = useState(false);
  const { user } = useAuth();

  return (
    <div className="flex min-h-screen">
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

      <div className="flex min-w-0 flex-1 flex-col">
        <Topbar onOpenMenu={() => setMenuOpen(true)} areaLabel="Ventanilla" areaName="Caja de Rentas" />
        <main className="flex-1">
          <Outlet />
        </main>
        <Footer areaName="Caja — Dirección General de Rentas" areaEmail="caja@ciudaduade.gob.ar" />
      </div>
    </div>
  );
}
