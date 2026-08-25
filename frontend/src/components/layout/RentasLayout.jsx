import { useState } from "react";
import { Outlet } from "react-router-dom";
import Sidebar from "./Sidebar.jsx";
import Topbar from "./Topbar.jsx";
import Footer from "./Footer.jsx";

/** Shell del área de trabajo: navegación por módulos + contenido + footer institucional. */
export default function RentasLayout() {
  const [menuOpen, setMenuOpen] = useState(false);

  return (
    <div className="flex min-h-screen">
      <Sidebar open={menuOpen} onClose={() => setMenuOpen(false)} />

      <div className="flex min-w-0 flex-1 flex-col">
        <Topbar onOpenMenu={() => setMenuOpen(true)} />
        <main className="flex-1">
          <Outlet />
        </main>
        <Footer areaName="Dirección General de Rentas" areaEmail="rentas@ciudaduade.gob.ar" />
      </div>
    </div>
  );
}
