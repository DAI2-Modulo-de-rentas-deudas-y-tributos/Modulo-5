import { useCallback, useState } from "react";
import ModuleShell from "../../components/layout/ModuleShell.jsx";
import Card from "../../components/common/Card.jsx";
import DataTable from "../../components/common/DataTable.jsx";
import StatusBadge from "../../components/common/StatusBadge.jsx";
import Modal from "../../components/common/Modal.jsx";
import Button from "../../components/common/Button.jsx";
import FieldGrid from "../../components/auditoria/FieldGrid.jsx";
import Alert from "../../components/ui/Alert.jsx";
import useResource from "../../hooks/useResource.js";
import { portalService } from "../../services/rentasService.js";
import { useAuth } from "../../context/AuthContext.jsx";
import { formatCurrency, formatDateTime, labelFor } from "../../lib/format.js";

/** Pagos acreditados al contribuyente y a qué deuda se aplicó cada uno. */
export default function MisPagosPage() {
  const { user } = useAuth();
  const [selected, setSelected] = useState(null);

  const loader = useCallback(
    () => portalService.payments({ taxpayerId: user.taxpayerId }),
    [user.taxpayerId],
  );
  const { data: payments, loading, error } = useResource(loader, []);
  const creditsLoader = useCallback(
    () => portalService.creditBalances({ taxpayerId: user.taxpayerId }),
    [user.taxpayerId],
  );
  const { data: credits, loading: creditsLoading, error: creditsError } = useResource(creditsLoader, []);

  const sinImputar = (payments ?? []).filter((p) => p.status === "UNALLOCATED");
  const reversados = (payments ?? []).filter((p) => p.status === "REVERSED");

  const columns = [
    {
      key: "receiptNumber",
      header: "Comprobante",
      render: (row) => <span className="font-medium text-neutral-800">{row.receiptNumber}</span>,
    },
    {
      key: "conceptName",
      header: "Aplicado a",
      render: (row) =>
        row.debtId ? (
          <div className="flex flex-col">
            <span>{row.conceptName}</span>
            <span className="text-[12px] tabular-nums text-neutral-400">Deuda #{row.debtId}</span>
          </div>
        ) : Number(row.allocatedAmount) > 0 ? (
          <StatusBadge
            tone={Number(row.unallocatedAmount) > 0 ? "warning" : "success"}
            label={Number(row.unallocatedAmount) > 0 ? "Imputado parcialmente" : "Imputado"}
          />
        ) : (
          <StatusBadge tone="warning" label="Sin imputar" />
        ),
    },
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
    { key: "paidAt", header: "Fecha", render: (row) => formatDateTime(row.paidAt) },
    { key: "status", header: "Estado", render: (row) => <StatusBadge status={row.status} /> },
  ];

  return (
    <ModuleShell
      label="Mi cuenta"
      title="Mis pagos"
      highlight="registrados"
      description="Los pagos acreditados a tu nombre y la deuda a la que se aplicó cada uno."
      breadcrumb={[{ id: "pagos", label: "Mis pagos" }]}
      homePath="/portal"
      homeLabel="Inicio"
    >
      {error && (
        <Alert variant="error" title="No pudimos cargar tus pagos">
          {error}
        </Alert>
      )}

      {sinImputar.length > 0 && (
        <Alert variant="info" title="Tenés un pago sin imputar">
          Se acreditó un pago que todavía no se aplicó a ninguna deuda. Acercate a la
          oficina de Rentas con el comprobante para que lo imputen.
        </Alert>
      )}

      {reversados.length > 0 && (
        <Alert variant="error" title="Tenés un pago reversado">
          Un pago fue dado de baja y su importe volvió al saldo de la deuda. Si no
          entendés el motivo, consultá en la oficina de Rentas.
        </Alert>
      )}

      <Card title="Historial de pagos" description="De los más recientes a los más antiguos.">
        <DataTable
          columns={columns}
          rows={payments ?? []}
          rowKey={(row) => row.id}
          loading={loading}
          emptyIconName="Banknote"
          emptyTitle="Sin pagos registrados"
          emptyDescription="Todavía no hay pagos acreditados a tu nombre."
          onRowClick={setSelected}
        />
      </Card>

      {creditsError && <Alert variant="error" title="No pudimos cargar tu saldo a favor">{creditsError}</Alert>}
      <Card title="Saldo a favor" description="Importe original, total utilizado y saldo disponible. Sólo consulta.">
        <DataTable
          columns={[
            { key: "id", header: "Saldo", render: (row) => `#${row.id}` },
            { key: "sourcePaymentId", header: "Pago de origen", render: (row) => `#${row.sourcePaymentId}` },
            { key: "originalAmount", header: "Original", align: "right", render: (row) => formatCurrency(row.originalAmount) },
            { key: "usedAmount", header: "Utilizado", align: "right", render: (row) => formatCurrency(Number(row.originalAmount) - Number(row.availableAmount)) },
            { key: "availableAmount", header: "Disponible", align: "right", render: (row) => <span className="font-semibold text-emerald-700">{formatCurrency(row.availableAmount)}</span> },
            { key: "status", header: "Estado", render: (row) => <StatusBadge status={row.status} /> },
          ]}
          rows={credits ?? []}
          rowKey={(row) => row.id}
          loading={creditsLoading}
          emptyIconName="Wallet"
          emptyTitle="Sin saldo a favor"
          emptyDescription="No tenés crédito disponible en tu cuenta."
        />
      </Card>

      {selected && <PaymentDetailModal paymentId={selected.id} onClose={() => setSelected(null)} />}
    </ModuleShell>
  );
}

function PaymentDetailModal({ paymentId, onClose }) {
  const loader = useCallback(() => portalService.paymentDetail({ paymentId }), [paymentId]);
  const { data: payment, loading, error } = useResource(loader);
  return (
    <Modal
      open
      title={`Pago #${paymentId}`}
      description="Detalle de la operación registrada en Rentas."
      onClose={onClose}
      footer={<Button variant="secondary" onClick={onClose}>Cerrar</Button>}
    >
      {error && <Alert variant="error" title="No pudimos abrir el pago">{error}</Alert>}
      {loading && <p className="text-[13px] text-neutral-400">Cargando detalle…</p>}
      {payment && (
        <>
          <FieldGrid columns={3} items={[
            { label: "Comprobante", value: payment.receiptNumber },
            { label: "Fecha", value: formatDateTime(payment.paidAt) },
            { label: "Estado", value: <StatusBadge status={payment.status} /> },
            { label: "Importe", value: formatCurrency(payment.amountPaid) },
            { label: "Medio", value: labelFor(payment.method) },
            { label: "Canal", value: labelFor(payment.channel) },
            { label: "Importe aplicado", value: formatCurrency(payment.allocatedAmount) },
            { label: "Sin imputar", value: formatCurrency(payment.unallocatedAmount) },
            { label: "Registrado por", value: payment.registeredBy ?? "Canal digital" },
          ]} />
          <div className="mt-5">
            <h3 className="mb-2 text-[14px] font-semibold text-[#0F2C59]">Imputaciones</h3>
            <DataTable
              columns={[
                {
                  key: "targetType",
                  header: "Destino",
                  render: (row) => row.targetType === "INSTALLMENT"
                    ? `Cuota #${row.installmentId}`
                    : `Deuda #${row.debtId}`,
                },
                { key: "amount", header: "Importe imputado", align: "right", render: (row) => formatCurrency(row.amount) },
                { key: "status", header: "Estado", render: (row) => <StatusBadge status={row.status} /> },
              ]}
              rows={payment.allocations ?? []}
              rowKey={(row) => row.id}
              emptyIconName="ListChecks"
              emptyTitle="Sin imputaciones"
              emptyDescription="Este pago todavía no fue aplicado a una deuda o cuota."
            />
          </div>
        </>
      )}
    </Modal>
  );
}
