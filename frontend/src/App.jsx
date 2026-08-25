import { BrowserRouter, Navigate, Route, Routes } from "react-router-dom";
import { AuthProvider } from "./context/AuthContext.jsx";
import ProtectedRoute from "./routes/ProtectedRoute.jsx";
import RentasLayout from "./components/layout/RentasLayout.jsx";
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

/**
 * Rutas del área de trabajo de Personal de Rentas.
 * Todo `/rentas/*` exige sesión; la bitácora de eventos además exige rol supervisor.
 */
export default function App() {
  return (
    <AuthProvider>
      <BrowserRouter>
        <Routes>
          <Route path="/login" element={<LoginPage />} />

          <Route element={<ProtectedRoute />}>
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

          <Route path="/" element={<Navigate to="/rentas" replace />} />
          <Route path="*" element={<Navigate to="/rentas" replace />} />
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  );
}
