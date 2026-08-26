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
import { formatDate, labelFor } from "../../lib/format.js";

/** Detalle del ticket: el reclamo, su estado en los dos módulos y a qué refiere. */
export default function TicketDetallePage() {
  const { ticketId } = useParams();
  const navigate = useNavigate();

  const loader = useCallback(() => auditService.ticketDetail(ticketId), [ticketId]);
  const { data: ticket, loading, error } = useResource(loader);

  if (loading) {
    return (
      <div className="flex items-center justify-center gap-3 py-24">
        <Spinner />
        <span className="text-[13px] text-neutral-400">Cargando el ticket…</span>
      </div>
    );
  }

  const breadcrumb = [
    { id: "tickets", label: "Tickets", path: "/auditor/tickets" },
    { id: "detalle", label: `#${ticketId}` },
  ];

  if (error || !ticket) {
    return (
      <ModuleShell
        label="Auditoría"
        title="Ticket"
        breadcrumb={breadcrumb}
        homePath="/auditor"
        homeLabel="Dashboard"
      >
        <Alert variant="error" title="No pudimos abrir el ticket">
          {error ?? "El ticket no existe."}
        </Alert>
      </ModuleShell>
    );
  }

  return (
    <ModuleShell
      label="Auditoría"
      title={`Ticket #${ticket.ticketId}`}
      description={`${ticket.taxpayerName} · ${ticket.subject}`}
      breadcrumb={breadcrumb}
      homePath="/auditor"
      homeLabel="Dashboard"
    >
      {ticket.escalated && (
        <Alert variant="info" title="Reclamo escalado">
          El ticket fue derivado a supervisión antes de resolverse.
        </Alert>
      )}

      <Card title="Datos generales">
        <div className="px-5 py-4">
          <FieldGrid
            columns={4}
            items={[
              { label: "Ciudadano", value: ticket.taxpayerName },
              { label: "Categoría", value: ticket.subject },
              { label: "Prioridad", value: <StatusBadge status={ticket.priority} /> },
              { label: "Fecha de recepción", value: formatDate(ticket.createdAt) },
              {
                label: "Estado externo (M2)",
                value: <StatusBadge status={ticket.externalStatus} />,
              },
              {
                label: "Estado de gestión Rentas",
                value: <StatusBadge status={ticket.status} />,
              },
              { label: "Asignado a", value: ticket.assignedTo },
              { label: "Escalado", value: ticket.escalated ? "Sí" : "No" },
            ]}
          />
        </div>
      </Card>

      <Card title="Descripción">
        <div className="px-5 py-4">
          <p className="text-[14px] leading-relaxed text-neutral-700">“{ticket.description}”</p>
          {ticket.additionalInformation && (
            <p className="mt-3 text-[13px] text-neutral-500">
              Información adicional: {ticket.additionalInformation}
            </p>
          )}
        </div>
      </Card>

      <Card title="Referencia relacionada" description="La entidad de Rentas sobre la que reclama.">
        <div className="flex flex-col gap-3 px-5 py-4">
          <FieldGrid
            columns={3}
            items={[
              { label: "Tipo", value: labelFor(ticket.reference?.type) },
              { label: "Pago", value: ticket.payment ? `#${ticket.payment.id}` : null },
              { label: "Deuda", value: ticket.debt ? `#${ticket.debt.id}` : null },
            ]}
          />
          <div className="flex flex-wrap gap-2">
            {ticket.payment && (
              <Button
                size="sm"
                variant="secondary"
                onClick={() => navigate(`/auditor/pagos/${ticket.payment.id}`)}
              >
                Ver pago
              </Button>
            )}
            {ticket.debt && (
              <Button
                size="sm"
                variant="secondary"
                onClick={() => navigate(`/auditor/deudas/${ticket.debt.id}`)}
              >
                Ver deuda
              </Button>
            )}
          </div>
        </div>
      </Card>

      <Card title="Historial">
        <div className="px-5 py-4">
          <HistoryTimeline entries={ticket.history} />
        </div>
      </Card>
    </ModuleShell>
  );
}
