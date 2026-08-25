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
import { formatCurrency } from "../../lib/format.js";

/** Planes de pago: qué se financió, en cuántas cuotas y si se está cumpliendo. */
export default function PlanesAuditorPage() {
  const navigate = useNavigate();
  const [filters, setFilters] = useState({ taxpayer: "", status: "", lifecycle: "", from: "", to: "" });

  const loader = useCallback(() => auditService.plans(filters), [filters]);
  const { data: plans, loading, error } = useResource(loader, []);

  const onFilterChange = (name, value) =>
    setFilters((previous) => ({ ...previous, [name]: value }));

  const columns = [
    {
      key: "requestId",
      header: "Nº",
      render: (row) => (
        <div className="flex flex-col">
          <span className="font-medium tabular-nums text-neutral-800">#{row.requestId}</span>
          {row.planId && (
            <span className="text-[12px] tabular-nums text-neutral-400">Plan #{row.planId}</span>
          )}
        </div>
      ),
    },
    { key: "taxpayerName", header: "Contribuyente" },
    { key: "installments", header: "Cuotas", align: "right" },
    {
      key: "outstandingAmount",
      header: "Saldo",
      align: "right",
      render: (row) =>
        row.outstandingAmount === undefined
          ? formatCurrency(row.totalDebt)
          : formatCurrency(row.outstandingAmount),
    },
    { key: "status", header: "Resolución", render: (row) => <StatusBadge status={row.status} /> },
    {
      key: "lifecycle",
      header: "Situación",
      render: (row) =>
        row.lifecycle ? (
          <StatusBadge status={row.lifecycle} />
        ) : (
          <span className="text-neutral-300">—</span>
        ),
    },
  ];

  return (
    <ModuleShell
      label="Auditoría"
      title="Planes de Pago"
      highlight="otorgados"
      description="Financiación, cuotas y cumplimiento de cada plan, con las deudas que incluye."
      breadcrumb={[{ id: "planes", label: "Planes de Pago" }]}
      homePath="/auditor"
      homeLabel="Dashboard"
    >
      {error && (
        <Alert variant="error" title="No pudimos cargar los planes">
          {error}
        </Alert>
      )}

      <Alert variant="info" title="Resolución y situación son cosas distintas">
        La <strong>resolución</strong> es lo que viajó en <code>updatePaymentPlanStatus</code>{" "}
        (otorgado o rechazado). La <strong>situación</strong> es el ciclo interno posterior:
        vigente, cumplido o incumplido.
      </Alert>

      <Card title="Resultados" description="Hacé clic en un plan para ver sus cuotas.">
        <FilterBar
          searchValue={filters.taxpayer}
          searchPlaceholder="Contribuyente, DNI o CUIT…"
          onSearchChange={(value) => onFilterChange("taxpayer", value)}
          filters={[
            {
              name: "status",
              label: "Resolución",
              options: [
                { value: "REQUESTED", label: "Pendiente de resolución" },
                { value: "GRANTED", label: "Otorgado" },
                { value: "REJECTED", label: "Rechazado" },
              ],
            },
            {
              name: "lifecycle",
              label: "Situación",
              options: [
                { value: "CURRENT", label: "Vigente" },
                { value: "FULFILLED", label: "Cumplido" },
                { value: "DEFAULTED", label: "Incumplido" },
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
          rows={plans ?? []}
          rowKey={(row) => row.requestId}
          loading={loading}
          emptyIconName="CalendarClock"
          emptyTitle="Sin planes"
          emptyDescription="Probá con otra fecha o quitá los filtros."
          onRowClick={(row) => navigate(`/auditor/planes/${row.requestId}`)}
        />
      </Card>
    </ModuleShell>
  );
}
