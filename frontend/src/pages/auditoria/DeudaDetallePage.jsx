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
import { formatCurrency, formatDate, formatDateTime, labelFor } from "../../lib/format.js";

/** Detalle de deuda: saldo, pagos aplicados, plan asociado e historial de estados. */
export default function DeudaDetallePage() {
  const { debtId } = useParams();
  const navigate = useNavigate();

  const loader = useCallback(() => auditService.debtDetail(debtId), [debtId]);
  const { data: debt, loading, error } = useResource(loader);

  if (loading) {
    return (
      <div className="flex items-center justify-center gap-3 py-24">
        <Spinner />
        <span className="text-[13px] text-neutral-400">Cargando la deuda…</span>
      </div>
    );
  }

  const breadcrumb = [
    { id: "deudas", label: "Deudas", path: "/auditor/deudas" },
    { id: "detalle", label: `#${debtId}` },
  ];

  if (error || !debt) {
    return (
      <ModuleShell
        label="Auditoría"
        title="Deuda"
        breadcrumb={breadcrumb}
        homePath="/auditor"
        homeLabel="Dashboard"
      >
        <Alert variant="error" title="No pudimos abrir la deuda">
          {error ?? "La deuda no existe."}
        </Alert>
      </ModuleShell>
    );
  }

  return (
    <ModuleShell
      label="Auditoría"
      title={`Deuda #${debt.id}`}
      description={`${debt.taxpayerName} · ${debt.conceptName}`}
      breadcrumb={breadcrumb}
      homePath="/auditor"
      homeLabel="Dashboard"
    >
      <Card title="Datos generales">
        <div className="px-5 py-4">
          <FieldGrid
            columns={4}
            items={[
              { label: "Contribuyente", value: debt.taxpayerName },
              { label: "Concepto", value: debt.conceptName },
              { label: "Estado", value: <StatusBadge status={debt.status} /> },
              { label: "Informada a M8", value: debt.reportedToM8 ? "Sí" : "No" },
              { label: "Fecha de generación", value: formatDate(debt.generatedAt) },
              { label: "Vencimiento", value: formatDate(debt.dueDate) },
              {
                label: "Origen",
                value: `${debt.originType}${debt.originId ? ` #${debt.originId}` : ""}`,
              },
              {
                label: "Liquidación origen",
                value: debt.settlement ? `#${debt.settlement.id}` : null,
              },
            ]}
          />
        </div>
      </Card>

      <Card title="Composición del saldo">
        <div className="grid grid-cols-1 gap-4 px-5 py-4 sm:grid-cols-3">
          <Amount label="Importe original" value={debt.originalAmount} />
          <Amount label="Pagado" value={debt.paidAmount} tone="success" />
          <Amount
            label="Saldo pendiente"
            value={debt.outstandingAmount}
            tone={debt.outstandingAmount > 0 ? "danger" : "success"}
          />
        </div>
      </Card>

      <Card title="Pagos asociados" description="Cada imputación que descontó saldo de esta deuda.">
        <DataTable
          columns={[
            { key: "receiptNumber", header: "Comprobante" },
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
          ]}
          rows={debt.payments}
          rowKey={(row) => row.id}
          emptyIconName="Banknote"
          emptyTitle="Sin pagos asociados"
          emptyDescription="La deuda todavía no recibió imputaciones."
          onRowClick={(row) => navigate(`/auditor/pagos/${row.id}`)}
        />
      </Card>

      <Card title="Plan de pago asociado">
        <div className="px-5 py-4">
          {debt.plan ? (
            <div className="flex flex-wrap items-center gap-3 rounded-lg border border-neutral-200 bg-neutral-50 p-4">
              <span className="text-[13px] text-neutral-600">
                Solicitud <span className="font-semibold text-neutral-800">#{debt.plan.requestId}</span>
                {debt.plan.planId ? ` · plan #${debt.plan.planId}` : ""} · {debt.plan.installments}{" "}
                cuotas
              </span>
              <StatusBadge status={debt.plan.status} />
              {debt.plan.lifecycle && <StatusBadge status={debt.plan.lifecycle} />}
              <Button
                size="sm"
                variant="secondary"
                className="ml-auto"
                onClick={() => navigate(`/auditor/planes/${debt.plan.requestId}`)}
              >
                Ver plan
              </Button>
            </div>
          ) : (
            <p className="text-[13px] text-neutral-400">Ninguno.</p>
          )}
        </div>
      </Card>

      <Card title="Historial de estados">
        <div className="px-5 py-4">
          <HistoryTimeline entries={debt.history} />
        </div>
      </Card>
    </ModuleShell>
  );
}

function Amount({ label, value, tone = "neutral" }) {
  const toneClass =
    tone === "danger" ? "text-[#D63031]" : tone === "success" ? "text-emerald-600" : "text-[#0F2C59]";
  return (
    <div className="rounded-lg border border-neutral-200 bg-neutral-50 p-4">
      <p className="text-[11px] font-semibold uppercase tracking-[0.12em] text-neutral-400">
        {label}
      </p>
      <p className={`mt-1.5 text-[20px] font-extrabold tabular-nums leading-none ${toneClass}`}>
        {formatCurrency(value)}
      </p>
    </div>
  );
}
