import { useCallback, useState } from "react";
import ModuleShell from "../../components/layout/ModuleShell.jsx";
import Card from "../../components/common/Card.jsx";
import DataTable from "../../components/common/DataTable.jsx";
import FilterBar from "../../components/common/FilterBar.jsx";
import StatusBadge from "../../components/common/StatusBadge.jsx";
import Modal from "../../components/common/Modal.jsx";
import Button from "../../components/common/Button.jsx";
import Alert from "../../components/ui/Alert.jsx";
import FormField from "../../components/ui/FormField.jsx";
import useResource from "../../hooks/useResource.js";
import useTaxpayerIndex from "../../hooks/useTaxpayerIndex.js";
import { ticketService } from "../../services/rentasService.js";
import { useAuth } from "../../context/AuthContext.jsx";
import { formatDateTime, labelFor } from "../../lib/format.js";

/** Estados acordados con M2: viajan dentro de updateTicketStatus, no como eventos sueltos. */
const TICKET_STATUSES = [
  { value: "IN_PROGRESS", label: "En curso" },
  { value: "WAITING_FOR_INFORMATION", label: "Esperando información" },
  { value: "COMPLETED", label: "Resuelto" },
  { value: "REJECTED", label: "Rechazado" },
];

/**
 * Tickets: reclamos que M2 deriva a Rentas (ticketCreated / ticketUpdated).
 * El área responde cambiando el estado con updateTicketStatus.
 */
export default function TicketsPage() {
  const [filters, setFilters] = useState({ status: "", priority: "" });
  const [selected, setSelected] = useState(null);
  const [feedback, setFeedback] = useState(null);

  const loader = useCallback(() => ticketService.list(filters), [filters]);
  const { data: tickets, loading, error, reload } = useResource(loader, []);
  const { nameOf } = useTaxpayerIndex();

  const onFilterChange = (name, value) =>
    setFilters((previous) => ({ ...previous, [name]: value }));

  const columns = [
    {
      key: "ticketId",
      header: "Ticket",
      render: (row) => <span className="tabular-nums">#{row.ticketId}</span>,
    },
    { key: "citizen", header: "Ciudadano", render: (row) => nameOf(row.citizenId) },
    {
      key: "description",
      header: "Reclamo",
      render: (row) => (
        <div className="flex max-w-xs flex-col">
          <span className="truncate text-neutral-700">{row.description}</span>
          {row.additionalInformation && (
            <span className="truncate text-[12px] text-neutral-400">
              {row.additionalInformation}
            </span>
          )}
        </div>
      ),
    },
    { key: "priority", header: "Prioridad", render: (row) => <StatusBadge status={row.priority} /> },
    { key: "createdAt", header: "Ingreso", render: (row) => formatDateTime(row.createdAt) },
    {
      key: "assignedTo",
      header: "Asignado",
      render: (row) => row.assignedTo ?? <span className="text-neutral-300">Sin asignar</span>,
    },
    { key: "status", header: "Estado", render: (row) => <StatusBadge status={row.status} /> },
    {
      key: "actions",
      header: "",
      align: "right",
      render: (row) => (
        <Button size="sm" variant="secondary" onClick={() => setSelected(row)}>
          Cambiar estado
        </Button>
      ),
    },
  ];

  return (
    <ModuleShell
      label="Atención"
      title="Tickets"
      highlight="derivados"
      description="Reclamos de contribuyentes recibidos desde Atención Ciudadana."
      breadcrumb={[{ id: "tickets", label: "Tickets" }]}
    >
      {feedback && (
        <Alert variant={feedback.variant} title={feedback.title} onDismiss={() => setFeedback(null)}>
          {feedback.message}
        </Alert>
      )}
      {error && <Alert variant="error" title="No pudimos cargar los tickets">{error}</Alert>}

      <Alert variant="info" title="El ticket es propiedad del Módulo 2">
        Rentas no edita el reclamo: sólo informa el avance publicando{" "}
        <code className="font-semibold">updateTicketStatus</code>. Los estados deben coincidir con
        los que M2 tiene definidos.
      </Alert>

      <Card title="Bandeja de reclamos" description="Priorizá los de prioridad alta y los que esperan respuesta.">
        <FilterBar
          filters={[
            {
              name: "status",
              label: "Estado",
              options: [
                { value: "OPEN", label: labelFor("OPEN") },
                ...TICKET_STATUSES,
              ],
            },
            {
              name: "priority",
              label: "Prioridad",
              options: [
                { value: "HIGH", label: "Alta" },
                { value: "MEDIUM", label: "Media" },
                { value: "LOW", label: "Baja" },
              ],
            },
          ]}
          values={filters}
          onFilterChange={onFilterChange}
        />

        <DataTable
          columns={columns}
          rows={tickets ?? []}
          rowKey={(row) => row.ticketId}
          loading={loading}
          emptyIconName="MessageSquare"
          emptyTitle="Bandeja vacía"
          emptyDescription="No hay reclamos que coincidan con los filtros."
        />
      </Card>

      {selected && (
        <UpdateTicketModal
          ticket={selected}
          citizenName={nameOf(selected.citizenId)}
          onClose={() => setSelected(null)}
          onDone={(ticket) => {
            setSelected(null);
            setFeedback({
              variant: "success",
              title: "Estado actualizado",
              message: `El ticket #${ticket.ticketId} quedó en "${labelFor(ticket.status)}" y se notificó a M2.`,
            });
            reload();
          }}
        />
      )}
    </ModuleShell>
  );
}

/** UpdateTicketStatusRequest: el rechazo exige motivo porque viaja en el payload. */
function UpdateTicketModal({ ticket, citizenName, onClose, onDone }) {
  const { user } = useAuth();
  const [status, setStatus] = useState(ticket.status === "OPEN" ? "IN_PROGRESS" : ticket.status);
  const [reason, setReason] = useState("");
  const [takeOwnership, setTakeOwnership] = useState(!ticket.assignedTo);
  const [error, setError] = useState(null);
  const [submitting, setSubmitting] = useState(false);

  const onSubmit = async (event) => {
    event.preventDefault();
    setError(null);
    if (status === "REJECTED" && !reason.trim()) {
      setError("El motivo del rechazo viaja en el evento: es obligatorio.");
      return;
    }
    setSubmitting(true);
    try {
      onDone(
        await ticketService.updateStatus({
          ticketId: ticket.ticketId,
          status,
          reason,
          assignedTo: takeOwnership ? user.username : undefined,
        }),
      );
    } catch (caught) {
      setError(caught.message);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open
      title={`Ticket #${ticket.ticketId}`}
      description={`${citizenName} · prioridad ${labelFor(ticket.priority).toLowerCase()}`}
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>
            Cancelar
          </Button>
          <Button variant="primary" loading={submitting} onClick={onSubmit}>
            Informar a M2
          </Button>
        </>
      }
    >
      {error && <Alert variant="error" title="No se pudo actualizar">{error}</Alert>}

      <div className="rounded-lg border border-neutral-200 bg-neutral-50 p-4 text-[13px] text-neutral-600">
        <p>{ticket.description}</p>
        {ticket.additionalInformation && (
          <p className="mt-2 text-neutral-500">
            <span className="font-medium text-neutral-700">Información adicional:</span>{" "}
            {ticket.additionalInformation}
          </p>
        )}
      </div>

      <FormField
        label="Nuevo estado"
        name="status"
        type="select"
        value={status}
        onChange={(event) => setStatus(event.target.value)}
        options={TICKET_STATUSES}
        required
      />

      {status === "REJECTED" && (
        <FormField
          label="Motivo del rechazo"
          name="reason"
          type="textarea"
          placeholder="El caso no corresponde al módulo de Rentas"
          value={reason}
          onChange={(event) => setReason(event.target.value)}
          required
        />
      )}

      <label className="flex items-center gap-2 text-[13px] text-neutral-600">
        <input
          type="checkbox"
          checked={takeOwnership}
          onChange={(event) => setTakeOwnership(event.target.checked)}
          className="h-4 w-4 rounded border-neutral-300 accent-[#D63031]"
        />
        Asignarme este ticket
      </label>
    </Modal>
  );
}
