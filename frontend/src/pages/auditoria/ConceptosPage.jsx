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
import { formatDate, labelFor } from "../../lib/format.js";

/** Reglas de cálculo vigentes: qué se cobra, cómo se calcula y desde cuándo. */
export default function ConceptosPage() {
  const navigate = useNavigate();
  const [filters, setFilters] = useState({ query: "", type: "", status: "" });

  const loader = useCallback(() => auditService.concepts(filters), [filters]);
  const { data: concepts, loading, error } = useResource(loader, []);

  const onFilterChange = (name, value) =>
    setFilters((previous) => ({ ...previous, [name]: value }));

  const columns = [
    {
      key: "code",
      header: "Código",
      render: (row) => (
        <div className="flex flex-col">
          <span className="font-medium text-neutral-800">{row.code}</span>
          <span className="text-[12px] text-neutral-400">{row.name}</span>
        </div>
      ),
    },
    { key: "type", header: "Tipo", render: (row) => <StatusBadge status={row.type} /> },
    {
      key: "calculationType",
      header: "Cálculo",
      render: (row) => labelFor(row.calculationType),
    },
    {
      key: "validFrom",
      header: "Vigencia",
      render: (row) => `${formatDate(row.validFrom)} — ${formatDate(row.validUntil)}`,
    },
    {
      key: "versions",
      header: "Versiones",
      align: "right",
      render: (row) => row.versions.length,
    },
    { key: "status", header: "Estado", render: (row) => <StatusBadge status={row.status} /> },
  ];

  return (
    <ModuleShell
      label="Auditoría"
      title="Conceptos"
      highlight="y sus reglas"
      description="Definición de cada tributo, su forma de cálculo y el historial de versiones."
      breadcrumb={[{ id: "conceptos", label: "Conceptos" }]}
      homePath="/auditor"
      homeLabel="Dashboard"
    >
      {error && (
        <Alert variant="error" title="No pudimos cargar los conceptos">
          {error}
        </Alert>
      )}

      <Card title="Buscar concepto" description="Por código o nombre, filtrando por tipo y estado.">
        <FilterBar
          searchValue={filters.query}
          searchPlaceholder="Código o nombre…"
          onSearchChange={(value) => onFilterChange("query", value)}
          filters={[
            {
              name: "type",
              label: "Tipo",
              options: [
                { value: "TASA", label: "Tasa" },
                { value: "MULTA", label: "Multa" },
                { value: "CARGO", label: "Cargo" },
              ],
            },
            {
              name: "status",
              label: "Estado",
              options: [
                { value: "ACTIVE", label: "Activo" },
                { value: "INACTIVE", label: "Inactivo" },
              ],
            },
          ]}
          values={filters}
          onFilterChange={onFilterChange}
        />
        <DataTable
          columns={columns}
          rows={concepts ?? []}
          rowKey={(row) => row.code}
          loading={loading}
          emptyIconName="Tags"
          emptyTitle="Sin conceptos"
          emptyDescription="Probá con otro código o quitá los filtros."
          onRowClick={(row) => navigate(`/auditor/conceptos/${row.code}`)}
        />
      </Card>
    </ModuleShell>
  );
}
