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
import { PAYMENT_METHODS } from "../../services/mockDb.js";
import { formatCurrency, formatDate, formatDateTime, labelFor } from "../../lib/format.js";

const TABS = [
  { id: "REGISTERED", label: "Registrados" },
  { id: "UNALLOCATED", label: "No imputados" },
  { id: "CREDIT", label: "Saldos a favor" },
  { id: "REVERSED", label: "Reversiones" },
];

/**
 * Pagos con las cuatro miradas del sketch. "Reversiones" no lista pagos sino las
 * reversiones en sí: al auditor le importa quién la pidió y quién la aprobó.
 */
export default function PagosAuditorPage() {
  const navigate = useNavigate();
  const [tab, setTab] = useState("REGISTERED");
  const [filters, setFilters] = useState({ taxpayer: "", method: "", from: "", to: "" });

  const isReversals = tab === "REVERSED";

  const loader = useCallback(
    () =>
      isReversals
        ? auditService.reversals({ from: filters.from, to: filters.to })
        : auditService.payments({ tab, ...filters }),
    [isReversals, tab, filters],
  );
  const { data: rows, loading, error } = useResource(loader, []);

  const onFilterChange = (name, value) =>
    setFilters((previous) => ({ ...previous, [name]: value }));

  const paymentColumns = [
    {
      key: "receiptNumber",
      header: "Nº",
      render: (row) => (
        <div className="flex flex-col">
          <span className="font-medium text-neutral-800">{row.receiptNumber}</span>
          <span className="text-[12px] tabular-nums text-neutral-400">Pago #{row.id}</span>
        </div>
      ),
    },
    { key: "taxpayerName", header: "Contribuyente" },
    {
      key: "amountPaid",
      header: "Importe",
      align: "right",
      render: (row) => (
        <span className={row.status === "REVERSED" ? "text-neutral-400 line-through" : ""}>
          {formatCurrency(row.amountPaid)}
        </span>
      ),
    },
    { key: "method", header: "Medio de pago", render: (row) => labelFor(row.method) },
    { key: "paidAt", header: "Fecha", render: (row) => formatDateTime(row.paidAt) },
    { key: "status", header: "Estado", render: (row) => <StatusBadge status={row.status} /> },
  ];

  const reversalColumns = [
    { key: "id", header: "Nº", render: (row) => <span className="tabular-nums">#{row.id}</span> },
    {
      key: "paymentId",
      header: "Pago",
      render: (row) => <span className="tabular-nums text-neutral-600">#{row.paymentId}</span>,
    },
    { key: "taxpayerName", header: "Contribuyente" },
    {
      key: "reversedAmount",
      header: "Importe",
      align: "right",
      render: (row) => formatCurrency(row.reversedAmount),
    },
    { key: "requestedBy", header: "Solicitó" },
    { key: "approvedBy", header: "Aprobó" },
    { key: "reversedAt", header: "Fecha", render: (row) => formatDate(row.reversedAt) },
  ];

  return (
    <ModuleShell
      label="Auditoría"
      title="Pagos"
      highlight="del módulo"
      description="Registrados, sin imputar, saldos a favor y reversiones, con su imputación al detalle."
      breadcrumb={[{ id: "pagos", label: "Pagos" }]}
      homePath="/auditor"
      homeLabel="Dashboard"
    >
      {error && (
        <Alert variant="error" title="No pudimos cargar los pagos">
          {error}
        </Alert>
      )}

      <Card
        title="Resultados"
        description={
          isReversals
            ? "Cada reversión con su solicitante y quien la autorizó."
            : "Hacé clic en un pago para ver su imputación e historial."
        }
        actions={
          <div className="flex flex-wrap gap-1">
            {TABS.map((item) => (
              <button
                key={item.id}
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
          onSearchChange={isReversals ? undefined : (value) => onFilterChange("taxpayer", value)}
          filters={[
            ...(isReversals
              ? []
              : [{ name: "method", label: "Medio de pago", options: PAYMENT_METHODS }]),
            { name: "from", label: "Desde", type: "date" },
            { name: "to", label: "Hasta", type: "date" },
          ]}
          values={filters}
          onFilterChange={onFilterChange}
        />
        <DataTable
          columns={isReversals ? reversalColumns : paymentColumns}
          rows={rows ?? []}
          rowKey={(row) => row.id}
          loading={loading}
          emptyIconName={isReversals ? "Undo2" : "Banknote"}
          emptyTitle={isReversals ? "Sin reversiones" : "Sin pagos"}
          emptyDescription="Probá con otra fecha o quitá los filtros."
          onRowClick={(row) =>
            navigate(isReversals ? `/auditor/reversiones/${row.id}` : `/auditor/pagos/${row.id}`)
          }
        />
      </Card>
    </ModuleShell>
  );
}
