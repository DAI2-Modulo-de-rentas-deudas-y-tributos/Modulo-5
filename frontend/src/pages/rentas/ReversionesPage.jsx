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
import Spinner from "../../components/ui/Spinner.jsx";
import FieldGrid from "../../components/auditoria/FieldGrid.jsx";
import useResource from "../../hooks/useResource.js";
import { paymentReversalService } from "../../services/rentasService.js";
import { formatCurrency, formatDateTime, labelFor } from "../../lib/format.js";

/** Bandeja del Supervisor: aprobar o rechazar una solicitud no ejecuta la reversión. */
export default function ReversionesPage() {
  const [filters, setFilters] = useState({ status: "PENDING_APPROVAL", from: "", to: "" });
  const [selected, setSelected] = useState(null);
  const [feedback, setFeedback] = useState(null);
  const loader = useCallback(() => paymentReversalService.list(filters), [filters]);
  const { data: reversals, loading, error, reload } = useResource(loader, []);

  const columns = [
    { key: "id", header: "Solicitud", render: (row) => <span className="font-semibold tabular-nums">#{row.id}</span> },
    {
      key: "payment",
      header: "Pago",
      render: (row) => (
        <div className="flex flex-col">
          <span className="font-medium text-neutral-800">{row.payment.receiptNumber}</span>
          <span className="text-[12px] text-neutral-400">Pago #{row.paymentId}</span>
        </div>
      ),
    },
    { key: "taxpayer", header: "Contribuyente", render: (row) => row.taxpayer.name },
    { key: "amount", header: "Importe", align: "right", render: (row) => formatCurrency(row.payment.amountPaid) },
    { key: "requestedBy", header: "Solicitó" },
    { key: "requestedAt", header: "Fecha", render: (row) => formatDateTime(row.requestedAt) },
    { key: "status", header: "Estado", render: (row) => <StatusBadge status={row.status} /> },
    {
      key: "actions",
      header: "",
      align: "right",
      render: (row) => row.status === "PENDING_APPROVAL"
        ? <Button size="sm" variant="primary" onClick={() => setSelected(row)}>Revisar</Button>
        : <Button size="sm" variant="secondary" onClick={() => setSelected(row)}>Ver detalle</Button>,
    },
  ];

  return (
    <ModuleShell
      label="Resoluciones"
      title="Reversiones de pago"
      highlight="pendientes"
      description="Revisá la evidencia del pago y autorizá o rechazá la solicitud del Cajero."
      breadcrumb={[{ id: "reversiones", label: "Reversiones de pago" }]}
    >
      {feedback && <Alert variant="success" title="Solicitud resuelta" onDismiss={() => setFeedback(null)}>{feedback}</Alert>}
      {error && <Alert variant="error" title="No pudimos cargar las solicitudes">{error}</Alert>}

      <Alert variant="info" title="La aprobación no modifica el pago">
        Aprobar habilita la ejecución posterior por Personal de Rentas. En esta etapa el pago,
        sus imputaciones y las deudas conservan sus valores.
      </Alert>

      <Card title="Bandeja de solicitudes" description="Abrí una solicitud para consultar pago, imputaciones, deuda y motivo.">
        <FilterBar
          filters={[
            {
              name: "status",
              label: "Estado",
              options: [
                { value: "PENDING_APPROVAL", label: "Pendiente de aprobación" },
                { value: "APPROVED", label: "Aprobada" },
                { value: "REJECTED", label: "Rechazada" },
                { value: "EXECUTED", label: "Ejecutada" },
              ],
            },
            { name: "from", label: "Desde", type: "date" },
            { name: "to", label: "Hasta", type: "date" },
          ]}
          values={filters}
          onFilterChange={(name, value) => setFilters((previous) => ({ ...previous, [name]: value }))}
        />
        <DataTable
          columns={columns}
          rows={reversals ?? []}
          rowKey={(row) => row.id}
          loading={loading}
          emptyIconName="Undo2"
          emptyTitle="Sin solicitudes"
          emptyDescription="No hay reversiones que coincidan con esos filtros."
        />
      </Card>

      {selected && (
        <ResolutionModal
          reversal={selected}
          onClose={() => setSelected(null)}
          onDone={(resolved) => {
            setSelected(null);
            setFeedback(
              resolved.status === "APPROVED"
                ? `La solicitud #${resolved.id} fue aprobada. El pago sigue confirmado hasta su ejecución por Rentas.`
                : `La solicitud #${resolved.id} fue rechazada y el pago no fue modificado.`,
            );
            reload();
          }}
        />
      )}
    </ModuleShell>
  );
}

function ResolutionModal({ reversal, onClose, onDone }) {
  const [decision, setDecision] = useState("APPROVED");
  const [observation, setObservation] = useState("");
  const [submitError, setSubmitError] = useState(null);
  const [submitting, setSubmitting] = useState(false);
  const loader = useCallback(() => paymentReversalService.detail(reversal.id), [reversal.id]);
  const { data: detail, loading, error } = useResource(loader);

  const onSubmit = async (event) => {
    event.preventDefault();
    setSubmitError(null);
    if (decision === "REJECTED" && observation.trim().length < 5) {
      setSubmitError("Indicá el motivo del rechazo con al menos 5 caracteres.");
      return;
    }
    setSubmitting(true);
    try {
      onDone(
        decision === "APPROVED"
          ? await paymentReversalService.approve({ id: reversal.id, observation: observation.trim() })
          : await paymentReversalService.reject({ id: reversal.id, reason: observation.trim() }),
      );
    } catch (caught) {
      setSubmitError(caught.message);
    } finally {
      setSubmitting(false);
    }
  };

  const canResolve = detail?.status === "PENDING_APPROVAL";

  return (
    <Modal
      open
      size="xl"
      title={`Solicitud de reversión #${reversal.id}`}
      description={detail ? `${detail.payment.receiptNumber} · solicitada por ${detail.requestedBy}` : "Cargando evidencia del pago"}
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>{canResolve ? "Cancelar" : "Cerrar"}</Button>
          {canResolve && (
            <Button variant={decision === "APPROVED" ? "primary" : "danger"} loading={submitting} onClick={onSubmit}>
              {decision === "APPROVED" ? "Aprobar solicitud" : "Rechazar solicitud"}
            </Button>
          )}
        </>
      }
    >
      {loading && (
        <div className="flex items-center justify-center gap-3 py-12">
          <Spinner />
          <span className="text-[13px] text-neutral-400">Cargando pago e imputaciones…</span>
        </div>
      )}
      {error && <Alert variant="error" title="No pudimos abrir la solicitud">{error}</Alert>}
      {submitError && <Alert variant="error" title="No se pudo registrar la decisión">{submitError}</Alert>}

      {detail && (
        <>
          <section className="rounded-lg border border-neutral-200 bg-neutral-50 px-5 py-4">
            <h3 className="text-[13px] font-bold text-[#0F2C59]">Pago solicitado</h3>
            <div className="mt-4">
              <FieldGrid
                columns={4}
                items={[
                  { label: "Comprobante", value: detail.payment.receiptNumber },
                  { label: "Contribuyente", value: detail.taxpayer.name },
                  { label: "Importe", value: formatCurrency(detail.payment.amountPaid) },
                  { label: "Fecha", value: formatDateTime(detail.payment.paidAt) },
                  { label: "Medio", value: labelFor(detail.payment.method) },
                  { label: "Origen", value: labelFor(detail.payment.channel) },
                  { label: "Estado del pago", value: <StatusBadge status={detail.payment.status} /> },
                  { label: "Registrado por", value: detail.payment.registeredBy ?? "Canal digital" },
                ]}
              />
            </div>
          </section>

          <section>
            <h3 className="mb-2 text-[13px] font-bold text-[#0F2C59]">Imputaciones y deuda</h3>
            <div className="overflow-hidden rounded-lg border border-neutral-200">
              <DataTable
                columns={[
                  {
                    key: "target",
                    header: "Destino",
                    render: (row) => row.debtId ? `Deuda #${row.debtId}` : `Cuota #${row.installmentId}`,
                  },
                  { key: "amount", header: "Aplicado", align: "right", render: (row) => formatCurrency(row.amount) },
                  { key: "allocationStatus", header: "Imputación", render: (row) => <StatusBadge status={row.status} /> },
                  {
                    key: "debtStatus",
                    header: "Estado de deuda",
                    render: (row) => row.debt ? <StatusBadge status={row.debt.status} /> : <span className="text-neutral-400">Plan de pago</span>,
                  },
                  { key: "balance", header: "Saldo", align: "right", render: (row) => row.debt ? formatCurrency(row.debt.outstandingAmount) : "—" },
                ]}
                rows={detail.allocations}
                rowKey={(row) => row.id}
                emptyIconName="FileWarning"
                emptyTitle="Sin imputaciones"
                emptyDescription="El pago no fue aplicado a una deuda o cuota."
              />
            </div>
          </section>

          <section className="rounded-lg border border-amber-200 bg-amber-50 px-4 py-3">
            <p className="text-[11px] font-semibold uppercase tracking-[0.08em] text-amber-700">Motivo informado por Caja</p>
            <p className="mt-1 text-[14px] leading-relaxed text-amber-900">{detail.reason}</p>
          </section>

          {canResolve ? (
            <form onSubmit={onSubmit} noValidate className="grid grid-cols-1 gap-4 md:grid-cols-2">
              <FormField
                label="Decisión"
                name="decision"
                type="select"
                value={decision}
                onChange={(event) => setDecision(event.target.value)}
                options={[
                  { value: "APPROVED", label: "Aprobar" },
                  { value: "REJECTED", label: "Rechazar" },
                ]}
                required
              />
              <FormField
                label={decision === "REJECTED" ? "Motivo del rechazo" : "Observación"}
                name="observation"
                type="textarea"
                value={observation}
                onChange={(event) => setObservation(event.target.value)}
                placeholder={decision === "REJECTED" ? "Explicá por qué se rechaza la solicitud." : "Observación opcional para la resolución."}
                required={decision === "REJECTED"}
              />
            </form>
          ) : (
            <Alert variant="info" title={`Solicitud ${labelFor(detail.status).toLocaleLowerCase("es")}`}>
              La decisión fue registrada por {detail.resolvedBy ?? "el Supervisor"} el {formatDateTime(detail.resolvedAt)}.
            </Alert>
          )}
        </>
      )}
    </Modal>
  );
}
