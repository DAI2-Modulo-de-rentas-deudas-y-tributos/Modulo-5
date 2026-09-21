import { useCallback, useState } from "react";
import ModuleShell from "../../components/layout/ModuleShell.jsx";
import Card from "../../components/common/Card.jsx";
import DataTable from "../../components/common/DataTable.jsx";
import StatusBadge from "../../components/common/StatusBadge.jsx";
import Modal from "../../components/common/Modal.jsx";
import Button from "../../components/common/Button.jsx";
import Alert from "../../components/ui/Alert.jsx";
import FormField from "../../components/ui/FormField.jsx";
import FieldGrid from "../../components/auditoria/FieldGrid.jsx";
import useResource from "../../hooks/useResource.js";
import useTaxpayerIndex from "../../hooks/useTaxpayerIndex.js";
import { planExpirationService } from "../../services/rentasService.js";
import { useAuth } from "../../context/AuthContext.jsx";
import { formatCurrency, formatDate, formatDateTime } from "../../lib/format.js";

export default function CaducidadesPage() {
  const { user } = useAuth();
  const supervisor = user.role === "SUPERVISOR";
  const [selected, setSelected] = useState(null);
  const [feedback, setFeedback] = useState(null);
  const loader = useCallback(
    () => supervisor
      ? planExpirationService.requests({ status: "PENDING_APPROVAL" })
      : planExpirationService.defaulted(),
    [supervisor],
  );
  const { data: rows, loading, error, reload } = useResource(loader, []);
  const { nameOf } = useTaxpayerIndex();

  const columns = supervisor
    ? [
        { key: "id", header: "Solicitud", render: (row) => `#${row.id}` },
        { key: "paymentPlanId", header: "Plan", render: (row) => `#${row.paymentPlanId}` },
        { key: "reason", header: "Motivo" },
        { key: "requestedBy", header: "Solicitó" },
        { key: "requestedAt", header: "Fecha", render: (row) => formatDateTime(row.requestedAt) },
        { key: "status", header: "Estado", render: (row) => <StatusBadge status={row.status} /> },
        { key: "actions", header: "", align: "right", render: (row) => <Button size="sm" variant="primary" onClick={() => setSelected(row)}>Resolver</Button> },
      ]
    : [
        { key: "id", header: "Plan", render: (row) => `#${row.id}` },
        { key: "taxpayerId", header: "Contribuyente", render: (row) => nameOf(row.taxpayerId) },
        { key: "installmentCount", header: "Cuotas", align: "right" },
        { key: "paidAmount", header: "Pagado", align: "right", render: (row) => formatCurrency(row.paidAmount) },
        { key: "outstandingAmount", header: "Saldo", align: "right", render: (row) => formatCurrency(row.outstandingAmount) },
        { key: "status", header: "Estado", render: (row) => <StatusBadge status={row.lifecycle ?? row.status} /> },
        { key: "actions", header: "", align: "right", render: (row) => <Button size="sm" variant="accent" onClick={() => setSelected(row)}>Solicitar caducidad</Button> },
      ];

  return (
    <ModuleShell
      label={supervisor ? "Supervisión" : "Operación"}
      title="Caducidad de planes"
      highlight={supervisor ? "por resolver" : "con incumplimientos"}
      description={supervisor
        ? "Revisá cuotas vencidas, pagos y tolerancia antes de autorizar o rechazar."
        : "Detectá los planes que superaron la tolerancia y solicitá formalmente su caducidad."}
      breadcrumb={[{ id: "caducidades", label: "Caducidad de planes" }]}
    >
      {feedback && <Alert variant="success" title="Operación registrada" onDismiss={() => setFeedback(null)}>{feedback}</Alert>}
      {error && <Alert variant="error" title="No pudimos cargar la bandeja">{error}</Alert>}
      <Card title={supervisor ? "Solicitudes pendientes" : "Planes incumplidos"}>
        <DataTable
          columns={columns}
          rows={rows ?? []}
          rowKey={(row) => row.id}
          loading={loading}
          emptyIconName="CircleCheckBig"
          emptyTitle="Sin pendientes"
          emptyDescription="No hay planes que requieran intervención."
        />
      </Card>
      {selected && (
        <ExpirationModal
          item={selected}
          supervisor={supervisor}
          onClose={() => setSelected(null)}
          onDone={(result) => {
            setSelected(null);
            setFeedback(supervisor
              ? `La solicitud #${result.id} quedó ${result.status === "APPROVED" ? "autorizada" : "rechazada"}.`
              : `La solicitud #${result.id} quedó pendiente de autorización.`);
            reload();
          }}
        />
      )}
    </ModuleShell>
  );
}

function ExpirationModal({ item, supervisor, onClose, onDone }) {
  const id = supervisor ? item.id : item.id;
  const loader = useCallback(
    () => supervisor ? planExpirationService.detail(id) : planExpirationService.defaultedDetail(id),
    [id, supervisor],
  );
  const { data: detail, loading, error } = useResource(loader);
  const [decision, setDecision] = useState("APPROVED");
  const [reason, setReason] = useState("");
  const [submitError, setSubmitError] = useState(null);
  const [submitting, setSubmitting] = useState(false);
  const plan = supervisor ? detail?.plan : detail;
  const installments = plan?.installments ?? [];
  const overdue = installments.filter((installment) => installment.overdue || installment.status === "OVERDUE");

  const submit = async () => {
    if (!reason.trim() && (!supervisor || decision === "REJECTED")) {
      setSubmitError("Indicá un motivo para registrar la decisión.");
      return;
    }
    setSubmitting(true);
    setSubmitError(null);
    try {
      onDone(supervisor
        ? await planExpirationService.resolve({ id: item.id, status: decision, reason: reason.trim() })
        : await planExpirationService.request({ planId: item.id, reason: reason.trim() }));
    } catch (caught) {
      setSubmitError(caught.message);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open
      size="xl"
      title={supervisor ? `Resolver solicitud #${item.id}` : `Solicitar caducidad del plan #${item.id}`}
      description="La caducidad sólo se aplica si el Supervisor la autoriza."
      onClose={onClose}
      footer={<><Button variant="secondary" onClick={onClose}>Cancelar</Button><Button variant={supervisor && decision === "REJECTED" ? "danger" : "primary"} loading={submitting} disabled={loading} onClick={submit}>{supervisor ? (decision === "APPROVED" ? "Autorizar caducidad" : "Rechazar") : "Enviar solicitud"}</Button></>}
    >
      {error && <Alert variant="error" title="No pudimos abrir el plan">{error}</Alert>}
      {submitError && <Alert variant="error" title="No se pudo completar">{submitError}</Alert>}
      {plan && (
        <>
          <FieldGrid
            columns={4}
            items={[
              { label: "Plan", value: `#${plan.id}` },
              { label: "Contribuyente", value: plan.taxpayer?.name ?? `#${plan.taxpayerId}` },
              { label: "Saldo pendiente", value: formatCurrency(plan.outstandingAmount) },
              { label: "Cuotas vencidas", value: overdue.length },
              { label: "Tolerancia configurada", value: plan.configuration ? `${plan.configuration.maxOverdueInstallments} cuota(s)` : "No disponible" },
              { label: "Resultado", value: plan.configuration && overdue.length > plan.configuration.maxOverdueInstallments ? "Tolerancia superada" : "Requiere revisión" },
            ]}
          />
          <DataTable
            columns={[
              { key: "number", header: "Cuota" },
              { key: "dueDate", header: "Vencimiento", render: (row) => formatDate(row.dueDate) },
              { key: "totalAmount", header: "Importe", align: "right", render: (row) => formatCurrency(row.totalAmount) },
              { key: "paidAmount", header: "Pagado", align: "right", render: (row) => formatCurrency(row.paidAmount) },
              { key: "status", header: "Estado", render: (row) => <StatusBadge status={row.status} /> },
            ]}
            rows={installments}
            rowKey={(row) => row.id}
            emptyTitle="Sin cuotas"
          />
          {supervisor && (
            <FormField label="Decisión" name="decision" type="select" value={decision} onChange={(event) => setDecision(event.target.value)} options={[{ value: "APPROVED", label: "Autorizar" }, { value: "REJECTED", label: "Rechazar" }]} />
          )}
          <FormField
            label={supervisor ? "Observación o motivo" : "Motivo de la solicitud"}
            name="reason"
            type="textarea"
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            placeholder="Describí el incumplimiento evaluado."
            required={!supervisor || decision === "REJECTED"}
          />
        </>
      )}
    </Modal>
  );
}
