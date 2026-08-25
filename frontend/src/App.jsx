import { BrowserRouter, Navigate, Route, Routes } from "react-router-dom";
import { AuthProvider, useAuth } from "./context/AuthContext.jsx";
import ProtectedRoute from "./routes/ProtectedRoute.jsx";
import RentasLayout from "./components/layout/RentasLayout.jsx";
import CajaLayout from "./components/layout/CajaLayout.jsx";
import { homePathForRole } from "./config/workspaces.js";
import LoginPage from "./pages/LoginPage.jsx";
import DashboardPage from "./pages/DashboardPage.jsx";
import NotFoundPage from "./pages/NotFoundPage.jsx";
import ContribuyentesPage from "./pages/rentas/ContribuyentesPage.jsx";
import LiquidacionesPage from "./pages/rentas/LiquidacionesPage.jsx";
import DeudasPage from "./pages/rentas/DeudasPage.jsx";
import BoletasPage from "./pages/rentas/BoletasPage.jsx";
import PagosPage from "./pages/rentas/PagosPage.jsx";
import PlanesPage from "./pages/rentas/PlanesPage.jsx";
import ExencionesPage from "./pages/rentas/ExencionesPage.jsx";
import TicketsPage from "./pages/rentas/TicketsPage.jsx";
import EventosPage from "./pages/rentas/EventosPage.jsx";
import CajaDashboardPage from "./pages/caja/CajaDashboardPage.jsx";
import CobrosPage from "./pages/caja/CobrosPage.jsx";
import ContribuyentesCajaPage from "./pages/caja/ContribuyentesCajaPage.jsx";
import ContribuyenteDetallePage from "./pages/caja/ContribuyenteDetallePage.jsx";
import PagosCajaPage from "./pages/caja/PagosCajaPage.jsx";
import BoletasCajaPage from "./pages/caja/BoletasCajaPage.jsx";

/**
 * Rutas de las dos áreas de trabajo del módulo.
 *
 * `/rentas/*` es el back-office de Personal y Supervisor —la bitácora de eventos exige
 * rol supervisor—; `/caja/*` es la ventanilla del Cajero. Cada rol entra a la suya y
 * `ProtectedRoute` devuelve a su panel a quien intente cruzarse.
 */
export default function App() {
  return (
    <AuthProvider>
      <BrowserRouter>
        <Routes>
          <Route path="/login" element={<LoginPage />} />

          <Route element={<ProtectedRoute roles={["PERSONAL", "SUPERVISOR"]} />}>
            <Route path="/rentas" element={<RentasLayout />}>
              <Route index element={<DashboardPage />} />
              <Route path="contribuyentes" element={<ContribuyentesPage />} />
              <Route path="liquidaciones" element={<LiquidacionesPage />} />
              <Route path="deudas" element={<DeudasPage />} />
              <Route path="boletas" element={<BoletasPage />} />
              <Route path="pagos" element={<PagosPage />} />
              <Route path="planes" element={<PlanesPage />} />
              <Route path="exenciones" element={<ExencionesPage />} />
              <Route path="tickets" element={<TicketsPage />} />

              <Route element={<ProtectedRoute roles={["SUPERVISOR"]} />}>
                <Route path="eventos" element={<EventosPage />} />
              </Route>

              <Route path="*" element={<NotFoundPage />} />
            </Route>
          </Route>

          <Route element={<ProtectedRoute roles={["CAJERO"]} />}>
            <Route path="/caja" element={<CajaLayout />}>
              <Route index element={<CajaDashboardPage />} />
              <Route path="cobros" element={<CobrosPage />} />
              <Route path="contribuyentes" element={<ContribuyentesCajaPage />} />
              <Route path="contribuyentes/:taxpayerId" element={<ContribuyenteDetallePage />} />
              <Route path="pagos" element={<PagosCajaPage />} />
              <Route path="boletas" element={<BoletasCajaPage />} />

              <Route path="*" element={<NotFoundPage />} />
            </Route>
          </Route>

          <Route path="/" element={<HomeRedirect />} />
          <Route path="*" element={<HomeRedirect />} />
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  );
}

/** Manda a cada usuario al panel de su área; sin sesión, al login. */
function HomeRedirect() {
  const { isAuthenticated, user } = useAuth();
  return <Navigate to={isAuthenticated ? homePathForRole(user.role) : "/login"} replace />;
}
