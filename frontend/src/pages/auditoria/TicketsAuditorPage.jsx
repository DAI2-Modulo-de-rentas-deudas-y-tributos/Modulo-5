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
import { formatDate } from "../../lib/format.js";

/** Reclamos derivados por M2 y cómo los resolvió Rentas. */
export default function TicketsAuditorPage() {
  const navigate = useNavigate();
  const [filters, setFilters] = useState({
    taxpayer: "",
    subject: "",
    status: "",
    priority: "",
    from: "",
    to: "",
  });

  const loader = useCallback(() => auditService.tickets(filters), [filters]);
  const { data: tickets, loading, error } = useResource(loader, []);

  const onFilterChange = (name, value) =>
    setFilters((previous) => ({ ...previous, [name]: value }));

  const columns = [
    {
      key: "ticketId",
      header: "Nº",
      render: (row) => <span className="tabular-nums">#{row.ticketId}</span>,
    },
    { key: "taxpayerName", header: "Ciudadano" },
    { key: "subject", header: "Categoría" },
    { key: "priority", header: "Prioridad", render: (row) => <StatusBadge status={row.priority} /> },
    { key: "createdAt", header: "Recepción", render: (row) => formatDate(row.createdAt) },
    {
      key: "status",
      header: "Estado en Rentas",
      render: (row) => <StatusBadge status={row.status} />,
    },
    {
      key: "escalated",
      header: "Escalado",
      render: (row) =>
        row.escalated ? (
          <StatusBadge tone="danger" label="Sí" />
        ) : (
          <span className="text-neutral-300">No</span>
        ),
    },
  ];

  return (
    <ModuleShell
      label="Auditoría"
      title="Tickets"
      highlight="derivados"
      description="Reclamos que llegaron desde Atención Ciudadana y la entidad a la que refieren."
      breadcrumb={[{ id: "tickets", label: "Tickets" }]}
      homePath="/auditor"
      homeLabel="Dashboard"
    >
      {error && (
        <Alert variant="error" title="No pudimos cargar los tickets">
          {error}
        </Alert>
      )}

      <Card title="Resultados" description="Hacé clic en un ticket para ver su trazabilidad.">
        <FilterBar
          searchValue={filters.taxpayer}
          searchPlaceholder="Ciudadano, DNI o CUIT…"
          onSearchChange={(value) => onFilterChange("taxpayer", value)}
          filters={[
            {
              name: "subject",
              label: "Categoría",
              options: [
                { value: "PAGO", label: "Pago" },
                { value: "DEUDA", label: "Deuda" },
                { value: "BOLETA", label: "Boleta" },
              ],
            },
            {
              name: "status",
              label: "Estado",
              options: [
                { value: "OPEN", label: "Abierto" },
                { value: "IN_PROGRESS", label: "En curso" },
                { value: "WAITING_FOR_INFORMATION", label: "Esperando información" },
                { value: "COMPLETED", label: "Resuelto" },
                { value: "REJECTED", label: "Rechazado" },
              ],
            },
            {
              name: "priority",
              label: "Prioridad",
              options: [
                { value: "HIGH", label: "Alta" },
                { value: "MEDIUM", label: "Media" },
                { value: "LOW", label: "Baja" },
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
          rows={tickets ?? []}
          rowKey={(row) => row.ticketId}
          loading={loading}
          emptyIconName="MessageSquare"
          emptyTitle="Sin tickets"
          emptyDescription="Probá con otra prioridad o quitá los filtros."
          onRowClick={(row) => navigate(`/auditor/tickets/${row.ticketId}`)}
        />
      </Card>
    </ModuleShell>
  );
}
