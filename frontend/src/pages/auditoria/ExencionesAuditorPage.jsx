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
import { conceptDefinitions } from "../../services/mockDb.js";
import { formatDate, formatPercentage } from "../../lib/format.js";

const CONCEPT_OPTIONS = conceptDefinitions.map((c) => ({ value: c.code, label: c.name }));

const TABS = [
  { id: "", label: "Todas" },
  { id: "REQUESTED", label: "Solicitudes" },
  { id: "APPROVED", label: "Aprobadas" },
  { id: "REJECTED", label: "Rechazadas" },
];

/** Exenciones: qué se pidió, qué se otorgó y quién lo resolvió. */
export default function ExencionesAuditorPage() {
  const navigate = useNavigate();
  const [tab, setTab] = useState("");
  const [filters, setFilters] = useState({ taxpayer: "", conceptCode: "", from: "", to: "" });

  const loader = useCallback(() => auditService.exemptions({ tab, ...filters }), [tab, filters]);
  const { data: exemptions, loading, error } = useResource(loader, []);

  const onFilterChange = (name, value) =>
    setFilters((previous) => ({ ...previous, [name]: value }));

  const columns = [
    {
      key: "requestId",
      header: "Nº",
      render: (row) => <span className="tabular-nums">#{row.requestId}</span>,
    },
    { key: "taxpayerName", header: "Contribuyente" },
    { key: "conceptName", header: "Concepto" },
    {
      key: "requestedPercentage",
      header: "Solicitado",
      align: "right",
      render: (row) => formatPercentage(row.requestedPercentage),
    },
    {
      key: "percentage",
      header: "Aprobado",
      align: "right",
      render: (row) =>
        row.percentage === undefined || row.percentage === null ? (
          <span className="text-neutral-300">—</span>
        ) : (
          <span
            className={
              row.percentage < row.requestedPercentage ? "font-semibold text-amber-600" : ""
            }
          >
            {formatPercentage(row.percentage)}
          </span>
        ),
    },
    { key: "requestedAt", header: "Fecha", render: (row) => formatDate(row.requestedAt) },
    { key: "status", header: "Estado", render: (row) => <StatusBadge status={row.status} /> },
  ];

  return (
    <ModuleShell
      label="Auditoría"
      title="Exenciones"
      highlight="y su resolución"
      description="Porcentaje solicitado frente al aprobado, vigencia y responsable de la decisión."
      breadcrumb={[{ id: "exenciones", label: "Exenciones" }]}
      homePath="/auditor"
      homeLabel="Dashboard"
    >
      {error && (
        <Alert variant="error" title="No pudimos cargar las exenciones">
          {error}
        </Alert>
      )}

      <Card
        title="Resultados"
        description="Cuando el porcentaje aprobado difiere del solicitado, la diferencia queda resaltada."
        actions={
          <div className="flex flex-wrap gap-1">
            {TABS.map((item) => (
              <button
                key={item.id || "all"}
                type="button"
                onClick={() => setTab(item.id)}
                className={`rounded-lg px-3 py-1.5 text-[12px] font-semibold transition-colors ${
                  tab === item.id
                    ? "bg-[#0F2C59] text-white"
                    : "text-neutral-500 hover:bg-neutral-100"
                }`}
              >
                {item.label}
              </button>
            ))}
          </div>
        }
      >
        <FilterBar
          searchValue={filters.taxpayer}
          searchPlaceholder="Contribuyente, DNI o CUIT…"
          onSearchChange={(value) => onFilterChange("taxpayer", value)}
          filters={[
            { name: "conceptCode", label: "Concepto", options: CONCEPT_OPTIONS },
            { name: "from", label: "Desde", type: "date" },
            { name: "to", label: "Hasta", type: "date" },
          ]}
          values={filters}
          onFilterChange={onFilterChange}
        />
        <DataTable
          columns={columns}
          rows={exemptions ?? []}
          rowKey={(row) => row.requestId}
          loading={loading}
          emptyIconName="ShieldCheck"
          emptyTitle="Sin exenciones"
          emptyDescription="Probá con otro concepto o quitá los filtros."
          onRowClick={(row) => navigate(`/auditor/exenciones/${row.requestId}`)}
        />
      </Card>
    </ModuleShell>
  );
}
