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
import { conceptDefinitions, MODULE_LABELS } from "../../services/mockDb.js";
import { formatCurrency } from "../../lib/format.js";

const CONCEPT_OPTIONS = conceptDefinitions.map((c) => ({ value: c.code, label: c.name }));

/** Liquidaciones emitidas: importe, estado y el evento externo que las originó. */
export default function LiquidacionesAuditorPage() {
  const navigate = useNavigate();
  const [filters, setFilters] = useState({
    taxpayer: "",
    conceptCode: "",
    status: "",
    from: "",
    to: "",
  });

  const loader = useCallback(() => auditService.settlements(filters), [filters]);
  const { data: settlements, loading, error } = useResource(loader, []);

  const onFilterChange = (name, value) =>
    setFilters((previous) => ({ ...previous, [name]: value }));

  const columns = [
    { key: "id", header: "Nº", render: (row) => <span className="tabular-nums">#{row.id}</span> },
    { key: "taxpayerName", header: "Contribuyente" },
    { key: "conceptName", header: "Concepto" },
    { key: "period", header: "Período" },
    {
      key: "amount",
      header: "Importe",
      align: "right",
      render: (row) => formatCurrency(row.amount),
    },
    {
      key: "origin",
      header: "Origen",
      render: (row) => (
        <span className="text-[12px] text-neutral-500">
          {MODULE_LABELS[row.origin.module] ?? row.origin.module}
        </span>
      ),
    },
    { key: "status", header: "Estado", render: (row) => <StatusBadge status={row.status} /> },
  ];

  return (
    <ModuleShell
      label="Auditoría"
      title="Liquidaciones"
      highlight="emitidas"
      description="Cómo se compuso cada importe y qué hecho externo lo generó."
      breadcrumb={[{ id: "liquidaciones", label: "Liquidaciones" }]}
      homePath="/auditor"
      homeLabel="Dashboard"
    >
      {error && (
        <Alert variant="error" title="No pudimos cargar las liquidaciones">
          {error}
        </Alert>
      )}

      <Card title="Resultados" description="Hacé clic en una liquidación para ver su cálculo y origen.">
        <FilterBar
          searchValue={filters.taxpayer}
          searchPlaceholder="Contribuyente, DNI o CUIT…"
          onSearchChange={(value) => onFilterChange("taxpayer", value)}
          filters={[
            { name: "conceptCode", label: "Concepto", options: CONCEPT_OPTIONS },
            {
              name: "status",
              label: "Estado",
              options: [
                { value: "DRAFT", label: "Borrador" },
                { value: "ISSUED", label: "Emitida" },
                { value: "SETTLED", label: "Cancelada" },
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
          rows={settlements ?? []}
          rowKey={(row) => row.id}
          loading={loading}
          emptyIconName="Calculator"
          emptyTitle="Sin liquidaciones"
          emptyDescription="Probá con otro período o quitá los filtros."
          onRowClick={(row) => navigate(`/auditor/liquidaciones/${row.id}`)}
        />
      </Card>
    </ModuleShell>
  );
}
