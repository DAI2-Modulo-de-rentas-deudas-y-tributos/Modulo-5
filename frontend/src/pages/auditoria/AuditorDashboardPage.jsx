import { useCallback } from "react";
import { useNavigate } from "react-router-dom";
import PageHeader from "../../components/ui/PageHeader.jsx";
import FeatureCard from "../../components/ui/FeatureCard.jsx";
import Alert from "../../components/ui/Alert.jsx";
import Spinner from "../../components/ui/Spinner.jsx";
import Card from "../../components/common/Card.jsx";
import DataTable from "../../components/common/DataTable.jsx";
import StatTile from "../../components/common/StatTile.jsx";
import StatusBadge from "../../components/common/StatusBadge.jsx";
import { AUDITORIA_MODULES } from "../../config/auditoriaModules.js";
import { auditService } from "../../services/rentasService.js";
import useResource from "../../hooks/useResource.js";
import { useAuth } from "../../context/AuthContext.jsx";
import {
  actionLabelFor,
  entityLabelFor,
  formatDate,
  formatDateTime,
  labelFor,
} from "../../lib/format.js";

/** Panel del auditor: volumen del día, desvíos que exigen mirada y actividad reciente. */
export default function AuditorDashboardPage() {
  const { user } = useAuth();
  const navigate = useNavigate();

  const loader = useCallback(() => auditService.dashboard(), []);
  const { data: summary, loading, error } = useResource(loader);

  const firstName = user.fullName.split(" ")[0];

  const activityColumns = [
    { key: "at", header: "Fecha/Hora", render: (row) => formatDateTime(row.at) },
    {
      key: "username",
      header: "Usuario",
      render: (row) => (
        <div className="flex flex-col">
          <span className="font-medium text-neutral-800">{row.username}</span>
          <span className="text-[12px] text-neutral-400">{labelFor(row.role)}</span>
        </div>
      ),
    },
    { key: "action", header: "Acción", render: (row) => actionLabelFor(row.action) },
    {
      key: "entity",
      header: "Entidad",
      render: (row) => (
        <span className="tabular-nums text-neutral-600">
          {entityLabelFor(row.entity.type)} #{row.entity.id}
        </span>
      ),
    },
    { key: "result", header: "Resultado", render: (row) => <StatusBadge status={row.result} /> },
  ];

  return (
    <>
      <PageHeader
        label={user.roleLabel}
        title={`Hola, ${firstName}.`}
        highlight="Control del módulo"
        description="Volumen de la jornada, desvíos a revisar y trazabilidad de cada operación."
      />

      <div className="mx-auto flex max-w-6xl flex-col gap-8 px-5 py-8">
        {error && (
          <Alert variant="error" title="No pudimos cargar el panel">
            {error}
          </Alert>
        )}

        {loading ? (
          <div className="flex items-center justify-center gap-3 py-10">
            <Spinner />
            <span className="text-[13px] text-neutral-400">Calculando indicadores…</span>
          </div>
        ) : (
          summary && (
            <>
              <Alert variant="info" title="Acceso de sólo lectura">
                Auditoría consulta todo el circuito de Rentas pero no modifica ninguna entidad.
                Las correcciones las hace el área responsable y quedan registradas acá.
              </Alert>

              <section className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
                <StatTile
                  label="Operaciones del día"
                  value={summary.dailyOperations}
                  hint={`Jornada del ${formatDate(summary.date)}`}
                  iconName="Activity"
                />
                <StatTile
                  label="Pagos registrados"
                  value={summary.paymentsRegistered}
                  hint="Cobros imputados hoy"
                  iconName="Banknote"
                />
                <StatTile
                  label="Pagos revertidos"
                  value={summary.paymentsReversed}
                  hint="Requieren respaldo documental"
                  iconName="Undo2"
                  tone={summary.paymentsReversed > 0 ? "danger" : "success"}
                />
                <StatTile
                  label="Ajustes manuales"
                  value={summary.manualAdjustments}
                  hint="Reversiones y resoluciones"
                  iconName="ListChecks"
                />
              </section>

              <section className="grid grid-cols-1 gap-4 sm:grid-cols-3">
                <StatTile
                  label="Planes incumplidos"
                  value={summary.defaultedPlans}
                  hint="Cuotas impagas"
                  iconName="CalendarClock"
                  tone={summary.defaultedPlans > 0 ? "danger" : "success"}
                />
                <StatTile
                  label="Exenciones resueltas"
                  value={summary.exemptionsApproved + summary.exemptionsRejected}
                  hint={`${summary.exemptionsApproved} aprobadas · ${summary.exemptionsRejected} rechazadas`}
                  iconName="ShieldCheck"
                />
                <StatTile
                  label="Eventos con error"
                  value={summary.integrationErrors}
                  hint="Reintentos y DLQ"
                  iconName="TriangleAlert"
                  tone={summary.integrationErrors > 0 ? "danger" : "success"}
                />
              </section>

              <Card
                title="Actividad reciente"
                description="Últimas acciones registradas en el módulo, con su responsable."
                actions={
                  <button
                    type="button"
                    onClick={() => navigate("/auditor/auditoria")}
                    className="text-[12px] font-semibold text-[#0F2C59] transition-colors hover:text-[#D63031]"
                  >
                    Ver registro completo
                  </button>
                }
              >
                <DataTable
                  columns={activityColumns}
                  rows={summary.recentActivity}
                  rowKey={(row) => row.id}
                  emptyIconName="ScrollText"
                  emptyTitle="Sin actividad"
                  emptyDescription="Todavía no hay operaciones registradas."
                  onRowClick={(row) => navigate(`/auditor/auditoria/${row.id}`)}
                />
              </Card>

              <section className="flex flex-col gap-4">
                <div>
                  <h2 className="text-[15px] font-semibold text-[#0F2C59]">Módulos de consulta</h2>
                  <p className="text-[13px] text-neutral-400">
                    Del padrón y las reglas a la trazabilidad de cada evento.
                  </p>
                </div>

                <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
                  {AUDITORIA_MODULES.map((module) => (
                    <FeatureCard
                      key={module.id}
                      title={module.label}
                      description={module.description}
                      iconName={module.iconName}
                      showItemCount={false}
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
