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
import { formatDateTime } from "../../lib/format.js";

/** Detalle del evento: el payload tal como llegó y qué produjo dentro de Rentas. */
export default function IntegracionDetallePage() {
  const { eventId } = useParams();
  const navigate = useNavigate();

  const loader = useCallback(() => auditService.integrationDetail(eventId), [eventId]);
  const { data: event, loading, error } = useResource(loader);

  if (loading) {
    return (
      <div className="flex items-center justify-center gap-3 py-24">
        <Spinner />
        <span className="text-[13px] text-neutral-400">Cargando el evento…</span>
      </div>
    );
  }

  const breadcrumb = [
    { id: "integraciones", label: "Integraciones", path: "/auditor/integraciones" },
    { id: "detalle", label: event?.eventType ?? "Evento" },
  ];

  if (error || !event) {
    return (
      <ModuleShell
        label="Auditoría"
        title="Evento"
        breadcrumb={breadcrumb}
        homePath="/auditor"
        homeLabel="Dashboard"
      >
        <Alert variant="error" title="No pudimos abrir el evento">
          {error ?? "El evento no existe."}
        </Alert>
      </ModuleShell>
    );
  }

  return (
    <ModuleShell
      label="Auditoría"
      title={event.eventType}
      description={`${MODULE_LABELS[event.sourceModule] ?? event.sourceModule} → ${MODULE_LABELS[event.destinationModule] ?? event.destinationModule}`}
      breadcrumb={breadcrumb}
      homePath="/auditor"
      homeLabel="Dashboard"
    >
      {event.error && (
        <Alert variant="error" title={`El evento quedó en ${event.status === "DLQ" ? "DLQ" : "reintento"}`}>
          {event.error}
        </Alert>
      )}

      <Card title="Datos del evento">
        <div className="px-5 py-4">
          <FieldGrid
            columns={4}
            items={[
              { label: "Event ID", value: <span className="tabular-nums">{event.eventId}</span>, span: 2 },
              { label: "Tipo", value: event.eventType },
              { label: "Estado", value: <StatusBadge status={event.status} /> },
              {
                label: "Módulo origen",
                value: MODULE_LABELS[event.sourceModule] ?? event.sourceModule,
              },
              {
                label: "Módulo destino",
                value: MODULE_LABELS[event.destinationModule] ?? event.destinationModule,
              },
              { label: "Fecha de recepción", value: formatDateTime(event.occurredAt) },
              {
                label: "Fecha de procesamiento",
                value: event.processedAt ? formatDateTime(event.processedAt) : null,
              },
              { label: "Intentos", value: event.attempts },
            ]}
          />
        </div>
      </Card>

      <Card title="Payload" description="El campo `data` del envelope común, tal como viajó.">
        <div className="px-5 py-4">
          <pre className="overflow-x-auto rounded-lg border border-neutral-200 bg-neutral-50 p-4 text-[12px] leading-relaxed text-neutral-700">
            {JSON.stringify(event.payload, null, 2)}
          </pre>
        </div>
      </Card>

      <Card title="Resultado" description="Qué generó el evento dentro de Rentas.">
        <div className="px-5 py-4">
          {event.result ? (
            <div className="flex flex-wrap gap-2">
              {event.result.settlementId && (
                <Button
                  size="sm"
                  variant="secondary"
                  onClick={() => navigate(`/auditor/liquidaciones/${event.result.settlementId}`)}
                >
                  Liquidación #{event.result.settlementId}
                </Button>
              )}
              {event.result.debtId && (
                <Button
                  size="sm"
                  variant="secondary"
                  onClick={() => navigate(`/auditor/deudas/${event.result.debtId}`)}
                >
                  Deuda #{event.result.debtId}
                </Button>
              )}
              {event.result.reversalId && (
                <Button
                  size="sm"
                  variant="secondary"
                  onClick={() => navigate(`/auditor/reversiones/${event.result.reversalId}`)}
                >
                  Reversión #{event.result.reversalId}
                </Button>
              )}
              {event.result.ticketId && (
                <Button
                  size="sm"
                  variant="secondary"
                  onClick={() => navigate(`/auditor/tickets/${event.result.ticketId}`)}
                >
                  Ticket #{event.result.ticketId}
                </Button>
              )}
            </div>
          ) : (
            <p className="text-[13px] text-neutral-400">
              El evento todavía no produjo ningún cambio: no llegó a procesarse.
            </p>
          )}
        </div>
      </Card>

      <Card title="Procesamiento">
        <div className="px-5 py-4">
          <HistoryTimeline entries={event.processing} />
        </div>
      </Card>
    </ModuleShell>
  );
}
