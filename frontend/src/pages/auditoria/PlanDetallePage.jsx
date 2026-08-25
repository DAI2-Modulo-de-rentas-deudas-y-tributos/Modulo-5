import { useCallback } from "react";
import { useNavigate, useParams } from "react-router-dom";
import ModuleShell from "../../components/layout/ModuleShell.jsx";
import Card from "../../components/common/Card.jsx";
import DataTable from "../../components/common/DataTable.jsx";
import StatusBadge from "../../components/common/StatusBadge.jsx";
import Alert from "../../components/ui/Alert.jsx";
import Spinner from "../../components/ui/Spinner.jsx";
import FieldGrid from "../../components/auditoria/FieldGrid.jsx";
import HistoryTimeline from "../../components/auditoria/HistoryTimeline.jsx";
import useResource from "../../hooks/useResource.js";
import { auditService } from "../../services/rentasService.js";
import { formatCurrency, formatDate } from "../../lib/format.js";

/** Detalle del plan: la financiación abierta, el cuadro de cuotas y qué deudas cubre. */
export default function PlanDetallePage() {
  const { requestId } = useParams();
  const navigate = useNavigate();

  const loader = useCallback(() => auditService.planDetail(requestId), [requestId]);
  const { data: plan, loading, error } = useResource(loader);

  if (loading) {
    return (
      <div className="flex items-center justify-center gap-3 py-24">
        <Spinner />
        <span className="text-[13px] text-neutral-400">Cargando el plan…</span>
      </div>
    );
  }

  const breadcrumb = [
    { id: "planes", label: "Planes de Pago", path: "/auditor/planes" },
    { id: "detalle", label: `#${requestId}` },
  ];

  if (error || !plan) {
    return (
      <ModuleShell
        label="Auditoría"
        title="Plan de pago"
        breadcrumb={breadcrumb}
        homePath="/auditor"
        homeLabel="Dashboard"
      >
        <Alert variant="error" title="No pudimos abrir el plan">
          {error ?? "El plan no existe."}
        </Alert>
      </ModuleShell>
    );
  }

  return (
    <ModuleShell
      label="Auditoría"
      title={plan.planId ? `Plan #${plan.planId}` : `Solicitud #${plan.requestId}`}
      description={`${plan.taxpayerName} · solicitud #${plan.requestId}`}
      breadcrumb={breadcrumb}
      homePath="/auditor"
      homeLabel="Dashboard"
    >
      <Card title="Datos generales">
        <div className="px-5 py-4">
          <FieldGrid
            columns={4}
            items={[
              { label: "Contribuyente", value: plan.taxpayerName },
              { label: "Fecha de solicitud", value: formatDate(plan.requestedAt) },
              {
                label: "Fecha de otorgamiento",
                value: plan.resolvedAt ? formatDate(plan.resolvedAt) : null,
              },
              { label: "Resolvió", value: plan.resolvedBy },
              { label: "Resolución", value: <StatusBadge status={plan.status} /> },
              {
                label: "Situación",
                value: plan.lifecycle ? <StatusBadge status={plan.lifecycle} /> : null,
              },
              { label: "Cantidad de cuotas", value: plan.installments },
              {
                label: "Motivo del rechazo",
                value: plan.status === "REJECTED" ? plan.reason : null,
              },
            ]}
          />
        </div>
      </Card>

      {plan.status === "GRANTED" && (
        <Card title="Financiación">
          <div className="px-5 py-4">
            <dl className="flex flex-col gap-2">
              <Line label="Deuda incluida" value={formatCurrency(plan.totalDebt)} />
              <Line label="Anticipo" value={formatCurrency(plan.downPayment)} />
              <Line label="Importe financiado" value={formatCurrency(plan.financedAmount)} />
              <Line label="Intereses" value={formatCurrency(plan.interestAmount)} />
              <div className="mt-2 flex items-center justify-between border-t border-neutral-200 pt-3">
                <dt className="text-[13px] font-semibold text-neutral-700">Total del plan</dt>
                <dd className="text-[18px] font-extrabold tabular-nums text-[#0F2C59]">
                  {formatCurrency(plan.totalAmount)}
                </dd>
              </div>
              <div className="flex items-center justify-between">
                <dt className="text-[13px] text-neutral-500">Saldo pendiente</dt>
                <dd className="text-[13px] font-semibold tabular-nums text-neutral-700">
                  {formatCurrency(plan.outstandingAmount)}
                </dd>
              </div>
            </dl>
          </div>
        </Card>
      )}

      {plan.schedule?.length > 0 && (
        <Card title="Cuotas">
          <DataTable
            columns={[
              { key: "number", header: "Nº", render: (row) => <span className="tabular-nums">{row.number}</span> },
              { key: "dueDate", header: "Vencimiento", render: (row) => formatDate(row.dueDate) },
              {
                key: "amount",
                header: "Importe",
                align: "right",
                render: (row) => formatCurrency(row.amount),
              },
              { key: "status", header: "Estado", render: (row) => <StatusBadge status={row.status} /> },
            ]}
            rows={plan.schedule}
            rowKey={(row) => row.number}
            emptyIconName="CalendarClock"
            emptyTitle="Sin cuotas"
          />
        </Card>
      )}

      <Card title="Deudas incluidas">
        <DataTable
          columns={[
            { key: "id", header: "Deuda", render: (row) => <span className="tabular-nums">#{row.id}</span> },
            { key: "conceptName", header: "Concepto" },
            {
              key: "originalAmount",
              header: "Importe",
              align: "right",
              render: (row) => formatCurrency(row.originalAmount),
            },
            { key: "status", header: "Estado", render: (row) => <StatusBadge status={row.status} /> },
          ]}
          rows={plan.debts}
          rowKey={(row) => row.id}
          emptyIconName="FileWarning"
          emptyTitle="Sin deudas incluidas"
          onRowClick={(row) => navigate(`/auditor/deudas/${row.id}`)}
        />
      </Card>

      <Card title="Historial">
        <div className="px-5 py-4">
          <HistoryTimeline entries={plan.history} />
        </div>
      </Card>
    </ModuleShell>
  );
}

function Line({ label, value }) {
  return (
    <div className="flex items-center justify-between">
      <dt className="text-[13px] text-neutral-500">{label}</dt>
      <dd className="text-[13px] font-medium tabular-nums text-neutral-700">{value}</dd>
    </div>
  );
}
