import { useState } from "react";
import { Outlet } from "react-router-dom";
import Sidebar from "./Sidebar.jsx";
import Topbar from "./Topbar.jsx";
import Footer from "./Footer.jsx";
import { modulesForRole } from "../../config/modules.js";
import { useAuth } from "../../context/AuthContext.jsx";

/** Shell del área de trabajo: navegación por módulos + contenido + footer institucional. */
export default function RentasLayout() {
  const [menuOpen, setMenuOpen] = useState(false);
  const { user } = useAuth();

  return (
    <div className="flex min-h-screen">
      <Sidebar
        open={menuOpen}
        onClose={() => setMenuOpen(false)}
        modules={modulesForRole(user.role)}
        homePath="/rentas"
        areaTag="Rentas"
        footerLines={["Módulo 5 — Rentas", "Gestión 2026"]}
      />

      <div className="flex min-w-0 flex-1 flex-col">
        <Topbar onOpenMenu={() => setMenuOpen(true)} areaName="Personal de Rentas" />
        <main className="flex-1">
          <Outlet />
        </main>
        <Footer areaName="Dirección General de Rentas" areaEmail="rentas@ciudaduade.gob.ar" />
      </div>
    </div>
  );
}
