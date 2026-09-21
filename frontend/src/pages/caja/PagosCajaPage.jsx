import { useCallback, useEffect, useState } from "react";
import ModuleShell from "../../components/layout/ModuleShell.jsx";
import Card from "../../components/common/Card.jsx";
import DataTable from "../../components/common/DataTable.jsx";
import FilterBar from "../../components/common/FilterBar.jsx";
import StatusBadge from "../../components/common/StatusBadge.jsx";
import Alert from "../../components/ui/Alert.jsx";
import ReceiptModal from "../../components/caja/ReceiptModal.jsx";
import ReversalRequestModal from "../../components/caja/ReversalRequestModal.jsx";
import useResource from "../../hooks/useResource.js";
import useTaxpayerIndex from "../../hooks/useTaxpayerIndex.js";
import { cashierService, paymentService } from "../../services/rentasService.js";
import { formatCurrency, formatDateTime, labelFor } from "../../lib/format.js";

/**
 * Pagos registrados, con los filtros que usa la ventanilla: fecha, estado y responsable
 * del cobro. Es una consulta: reversar un pago es atribución de Personal de Rentas.
 */
export default function PagosCajaPage() {
  const [filters, setFilters] = useState({ date: new Date().toISOString().slice(0, 10), status: "", registeredBy: "" });
  const [query, setQuery] = useState("");
  const [selectedPayment, setSelectedPayment] = useState(null);
  const [reversalTarget, setReversalTarget] = useState(null);
  const [feedback, setFeedback] = useState(null);
  const [agents, setAgents] = useState([]);
  const [agentsError, setAgentsError] = useState(null);

  const loader = useCallback(
    () => query
      ? paymentService.listAll({ ...filters, date: "" })
      : paymentService.list(filters),
    [filters, query],
  );
  const { data: payments, loading, error, reload } = useResource(loader, []);
  const { index, nameOf } = useTaxpayerIndex();

  const normalizedQuery = query.trim().toLocaleLowerCase("es");
  const visiblePayments = normalizedQuery
    ? (payments ?? []).filter((payment) => {
        const taxpayer = index[payment.taxpayerId] ?? {};
        return [
          payment.receiptNumber,
          payment.id,
          taxpayer.name,
          taxpayer.document,
          taxpayer.cuit,
          taxpayer.externalId,
        ].some((value) => String(value ?? "").toLocaleLowerCase("es").includes(normalizedQuery));
      })
    : payments ?? [];

  useEffect(() => {
    let active = true;
    cashierService.agents()
      .then((list) => active && setAgents(list))
      .catch((failure) => active && setAgentsError(failure.message));
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
      {feedback && (
        <Alert variant="success" title="Solicitud enviada" onDismiss={() => setFeedback(null)}>
          {feedback}
        </Alert>
      )}
      {error && (
        <Alert variant="error" title="No pudimos cargar los pagos">
          {error}
        </Alert>
      )}
      {agentsError && <Alert variant="error" title="No pudimos cargar los responsables">{agentsError}</Alert>}

      <Card
        title="Pagos registrados"
        description="Hacé clic en un pago para ver el detalle y su comprobante."
      >
        <FilterBar
          searchValue={query}
          searchPlaceholder="Comprobante, DNI, CUIT u operación…"
          onSearchChange={setQuery}
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
          rows={visiblePayments}
          rowKey={(row) => row.id}
          loading={loading}
          emptyIconName="Banknote"
          emptyTitle="Sin pagos para esos filtros"
          emptyDescription="Probá con otra fecha o quitá el filtro de responsable."
          onRowClick={setSelectedPayment}
        />
      </Card>

      <ReceiptModal
        paymentId={selectedPayment?.id}
        onClose={() => setSelectedPayment(null)}
        onRequestReversal={() => {
          setReversalTarget(selectedPayment);
          setSelectedPayment(null);
        }}
      />
      <ReversalRequestModal
        payment={reversalTarget}
        onClose={() => setReversalTarget(null)}
        onDone={(request) => {
          setReversalTarget(null);
          setFeedback(`La solicitud #${request.id} quedó pendiente de aprobación.`);
          reload();
        }}
      />
    </ModuleShell>
  );
}
