import { useCallback, useState } from "react";
import { useNavigate } from "react-router-dom";
import ModuleShell from "../../components/layout/ModuleShell.jsx";
import Card from "../../components/common/Card.jsx";
import DataTable from "../../components/common/DataTable.jsx";
import FilterBar from "../../components/common/FilterBar.jsx";
import StatusBadge from "../../components/common/StatusBadge.jsx";
import Alert from "../../components/ui/Alert.jsx";
import useResource from "../../hooks/useResource.js";
import { auditService } from "../../services/rentasService.js";
import { MODULE_LABELS } from "../../services/mockDb.js";
import { formatDateTime } from "../../lib/format.js";

const MODULE_OPTIONS = Object.entries(MODULE_LABELS).map(([value, label]) => ({ value, label }));

/** Eventos intercambiados con los demás módulos: qué entró, qué salió y qué falló. */
export default function IntegracionesPage() {
  const navigate = useNavigate();
  const [filters, setFilters] = useState({
    sourceModule: "",
    eventType: "",
    status: "",
    from: "",
    to: "",
  });

  const loader = useCallback(() => auditService.integrations(filters), [filters]);
  const { data: events, loading, error } = useResource(loader, []);

  const onFilterChange = (name, value) =>
    setFilters((previous) => ({ ...previous, [name]: value }));

  const columns = [
    {
      key: "eventType",
      header: "Evento",
      render: (row) => (
        <div className="flex flex-col">
          <span className="font-medium text-neutral-800">{row.eventType}</span>
          <span className="text-[12px] tabular-nums text-neutral-400">
            {row.eventId.slice(0, 8)}…
          </span>
        </div>
      ),
    },
    {
      key: "sourceModule",
      header: "Origen",
      render: (row) => MODULE_LABELS[row.sourceModule] ?? row.sourceModule,
    },
    {
      key: "direction",
      header: "Sentido",
      render: (row) => (
        <StatusBadge tone="neutral" label={row.direction === "IN" ? "Entrante" : "Saliente"} />
      ),
    },
    { key: "occurredAt", header: "Fecha", render: (row) => formatDateTime(row.occurredAt) },
    { key: "attempts", header: "Intentos", align: "right" },
    { key: "status", header: "Estado", render: (row) => <StatusBadge status={row.status} /> },
  ];

  return (
    <ModuleShell
      label="Auditoría"
      title="Integraciones"
      highlight="entre módulos"
      description="Cada evento recibido o publicado, con su payload y lo que generó en Rentas."
      breadcrumb={[{ id: "integraciones", label: "Integraciones" }]}
      homePath="/auditor"
      homeLabel="Dashboard"
    >
      {error && (
        <Alert variant="error" title="No pudimos cargar los eventos">
          {error}
        </Alert>
      )}

      <Alert variant="info" title="Idempotencia y reintentos">
        Cada evento trae un <code>eventId</code> único: si llega dos veces, la segunda se
        ignora. Los que fallan se reintentan tres veces y después van a la DLQ.
      </Alert>

      <Card title="Resultados" description="Hacé clic en un evento para ver su payload completo.">
        <FilterBar
          searchValue={filters.eventType}
          searchPlaceholder="Tipo de evento…"
          onSearchChange={(value) => onFilterChange("eventType", value)}
          filters={[
            { name: "sourceModule", label: "Módulo origen", options: MODULE_OPTIONS },
            {
              name: "status",
              label: "Estado",
              options: [
                { value: "PROCESSED", label: "Procesado" },
                { value: "PUBLISHED", label: "Publicado" },
                { value: "RETRYING", label: "Reintentando" },
                { value: "DLQ", label: "En DLQ" },
              ],
            },
            { name: "from", label: "Desde", type: "date" },
            { name: "to", label: "Hasta", type: "date" },
          ]}
          values={filters}
          onFilterChange={onFilterChange}
        />
        <DataTable
          columns={columns}
          rows={events ?? []}
          rowKey={(row) => row.eventId}
          loading={loading}
          emptyIconName="Webhook"
          emptyTitle="Sin eventos"
          emptyDescription="Probá con otro módulo o quitá los filtros."
          onRowClick={(row) => navigate(`/auditor/integraciones/${row.eventId}`)}
        />
      </Card>
    </ModuleShell>
  );
}
