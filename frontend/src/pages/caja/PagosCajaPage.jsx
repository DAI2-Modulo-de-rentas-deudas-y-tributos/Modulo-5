import { useCallback, useEffect, useState } from "react";
import ModuleShell from "../../components/layout/ModuleShell.jsx";
import Card from "../../components/common/Card.jsx";
import DataTable from "../../components/common/DataTable.jsx";
import FilterBar from "../../components/common/FilterBar.jsx";
import StatusBadge from "../../components/common/StatusBadge.jsx";
import Alert from "../../components/ui/Alert.jsx";
import ReceiptModal from "../../components/caja/ReceiptModal.jsx";
import useResource from "../../hooks/useResource.js";
import useTaxpayerIndex from "../../hooks/useTaxpayerIndex.js";
import { cashierService, paymentService } from "../../services/rentasService.js";
import { BUSINESS_DATE } from "../../services/mockDb.js";
import { formatCurrency, formatDateTime, labelFor } from "../../lib/format.js";

/**
 * Pagos registrados, con los filtros que usa la ventanilla: fecha, estado y responsable
 * del cobro. Es una consulta: reversar un pago es atribución de Personal de Rentas.
 */
export default function PagosCajaPage() {
  const [filters, setFilters] = useState({ date: BUSINESS_DATE, status: "", registeredBy: "" });
  const [receiptId, setReceiptId] = useState(null);
  const [agents, setAgents] = useState([]);

  const loader = useCallback(() => paymentService.list(filters), [filters]);
  const { data: payments, loading, error } = useResource(loader, []);
  const { nameOf } = useTaxpayerIndex();

  useEffect(() => {
    let active = true;
    cashierService.agents().then((list) => active && setAgents(list));
    return () => {
      active = false;
    };
  }, []);

  const onFilterChange = (name, value) =>
    setFilters((previous) => ({ ...previous, [name]: value }));

  const columns = [
    {
      key: "receiptNumber",
      header: "Comprobante",
      render: (row) => (
        <div className="flex flex-col">
          <span className="font-medium text-neutral-800">{row.receiptNumber}</span>
          <span className="text-[12px] text-neutral-400 tabular-nums">Pago #{row.id}</span>
        </div>
      ),
    },
    { key: "taxpayer", header: "Contribuyente", render: (row) => nameOf(row.taxpayerId) },
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
    { key: "method", header: "Medio", render: (row) => labelFor(row.method) },
    {
      key: "registeredBy",
      header: "Responsable",
      render: (row) =>
        row.registeredBy ? (
          (agents.find((a) => a.value === row.registeredBy)?.label ?? row.registeredBy)
        ) : (
          <span className="text-neutral-300">Canal digital</span>
        ),
    },
    { key: "paidAt", header: "Fecha", render: (row) => formatDateTime(row.paidAt) },
    {
      key: "wasOverdue",
      header: "Situación",
      render: (row) =>
        row.wasOverdue === null || row.wasOverdue === undefined ? (
          <span className="text-neutral-300">—</span>
        ) : (
          <StatusBadge
            tone={row.wasOverdue ? "danger" : "success"}
            label={row.wasOverdue ? "Vencida" : "Al día"}
          />
        ),
    },
    { key: "status", header: "Estado", render: (row) => <StatusBadge status={row.status} /> },
  ];

  return (
    <ModuleShell
      label="Ventanilla"
      title="Pagos"
      highlight="registrados"
      description="Consultá los cobros por fecha, estado y responsable, y reimprimí comprobantes."
      breadcrumb={[{ id: "pagos", label: "Pagos" }]}
      homePath="/caja"
      homeLabel="Panel de caja"
    >
      {error && (
        <Alert variant="error" title="No pudimos cargar los pagos">
          {error}
        </Alert>
      )}

      <Card
        title="Pagos registrados"
        description="Hacé clic en un pago para ver el detalle y su comprobante."
      >
        <FilterBar
          filters={[
            { name: "date", label: "Fecha", type: "date" },
            {
              name: "status",
              label: "Estado",
              options: [
                { value: "REGISTERED", label: "Registrado" },
                { value: "UNALLOCATED", label: "Sin imputar" },
                { value: "REVERSED", label: "Reversado" },
              ],
            },
            { name: "registeredBy", label: "Responsable", options: agents },
          ]}
          values={filters}
          onFilterChange={onFilterChange}
        />

        <DataTable
          columns={columns}
          rows={payments ?? []}
          rowKey={(row) => row.id}
          loading={loading}
          emptyIconName="Banknote"
          emptyTitle="Sin pagos para esos filtros"
          emptyDescription="Probá con otra fecha o quitá el filtro de responsable."
          onRowClick={(row) => setReceiptId(row.id)}
        />
      </Card>

      <ReceiptModal paymentId={receiptId} onClose={() => setReceiptId(null)} />
    </ModuleShell>
  );
}
