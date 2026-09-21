import { useCallback } from "react";
import { useNavigate } from "react-router-dom";
import FeatureCard from "../components/ui/FeatureCard.jsx";
import Alert from "../components/ui/Alert.jsx";
import Spinner from "../components/ui/Spinner.jsx";
import PageHeader from "../components/ui/PageHeader.jsx";
import StatTile from "../components/common/StatTile.jsx";
import { modulesForRole } from "../config/modules.js";
import { dashboardService } from "../services/rentasService.js";
import useResource from "../hooks/useResource.js";
import { useAuth } from "../context/AuthContext.jsx";
import { formatCurrency } from "../lib/format.js";

/** Panel de inicio: métricas del día y acceso a cada módulo funcional. */
export default function DashboardPage() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const loader = useCallback(() => dashboardService.metrics(user.role), [user.role]);
  const { data: metrics, loading, error } = useResource(loader);

  const modules = modulesForRole(user.role);
  const firstName = user.fullName.split(" ")[0];
  const supervisionQueues = metrics ? [
    { id: "planes", label: "Planes excepcionales", path: "/rentas/planes", iconName: "CalendarClock", count: metrics.planes },
    { id: "exenciones", label: "Exenciones", path: "/rentas/exenciones", iconName: "ShieldCheck", count: metrics.exenciones },
    { id: "ajustes", label: "Ajustes", path: "/rentas/ajustes", iconName: "SlidersHorizontal", count: metrics.ajustes },
    { id: "refinanciacion", label: "Refinanciaciones", path: "/rentas/refinanciacion", iconName: "RefreshCw", count: metrics.refinanciacion },
    { id: "reversiones", label: "Reversiones", path: "/rentas/reversiones", iconName: "Undo2", count: metrics.reversiones },
    { id: "corridas", label: "Corridas masivas", path: "/rentas/corridas-masivas", iconName: "Layers", count: metrics.corridas },
    { id: "caducidades", label: "Caducidades", path: "/rentas/caducidades", iconName: "CalendarX", count: metrics.caducidades },
  ] : [];

  return (
    <>
      <PageHeader
        label={user.roleLabel}
        title={`Hola, ${firstName}.`}
        highlight="Tu día en Rentas"
        description="Prioridades de la jornada y acceso directo a cada módulo del área."
      />

      <div className="mx-auto flex max-w-6xl flex-col gap-8 px-5 py-8">
        {error && <Alert variant="error" title="No pudimos cargar el panel">{error}</Alert>}

        {loading ? (
          <div className="flex items-center justify-center gap-3 py-10">
            <Spinner />
            <span className="text-[13px] text-neutral-400">Calculando métricas…</span>
          </div>
        ) : (
          metrics && (
            <>
              <section className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
                <StatTile
                  label="Deuda vencida"
                  value={formatCurrency(metrics.totalOverdueAmount)}
                  hint={`${metrics.deudas} deudas en mora`}
                  iconName="TrendingDown"
                  tone="danger"
                />
                <StatTile
                  label="Pagos sin imputar"
                  value={formatCurrency(metrics.unallocatedPayments)}
                  hint="Requieren imputación manual"
                  iconName="Banknote"
                  tone={metrics.unallocatedPayments > 0 ? "danger" : "success"}
                />
                <StatTile
                  label="Solicitudes pendientes"
                  value={metrics.planes + metrics.exenciones}
                  hint={`${metrics.planes} planes · ${metrics.exenciones} exenciones`}
                  iconName="ClipboardList"
                />
                <StatTile
                  label="Tickets abiertos"
                  value={metrics.tickets}
                  hint="Derivados por Atención Ciudadana"
                  iconName="MessageSquare"
                />
              </section>

              {user.role === "SUPERVISOR" && (
                <section className="flex flex-col gap-4">
                  <div>
                    <h2 className="text-[15px] font-semibold text-[#0F2C59]">Pendientes de supervisión</h2>
                    <p className="text-[13px] text-neutral-400">Abrí cada indicador para ir directamente a su bandeja.</p>
                  </div>
                  <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
                    {supervisionQueues.map((queue) => (
                      <FeatureCard
                        key={queue.id}
                        title={queue.label}
                        description="Solicitudes que requieren una decisión."
                        iconName={queue.iconName}
                        itemCount={queue.count}
                        itemCountLabel="pendientes"
                        badge={queue.count > 0 ? { text: "Requiere atención", className: "bg-red-50 text-[#D63031]" } : undefined}
                        onClick={() => navigate(queue.path)}
                      />
                    ))}
                  </div>
                </section>
              )}

              <section className="flex flex-col gap-4">
                <div>
                  <h2 className="text-[15px] font-semibold text-[#0F2C59]">
                    Módulos del área
                  </h2>
                  <p className="text-[13px] text-neutral-400">
                    El orden sigue el flujo operativo: liquidación → deuda → boleta → pago.
                  </p>
                </div>

                <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
                  {modules.map((module) => (
                    <FeatureCard
                      key={module.id}
                      title={module.label}
                      description={module.description}
                      iconName={module.iconName}
                      itemCount={metrics[module.id]}
                      itemCountLabel={module.countLabel}
                      badge={
                        module.roles.length === 1
                          ? { text: "Supervisor", className: "bg-[#0F2C59]/5 text-[#0F2C59]" }
                          : undefined
                      }
                      onClick={() => navigate(module.path)}
                    />
                  ))}
                </div>
              </section>
            </>
          )
        )}
      </div>
    </>
  );
}
