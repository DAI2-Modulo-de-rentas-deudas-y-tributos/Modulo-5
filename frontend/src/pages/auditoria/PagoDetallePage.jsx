import { useCallback } from "react";
import { useNavigate, useParams } from "react-router-dom";
import ModuleShell from "../../components/layout/ModuleShell.jsx";
import Card from "../../components/common/Card.jsx";
import DataTable from "../../components/common/DataTable.jsx";
import StatusBadge from "../../components/common/StatusBadge.jsx";
import Button from "../../components/common/Button.jsx";
import Alert from "../../components/ui/Alert.jsx";
import Spinner from "../../components/ui/Spinner.jsx";
import FieldGrid from "../../components/auditoria/FieldGrid.jsx";
import HistoryTimeline from "../../components/auditoria/HistoryTimeline.jsx";
import useResource from "../../hooks/useResource.js";
import { auditService } from "../../services/rentasService.js";
import { formatCurrency, formatDateTime, labelFor } from "../../lib/format.js";

/** Detalle del pago: a qué se aplicó, qué generó y si terminó reversado. */
export default function PagoDetallePage() {
  const { paymentId } = useParams();
  const navigate = useNavigate();

  const loader = useCallback(() => auditService.paymentDetail(paymentId), [paymentId]);
  const { data: payment, loading, error } = useResource(loader);

  if (loading) {
    return (
      <div className="flex items-center justify-center gap-3 py-24">
        <Spinner />
        <span className="text-[13px] text-neutral-400">Cargando el pago…</span>
      </div>
    );
  }

  const breadcrumb = [
    { id: "pagos", label: "Pagos", path: "/auditor/pagos" },
    { id: "detalle", label: `#${paymentId}` },
  ];

  if (error || !payment) {
    return (
      <ModuleShell
        label="Auditoría"
        title="Pago"
        breadcrumb={breadcrumb}
        homePath="/auditor"
        homeLabel="Dashboard"
      >
        <Alert variant="error" title="No pudimos abrir el pago">
          {error ?? "El pago no existe."}
        </Alert>
      </ModuleShell>
    );
  }

  return (
    <ModuleShell
      label="Auditoría"
      title={`Pago #${payment.id}`}
      description={`${payment.taxpayerName} · ${payment.receiptNumber}`}
      breadcrumb={breadcrumb}
      homePath="/auditor"
      homeLabel="Dashboard"
    >
      {payment.status === "REVERSED" && (
        <Alert variant="error" title="Pago reversado">
          El importe volvió al saldo de la deuda. El detalle de la reversión está más abajo.
        </Alert>
      )}

      <Card title="Datos generales">
        <div className="px-5 py-4">
          <FieldGrid
            columns={4}
            items={[
              { label: "Contribuyente", value: payment.taxpayerName },
              { label: "Fecha", value: formatDateTime(payment.paidAt) },
              { label: "Importe total", value: formatCurrency(payment.amountPaid) },
              { label: "Medio de pago", value: labelFor(payment.method) },
              { label: "Estado", value: <StatusBadge status={payment.status} /> },
              { label: "Canal", value: labelFor(payment.channel) },
              { label: "Registrado por", value: payment.registeredBy ?? "Canal digital" },
              { label: "Comprobante", value: payment.receiptNumber },
              {
                label: "Situación de la deuda al cobrar",
                value:
                  payment.wasOverdue === null || payment.wasOverdue === undefined
                    ? null
                    : payment.wasOverdue
                      ? "Vencida"
                      : "Al día",
                span: 2,
              },
            ]}
          />
        </div>
      </Card>

      <Card title="Imputaciones" description="A qué deudas se aplicó el importe.">
        <DataTable
          columns={[
            {
              key: "debtId",
              header: "Deuda",
              render: (row) => <span className="tabular-nums">#{row.debtId}</span>,
            },
            { key: "conceptName", header: "Concepto" },
            {
              key: "amount",
              header: "Importe aplicado",
              align: "right",
              render: (row) => formatCurrency(row.amount),
            },
          ]}
          rows={payment.allocations}
          rowKey={(row) => row.debtId}
          emptyIconName="FileWarning"
          emptyTitle="Sin imputar"
          emptyDescription="El pago todavía no se aplicó a ninguna deuda."
          onRowClick={(row) => navigate(`/auditor/deudas/${row.debtId}`)}
        />
      </Card>

      <Card title="Consecuencias del pago">
        <div className="flex flex-col gap-3 px-5 py-4">
          <Consequence
            label="Saldo a favor generado"
            empty="No"
            value={
              payment.creditBalance && (
                <span>
                  {formatCurrency(payment.creditBalance.amount)} — saldo a favor #
                  {payment.creditBalance.id}
                </span>
              )
            }
          />
          <Consequence
            label="Reversión asociada"
            empty="No"
            value={
              payment.reversal && (
                <span className="flex flex-wrap items-center gap-3">
                  Reversión #{payment.reversal.id} — {payment.reversal.reason}
                  <Button
                    size="sm"
                    variant="secondary"
                    onClick={() => navigate(`/auditor/reversiones/${payment.reversal.id}`)}
                  >
                    Ver reversión
                  </Button>
                </span>
              )
            }
          />
        </div>
      </Card>

      <Card title="Historial">
        <div className="px-5 py-4">
          <HistoryTimeline entries={payment.history} />
        </div>
      </Card>
    </ModuleShell>
  );
}

function Consequence({ label, value, empty }) {
  return (
    <div className="flex flex-wrap items-baseline gap-2 border-b border-neutral-100 pb-3 last:border-0 last:pb-0">
      <span className="text-[13px] text-neutral-500">{label}:</span>
      <span className="text-[13px] font-medium text-neutral-800">{value ?? empty}</span>
    </div>
  );
}
