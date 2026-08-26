import { useCallback } from "react";
import { useParams } from "react-router-dom";
import ModuleShell from "../../components/layout/ModuleShell.jsx";
import Card from "../../components/common/Card.jsx";
import StatusBadge from "../../components/common/StatusBadge.jsx";
import Alert from "../../components/ui/Alert.jsx";
import Spinner from "../../components/ui/Spinner.jsx";
import FieldGrid from "../../components/auditoria/FieldGrid.jsx";
import HistoryTimeline from "../../components/auditoria/HistoryTimeline.jsx";
import useResource from "../../hooks/useResource.js";
import { auditService } from "../../services/rentasService.js";
import { formatDate, formatPercentage } from "../../lib/format.js";

/** Detalle de la exención, con foco en la brecha entre lo pedido y lo otorgado. */
export default function ExencionDetallePage() {
  const { requestId } = useParams();
  const loader = useCallback(() => auditService.exemptionDetail(requestId), [requestId]);
  const { data: exemption, loading, error } = useResource(loader);

  if (loading) {
    return (
      <div className="flex items-center justify-center gap-3 py-24">
        <Spinner />
        <span className="text-[13px] text-neutral-400">Cargando la exención…</span>
      </div>
    );
  }

  const breadcrumb = [
    { id: "exenciones", label: "Exenciones", path: "/auditor/exenciones" },
    { id: "detalle", label: `#${requestId}` },
  ];

  if (error || !exemption) {
    return (
      <ModuleShell
        label="Auditoría"
        title="Exención"
        breadcrumb={breadcrumb}
        homePath="/auditor"
        homeLabel="Dashboard"
      >
        <Alert variant="error" title="No pudimos abrir la exención">
          {error ?? "La solicitud no existe."}
        </Alert>
      </ModuleShell>
    );
  }

  const reduced =
    exemption.status === "APPROVED" && exemption.percentage < exemption.requestedPercentage;

  return (
    <ModuleShell
      label="Auditoría"
      title={`Exención #${exemption.requestId}`}
      description={`${exemption.taxpayerName} · ${exemption.conceptName}`}
      breadcrumb={breadcrumb}
      homePath="/auditor"
      homeLabel="Dashboard"
    >
      {reduced && (
        <Alert variant="info" title="Se aprobó por menos de lo solicitado">
          Solicitado {formatPercentage(exemption.requestedPercentage)}, aprobado{" "}
          {formatPercentage(exemption.percentage)}. El motivo está en las observaciones.
        </Alert>
      )}

      <Card title="Solicitud">
        <div className="px-5 py-4">
          <FieldGrid
            columns={4}
            items={[
              { label: "Contribuyente", value: exemption.taxpayerName },
              { label: "Concepto", value: exemption.conceptName },
              { label: "Fecha de solicitud", value: formatDate(exemption.requestedAt) },
              { label: "Estado", value: <StatusBadge status={exemption.status} /> },
              { label: "Expediente", value: exemption.fileNumber },
              {
                label: "Beneficio social relacionado",
                value: exemption.benefitId ? `Beneficio #${exemption.benefitId} (M8)` : null,
              },
              { label: "Motivo", value: exemption.reason, span: 2 },
            ]}
          />
        </div>
      </Card>

      <Card title="Porcentajes y vigencia">
        <div className="px-5 py-4">
          <FieldGrid
            columns={4}
            items={[
              {
                label: "Porcentaje solicitado",
                value: formatPercentage(exemption.requestedPercentage),
              },
              {
                label: "Porcentaje aprobado",
                value:
                  exemption.percentage === undefined || exemption.percentage === null
                    ? null
                    : formatPercentage(exemption.percentage),
              },
              {
                label: "Vigencia solicitada",
                value: `${formatDate(exemption.requestedFrom)} — ${formatDate(exemption.requestedUntil)}`,
                span: 2,
              },
              {
                label: "Vigencia otorgada",
                value: exemption.validFrom
                  ? `${formatDate(exemption.validFrom)} — ${formatDate(exemption.validUntil)}`
                  : null,
                span: 2,
              },
            ]}
          />
        </div>
      </Card>

      <Card title="Resolución">
        <div className="px-5 py-4">
          <FieldGrid
            columns={3}
            items={[
              {
                label: exemption.status === "REJECTED" ? "Rechazada por" : "Aprobada por",
                value: exemption.resolvedBy,
              },
              {
                label: "Fecha de resolución",
                value: exemption.resolvedAt ? formatDate(exemption.resolvedAt) : null,
              },
              { label: "Exención generada", value: exemption.exemptionId ? `#${exemption.exemptionId}` : null },
              {
                label: exemption.status === "REJECTED" ? "Motivo del rechazo" : "Observaciones",
                value: exemption.reason_rejected ?? exemption.observations,
                span: 2,
              },
              {
                label: "Documentación respaldatoria",
                value:
                  exemption.attachments?.length > 0
                    ? `${exemption.attachments.length} archivo(s) en S3`
                    : null,
              },
            ]}
          />
        </div>
      </Card>

      <Card title="Historial">
        <div className="px-5 py-4">
          <HistoryTimeline entries={exemption.history} />
        </div>
      </Card>
    </ModuleShell>
  );
}
