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
import { formatCurrency, formatDate } from "../../lib/format.js";

const CONCEPT_OPTIONS = conceptDefinitions.map((c) => ({ value: c.code, label: c.name }));

/** Deudas vivas y saldadas, con su saldo y estado actual. */
export default function DeudasAuditorPage() {
  const navigate = useNavigate();
  const [filters, setFilters] = useState({
    taxpayer: "",
    conceptCode: "",
    status: "",
    from: "",
    to: "",
  });

  const loader = useCallback(() => auditService.debts(filters), [filters]);
  const { data: debts, loading, error } = useResource(loader, []);

  const onFilterChange = (name, value) =>
    setFilters((previous) => ({ ...previous, [name]: value }));

  const columns = [
    { key: "id", header: "Nº", render: (row) => <span className="tabular-nums">#{row.id}</span> },
    { key: "taxpayerName", header: "Contribuyente" },
    { key: "conceptName", header: "Concepto" },
    {
      key: "originalAmount",
      header: "Original",
      align: "right",
      render: (row) => formatCurrency(row.originalAmount),
    },
    {
      key: "outstandingAmount",
      header: "Saldo",
      align: "right",
      render: (row) => (
        <span className={row.status === "OVERDUE" ? "font-semibold text-[#D63031]" : ""}>
          {formatCurrency(row.outstandingAmount)}
        </span>
      ),
    },
    { key: "dueDate", header: "Vencimiento", render: (row) => formatDate(row.dueDate) },
    { key: "status", header: "Estado", render: (row) => <StatusBadge status={row.status} /> },
  ];

  return (
    <ModuleShell
      label="Auditoría"
      title="Deudas"
      highlight="del módulo"
      description="Saldo, vencimiento y estado de cada obligación, con su cadena de pagos."
      breadcrumb={[{ id: "deudas", label: "Deudas" }]}
      homePath="/auditor"
      homeLabel="Dashboard"
    >
      {error && (
        <Alert variant="error" title="No pudimos cargar las deudas">
          {error}
        </Alert>
      )}

      <Card title="Resultados" description="Hacé clic en una deuda para ver sus pagos e historial.">
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
                { value: "PENDING", label: "Pendiente" },
                { value: "OVERDUE", label: "Vencida" },
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
          rows={debts ?? []}
          rowKey={(row) => row.id}
          loading={loading}
          emptyIconName="FileWarning"
          emptyTitle="Sin deudas"
          emptyDescription="Probá con otro vencimiento o quitá los filtros."
          onRowClick={(row) => navigate(`/auditor/deudas/${row.id}`)}
        />
      </Card>
    </ModuleShell>
  );
}
