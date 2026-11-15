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
import { EVENT_LABELS, MODULE_LABELS } from "../../config/etiquetasModulos.js";
import { formatDateTime } from "../../lib/format.js";

/**
 * El backend guarda el payload como texto JSON. Serializarlo de nuevo lo mostraría
 * escapado, así que se intenta formatearlo y, si no es JSON válido, se muestra crudo.
 */
function formatearPayload(payload) {
  if (payload == null) return "—";
  if (typeof payload !== "string") return JSON.stringify(payload, null, 2);
  try {
    return JSON.stringify(JSON.parse(payload), null, 2);
  } catch {
    return payload;
  }
}

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
    { id: "detalle", label: EVENT_LABELS[event?.eventType] ?? "Evento" },
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
      title={EVENT_LABELS[event.eventType] ?? "Evento de integración"}
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

      <Card title="Resumen de la operación">
        <div className="px-5 py-4">
          <FieldGrid
            columns={3}
            items={[
              { label: "Estado", value: <StatusBadge status={event.status} /> },
              {
                label: "Módulo origen",
                value: MODULE_LABELS[event.sourceModule] ?? event.sourceModule,
              },
              {
                label: "Módulo destino",
                value: MODULE_LABELS[event.destinationModule] ?? event.destinationModule,
              },
              { label: "Fecha", value: formatDateTime(event.occurredAt) },
              {
                label: "Fecha de procesamiento",
                value: event.processedAt ? formatDateTime(event.processedAt) : null,
              },
              { label: "Intentos", value: event.attempts },
            ]}
          />
        </div>
      </Card>

      <details className="rounded-xl border border-neutral-200 bg-white shadow-sm">
        <summary className="cursor-pointer px-5 py-4 text-[13px] font-semibold text-[#0F2C59]">Detalles técnicos</summary>
        <div className="border-t border-neutral-100 px-5 py-4">
          <FieldGrid columns={2} items={[
            { label: "Identificador del evento", value: <span className="break-all tabular-nums">{event.eventId}</span> },
            { label: "Tipo técnico", value: event.eventType },
            { label: "Código de origen", value: event.sourceModule },
            { label: "Código de destino", value: event.destinationModule },
          ]} />
          <h2 className="mt-5 text-[12px] font-semibold text-neutral-600">Datos recibidos</h2>
          <pre className="mt-2 overflow-x-auto rounded-lg border border-neutral-200 bg-neutral-50 p-4 text-[12px] leading-relaxed text-neutral-700">
            {formatearPayload(event.payload)}
          </pre>
        </div>
      </details>

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
