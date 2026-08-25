import { useCallback } from "react";
import { useNavigate, useParams } from "react-router-dom";
import ModuleShell from "../../components/layout/ModuleShell.jsx";
import Card from "../../components/common/Card.jsx";
import StatusBadge from "../../components/common/StatusBadge.jsx";
import Button from "../../components/common/Button.jsx";
import Alert from "../../components/ui/Alert.jsx";
import Spinner from "../../components/ui/Spinner.jsx";
import FieldGrid from "../../components/auditoria/FieldGrid.jsx";
import useResource from "../../hooks/useResource.js";
import { auditService } from "../../services/rentasService.js";
import { formatCurrency, formatDate } from "../../lib/format.js";

/**
 * Detalle de reversión: el caso que más mira el auditor, porque deshace un hecho
 * económico ya publicado. Muestra los dos estados que cambió y quién lo autorizó.
 */
export default function ReversionDetallePage() {
  const { reversalId } = useParams();
  const navigate = useNavigate();

  const loader = useCallback(() => auditService.reversalDetail(reversalId), [reversalId]);
  const { data: reversal, loading, error } = useResource(loader);

  if (loading) {
    return (
      <div className="flex items-center justify-center gap-3 py-24">
        <Spinner />
        <span className="text-[13px] text-neutral-400">Cargando la reversión…</span>
      </div>
    );
  }

  const breadcrumb = [
    { id: "pagos", label: "Pagos", path: "/auditor/pagos" },
    { id: "detalle", label: `Reversión #${reversalId}` },
  ];

  if (error || !reversal) {
    return (
      <ModuleShell
        label="Auditoría"
        title="Reversión"
        breadcrumb={breadcrumb}
        homePath="/auditor"
        homeLabel="Dashboard"
      >
        <Alert variant="error" title="No pudimos abrir la reversión">
          {error ?? "La reversión no existe."}
        </Alert>
      </ModuleShell>
    );
  }

  return (
    <ModuleShell
      label="Auditoría"
      title={`Reversión #${reversal.id}`}
      description={`${reversal.taxpayerName} · pago #${reversal.paymentId}`}
      breadcrumb={breadcrumb}
      homePath="/auditor"
      homeLabel="Dashboard"
    >
      <Card title="Datos de la reversión">
        <div className="px-5 py-4">
          <FieldGrid
            columns={4}
            items={[
              { label: "Pago original", value: `#${reversal.paymentId}` },
              { label: "Contribuyente", value: reversal.taxpayerName },
              { label: "Importe revertido", value: formatCurrency(reversal.reversedAmount) },
              { label: "Fecha de reversión", value: formatDate(reversal.reversedAt) },
              { label: "Solicitó", value: reversal.requestedBy },
              { label: "Aprobó", value: reversal.approvedBy },
              { label: "Motivo", value: reversal.reason, span: 2 },
            ]}
          />
        </div>
      </Card>

      <Card title="Estados que cambió" description="Lo que la reversión deshizo.">
        <div className="flex flex-col gap-4 px-5 py-4">
          <Transition
            label="Estado del pago"
            from={reversal.paymentStatusChange.from}
            to={reversal.paymentStatusChange.to}
          />
          {reversal.debtStatusChange && (
            <Transition
              label={`Estado de la deuda #${reversal.debtId}`}
              from={reversal.debtStatusChange.from}
              to={reversal.debtStatusChange.to}
            />
          )}
        </div>
      </Card>

      <Card title="Efecto en la integración">
        <div className="px-5 py-4">
          <p className="text-[13px] text-neutral-600">
            Se publicó{" "}
            <code className="font-semibold text-[#0F2C59]">{reversal.eventPublished}</code> para que
            el módulo de origen vuelva a considerar la obligación impaga.
          </p>
          <div className="mt-4 flex flex-wrap gap-2">
            <Button
              size="sm"
              variant="secondary"
              onClick={() => navigate(`/auditor/pagos/${reversal.paymentId}`)}
            >
              Ver pago
            </Button>
            {reversal.debtId && (
              <Button
                size="sm"
                variant="secondary"
                onClick={() => navigate(`/auditor/deudas/${reversal.debtId}`)}
              >
                Ver deuda
              </Button>
            )}
          </div>
        </div>
      </Card>
    </ModuleShell>
  );
}

function Transition({ label, from, to }) {
  return (
    <div>
      <p className="text-[11px] font-semibold uppercase tracking-[0.1em] text-neutral-400">
        {label}
      </p>
      <div className="mt-1.5 flex flex-wrap items-center gap-2">
        <StatusBadge status={from} />
        <span className="text-neutral-300">→</span>
        <StatusBadge status={to} />
      </div>
    </div>
  );
}
