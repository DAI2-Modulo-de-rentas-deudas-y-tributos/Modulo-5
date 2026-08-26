import { BrowserRouter, Navigate, Route, Routes } from "react-router-dom";
import { AuthProvider, useAuth } from "./context/AuthContext.jsx";
import ProtectedRoute from "./routes/ProtectedRoute.jsx";
import RentasLayout from "./components/layout/RentasLayout.jsx";
import CajaLayout from "./components/layout/CajaLayout.jsx";
import AuditoriaLayout from "./components/layout/AuditoriaLayout.jsx";
import PortalLayout from "./components/layout/PortalLayout.jsx";
import { homePathForRole } from "./config/workspaces.js";
import LoginPage from "./pages/LoginPage.jsx";
import DashboardPage from "./pages/DashboardPage.jsx";
import NotFoundPage from "./pages/NotFoundPage.jsx";
import ContribuyentesPage from "./pages/rentas/ContribuyentesPage.jsx";
import ConfiguracionTributosPage from "./pages/rentas/ConfiguracionTributosPage.jsx";
import LiquidacionesPage from "./pages/rentas/LiquidacionesPage.jsx";
import DeudasPage from "./pages/rentas/DeudasPage.jsx";
import BoletasPage from "./pages/rentas/BoletasPage.jsx";
import PagosPage from "./pages/rentas/PagosPage.jsx";
import PlanesPage from "./pages/rentas/PlanesPage.jsx";
import RefinanciacionPage from "./pages/rentas/RefinanciacionPage.jsx";
import ExencionesPage from "./pages/rentas/ExencionesPage.jsx";
import TicketsPage from "./pages/rentas/TicketsPage.jsx";
import EventosPage from "./pages/rentas/EventosPage.jsx";
import CajaDashboardPage from "./pages/caja/CajaDashboardPage.jsx";
import CobrosPage from "./pages/caja/CobrosPage.jsx";
import ContribuyentesCajaPage from "./pages/caja/ContribuyentesCajaPage.jsx";
import ContribuyenteDetallePage from "./pages/caja/ContribuyenteDetallePage.jsx";
import PagosCajaPage from "./pages/caja/PagosCajaPage.jsx";
import BoletasCajaPage from "./pages/caja/BoletasCajaPage.jsx";
import AuditorDashboardPage from "./pages/auditoria/AuditorDashboardPage.jsx";
import ContribuyentesAuditorPage from "./pages/auditoria/ContribuyentesAuditorPage.jsx";
import ContribuyenteAuditorDetallePage from "./pages/auditoria/ContribuyenteAuditorDetallePage.jsx";
import ConceptosPage from "./pages/auditoria/ConceptosPage.jsx";
import ConceptoDetallePage from "./pages/auditoria/ConceptoDetallePage.jsx";
import LiquidacionesAuditorPage from "./pages/auditoria/LiquidacionesAuditorPage.jsx";
import LiquidacionDetallePage from "./pages/auditoria/LiquidacionDetallePage.jsx";
import DeudasAuditorPage from "./pages/auditoria/DeudasAuditorPage.jsx";
import DeudaDetallePage from "./pages/auditoria/DeudaDetallePage.jsx";
import PagosAuditorPage from "./pages/auditoria/PagosAuditorPage.jsx";
import PagoDetallePage from "./pages/auditoria/PagoDetallePage.jsx";
import ReversionDetallePage from "./pages/auditoria/ReversionDetallePage.jsx";
import PlanesAuditorPage from "./pages/auditoria/PlanesAuditorPage.jsx";
import PlanDetallePage from "./pages/auditoria/PlanDetallePage.jsx";
import ExencionesAuditorPage from "./pages/auditoria/ExencionesAuditorPage.jsx";
import ExencionDetallePage from "./pages/auditoria/ExencionDetallePage.jsx";
import TicketsAuditorPage from "./pages/auditoria/TicketsAuditorPage.jsx";
import TicketDetallePage from "./pages/auditoria/TicketDetallePage.jsx";
import IntegracionesPage from "./pages/auditoria/IntegracionesPage.jsx";
import IntegracionDetallePage from "./pages/auditoria/IntegracionDetallePage.jsx";
import AuditoriaPage from "./pages/auditoria/AuditoriaPage.jsx";
import AuditoriaDetallePage from "./pages/auditoria/AuditoriaDetallePage.jsx";
import IndicadoresPage from "./pages/auditoria/IndicadoresPage.jsx";
import IndicadorDetallePage from "./pages/auditoria/IndicadorDetallePage.jsx";
import InicioPage from "./pages/portal/InicioPage.jsx";
import MisDeudasPage from "./pages/portal/MisDeudasPage.jsx";
import MisBoletasPage from "./pages/portal/MisBoletasPage.jsx";
import MisPagosPage from "./pages/portal/MisPagosPage.jsx";
import PlanesPortalPage from "./pages/portal/PlanesPortalPage.jsx";
import ExencionesPortalPage from "./pages/portal/ExencionesPortalPage.jsx";

/**
 * Rutas de las dos áreas de trabajo del módulo.
 *
 * `/rentas/*` es el back-office de Personal y Supervisor —la bitácora de eventos exige
 * rol supervisor—; `/caja/*` es la ventanilla del Cajero y `/auditor/*` el área de
 * Auditoría, de sólo lectura. `/portal/*` es el único espacio de cara al ciudadano:
 * el Contribuyente consulta su propio legajo. Cada rol entra a la suya y
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
              <Route path="tributos" element={<ConfiguracionTributosPage />} />
              <Route path="liquidaciones" element={<LiquidacionesPage />} />
              <Route path="deudas" element={<DeudasPage />} />
              <Route path="boletas" element={<BoletasPage />} />
              <Route path="pagos" element={<PagosPage />} />
              <Route path="planes" element={<PlanesPage />} />
              <Route path="refinanciacion" element={<RefinanciacionPage />} />
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

          <Route element={<ProtectedRoute roles={["AUDITOR"]} />}>
            <Route path="/auditor" element={<AuditoriaLayout />}>
              <Route index element={<AuditorDashboardPage />} />
              <Route path="contribuyentes" element={<ContribuyentesAuditorPage />} />
              <Route path="contribuyentes/:taxpayerId" element={<ContribuyenteAuditorDetallePage />} />
              <Route path="conceptos" element={<ConceptosPage />} />
              <Route path="conceptos/:code" element={<ConceptoDetallePage />} />
              <Route path="liquidaciones" element={<LiquidacionesAuditorPage />} />
              <Route path="liquidaciones/:settlementId" element={<LiquidacionDetallePage />} />
              <Route path="deudas" element={<DeudasAuditorPage />} />
              <Route path="deudas/:debtId" element={<DeudaDetallePage />} />
              <Route path="pagos" element={<PagosAuditorPage />} />
              <Route path="pagos/:paymentId" element={<PagoDetallePage />} />
              <Route path="reversiones/:reversalId" element={<ReversionDetallePage />} />
              <Route path="planes" element={<PlanesAuditorPage />} />
              <Route path="planes/:requestId" element={<PlanDetallePage />} />
              <Route path="exenciones" element={<ExencionesAuditorPage />} />
              <Route path="exenciones/:requestId" element={<ExencionDetallePage />} />
              <Route path="tickets" element={<TicketsAuditorPage />} />
              <Route path="tickets/:ticketId" element={<TicketDetallePage />} />
              <Route path="integraciones" element={<IntegracionesPage />} />
              <Route path="integraciones/:eventId" element={<IntegracionDetallePage />} />
              <Route path="auditoria" element={<AuditoriaPage />} />
              <Route path="auditoria/:entryId" element={<AuditoriaDetallePage />} />
              <Route path="indicadores" element={<IndicadoresPage />} />
              <Route path="indicadores/:key" element={<IndicadorDetallePage />} />

              <Route path="*" element={<NotFoundPage />} />
            </Route>
          </Route>

          <Route element={<ProtectedRoute roles={["CONTRIBUYENTE"]} />}>
            <Route path="/portal" element={<PortalLayout />}>
              <Route index element={<InicioPage />} />
              <Route path="deudas" element={<MisDeudasPage />} />
              <Route path="boletas" element={<MisBoletasPage />} />
              <Route path="pagos" element={<MisPagosPage />} />
              <Route path="planes" element={<PlanesPortalPage />} />
              <Route path="exenciones" element={<ExencionesPortalPage />} />

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
