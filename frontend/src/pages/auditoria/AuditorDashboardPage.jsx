import { useCallback } from "react";
import { useNavigate } from "react-router-dom";
import PageHeader from "../../components/layout/WorkspaceHeader.jsx";
import FeatureCard from "../../components/ui/FeatureCard.jsx";
import Alert from "../../components/ui/Alert.jsx";
import DashboardLoading from "../../components/common/DashboardLoading.jsx";
import Card from "../../components/common/Card.jsx";
import DataTable from "../../components/common/DataTable.jsx";
import { AUDITORIA_MODULES } from "../../config/auditoriaModules.js";
import { auditService } from "../../services/rentasService.js";
import useResource from "../../hooks/useResource.js";
import { useAuth } from "../../context/AuthContext.jsx";
import {
  actionLabelFor,
  entityLabelFor,
  formatDateTime,
  labelFor,
} from "../../lib/format.js";

/** Panel del auditor con actividad reciente y acceso de consulta. */
export default function AuditorDashboardPage() {
  const { user } = useAuth();
  const navigate = useNavigate();

  const loader = useCallback(() => auditService.dashboard(), []);
  const { data: summary, loading, error } = useResource(loader);

  const firstName = user.fullName.split(" ")[0];

  const activityColumns = [
    { key: "occurredAt", header: "Fecha/Hora", render: (row) => formatDateTime(row.occurredAt) },
    {
      key: "userId",
      header: "Usuario",
      render: (row) => (
        <div className="flex flex-col">
          <span className="font-medium text-neutral-800">{row.userId}</span>
          <span className="text-[12px] text-neutral-400">{labelFor(row.userRole)}</span>
        </div>
      ),
    },
    { key: "action", header: "Acción", render: (row) => actionLabelFor(row.action) },
    {
      key: "entity",
      header: "Entidad",
      render: (row) => (
        <span className="tabular-nums text-neutral-600">
          {entityLabelFor(row.entityType)} #{row.entityId}
        </span>
      ),
    },
  ];

  return (
    <>
      <PageHeader
        label={user.roleLabel}
        title={`Hola, ${firstName}.`}
        highlight="Control del módulo"
        description="Trazabilidad reciente de las operaciones registradas en Rentas."
      />

      <div className="workspace-container workspace-content workspace-dashboard">
        {error && (
          <Alert variant="error" title="No pudimos cargar el panel">
            {error}
          </Alert>
        )}

        {loading ? (
          <DashboardLoading label="Calculando indicadores…" />
        ) : (
          summary && (
            <>
              <Alert variant="info" title="Acceso de sólo lectura">
                Auditoría consulta todo el circuito de Rentas pero no modifica ninguna entidad.
                Las correcciones las hace el área responsable y quedan registradas acá.
              </Alert>

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
                  rows={summary}
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

                <div className="workspace-directory">
                  {AUDITORIA_MODULES.map((module) => (
                    <FeatureCard
                      key={module.id}
                      className="workspace-module-link"
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
