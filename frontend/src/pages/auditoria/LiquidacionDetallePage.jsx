import { useCallback } from "react";
import { useNavigate, useParams } from "react-router-dom";
import ModuleShell from "../../components/layout/ModuleShell.jsx";
import Card from "../../components/common/Card.jsx";
import StatusBadge from "../../components/common/StatusBadge.jsx";
import Button from "../../components/common/Button.jsx";
import Alert from "../../components/ui/Alert.jsx";
import Spinner from "../../components/ui/Spinner.jsx";
import FieldGrid from "../../components/auditoria/FieldGrid.jsx";
import HistoryTimeline from "../../components/auditoria/HistoryTimeline.jsx";
import useResource from "../../hooks/useResource.js";
import { auditService } from "../../services/rentasService.js";
import { MODULE_LABELS } from "../../services/mockDb.js";
import { formatCurrency, formatDate, formatPercentage } from "../../lib/format.js";

/** Detalle de liquidación: el cálculo abierto y la traza hasta el evento de origen. */
export default function LiquidacionDetallePage() {
  const { settlementId } = useParams();
  const navigate = useNavigate();

  const loader = useCallback(() => auditService.settlementDetail(settlementId), [settlementId]);
  const { data: settlement, loading, error } = useResource(loader);

  if (loading) {
    return (
      <div className="flex items-center justify-center gap-3 py-24">
        <Spinner />
        <span className="text-[13px] text-neutral-400">Cargando la liquidación…</span>
      </div>
    );
  }

  const breadcrumb = [
    { id: "liquidaciones", label: "Liquidaciones", path: "/auditor/liquidaciones" },
    { id: "detalle", label: `#${settlementId}` },
  ];

  if (error || !settlement) {
    return (
      <ModuleShell
        label="Auditoría"
        title="Liquidación"
        breadcrumb={breadcrumb}
        homePath="/auditor"
        homeLabel="Dashboard"
      >
        <Alert variant="error" title="No pudimos abrir la liquidación">
          {error ?? "La liquidación no existe."}
        </Alert>
      </ModuleShell>
    );
  }

  const discount = (settlement.baseAmount * settlement.discountPercentage) / 100;

  return (
    <ModuleShell
      label="Auditoría"
      title={`Liquidación #${settlement.id}`}
      description={`${settlement.taxpayerName} · ${settlement.conceptName}`}
      breadcrumb={breadcrumb}
      homePath="/auditor"
      homeLabel="Dashboard"
    >
      <Card title="Datos generales">
        <div className="px-5 py-4">
          <FieldGrid
            columns={4}
            items={[
              { label: "Contribuyente", value: settlement.taxpayerName },
              { label: "Concepto", value: settlement.conceptName },
              { label: "Período", value: settlement.period },
              { label: "Estado", value: <StatusBadge status={settlement.status} /> },
              { label: "Fecha de emisión", value: formatDate(settlement.createdAt) },
              { label: "Vencimiento", value: formatDate(settlement.dueDate) },
            ]}
          />
        </div>
      </Card>

      <Card title="Cálculo" description="Cómo se llegó al importe final.">
        <div className="px-5 py-4">
          <dl className="flex flex-col gap-2">
            <CalcLine label="Importe base" value={formatCurrency(settlement.baseAmount)} />
            <CalcLine
              label={`Descuentos${settlement.discountPercentage ? ` (${formatPercentage(settlement.discountPercentage)})` : ""}`}
              value={discount ? `− ${formatCurrency(discount)}` : formatCurrency(0)}
              tone={discount ? "positive" : "muted"}
            />
            <CalcLine
              label="Recargos"
              value={formatCurrency(settlement.surcharges)}
              tone={settlement.surcharges ? "negative" : "muted"}
            />
            <CalcLine
              label="Intereses"
              value={formatCurrency(settlement.interests)}
              tone={settlement.interests ? "negative" : "muted"}
            />
            <div className="mt-2 flex items-center justify-between border-t border-neutral-200 pt-3">
              <dt className="text-[13px] font-semibold text-neutral-700">Importe final</dt>
              <dd className="text-[18px] font-extrabold tabular-nums text-[#0F2C59]">
                {formatCurrency(settlement.amount)}
              </dd>
            </div>
          </dl>
        </div>
      </Card>

      <Card title="Origen" description="De dónde salió la obligación.">
        <div className="px-5 py-4">
          <FieldGrid
            columns={3}
            items={[
              {
                label: "Módulo",
                value: MODULE_LABELS[settlement.origin.module] ?? settlement.origin.module,
              },
              { label: "Evento", value: settlement.origin.event },
              { label: "Referencia externa", value: settlement.origin.externalRef },
            ]}
          />
          {settlement.debt && (
            <div className="mt-4 flex flex-wrap items-center gap-3 rounded-lg border border-neutral-200 bg-neutral-50 p-4">
              <span className="text-[13px] text-neutral-600">
                Deuda generada:{" "}
                <span className="font-semibold text-neutral-800">#{settlement.debt.id}</span> ·
                saldo {formatCurrency(settlement.debt.outstandingAmount)}
              </span>
              <StatusBadge status={settlement.debt.status} />
              <Button
                size="sm"
                variant="secondary"
                className="ml-auto"
                onClick={() => navigate(`/auditor/deudas/${settlement.debt.id}`)}
              >
                Ver deuda
              </Button>
            </div>
          )}
        </div>
      </Card>

      <Card title="Historial">
        <div className="px-5 py-4">
          <HistoryTimeline entries={settlement.history} />
        </div>
      </Card>
    </ModuleShell>
  );
}

function CalcLine({ label, value, tone = "muted" }) {
  const toneClass =
    tone === "positive" ? "text-emerald-600" : tone === "negative" ? "text-[#D63031]" : "text-neutral-600";
  return (
    <div className="flex items-center justify-between">
      <dt className="text-[13px] text-neutral-500">{label}</dt>
      <dd className={`text-[13px] font-medium tabular-nums ${toneClass}`}>{value}</dd>
    </div>
  );
}
