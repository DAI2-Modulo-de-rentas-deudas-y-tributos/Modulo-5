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
import useTaxConcepts from "../../hooks/useTaxConcepts.js";
import useTaxpayerIndex from "../../hooks/useTaxpayerIndex.js";
import { liquidationRunService } from "../../services/rentasService.js";
import { useAuth } from "../../context/AuthContext.jsx";
import { formatCurrency, formatDate, formatDateTime } from "../../lib/format.js";

export default function CorridasMasivasPage() {
  const { user } = useAuth();
  const supervisor = user.role === "SUPERVISOR";
  const [selected, setSelected] = useState(null);
  const [feedback, setFeedback] = useState(null);
  const loader = useCallback(
    () => liquidationRunService.list({ status: supervisor ? "PENDING_APPROVAL" : "" }),
    [supervisor],
  );
  const { data: runs, loading, error, reload } = useResource(loader, []);
  const { concepts } = useTaxConcepts();
  const conceptName = (id) => concepts.find((concept) => concept.id === id)?.name ?? `Concepto #${id}`;

  const columns = [
    { key: "id", header: "Corrida", render: (row) => `#${row.id}` },
    { key: "taxConceptId", header: "Concepto", render: (row) => conceptName(row.taxConceptId) },
    { key: "period", header: "Período" },
    { key: "dueDate", header: "Vencimiento", render: (row) => formatDate(row.dueDate) },
    { key: "validItems", header: "Válidos", align: "right" },
    { key: "errorItems", header: "Errores", align: "right" },
    { key: "estimatedTotalAmount", header: "Importe", align: "right", render: (row) => formatCurrency(row.estimatedTotalAmount) },
    { key: "status", header: "Estado", render: (row) => <StatusBadge status={row.status} /> },
    { key: "actions", header: "", align: "right", render: (row) => <Button size="sm" variant={supervisor ? "primary" : "secondary"} onClick={() => setSelected(row)}>{supervisor ? "Resolver" : "Ver"}</Button> },
  ];

  return (
    <ModuleShell
      label={supervisor ? "Supervisión" : "Operación"}
      title="Corridas masivas"
      highlight={supervisor ? "pendientes" : "de liquidación"}
      description="Revisá el resumen, los contribuyentes alcanzados, los importes y los errores antes de continuar."
      breadcrumb={[{ id: "corridas", label: "Corridas masivas" }]}
    >
      {feedback && <Alert variant="success" title="Corrida actualizada" onDismiss={() => setFeedback(null)}>{feedback}</Alert>}
      {error && <Alert variant="error" title="No pudimos cargar las corridas">{error}</Alert>}
      <Card title={supervisor ? "Pendientes de aprobación" : "Historial de corridas"}>
        <DataTable columns={columns} rows={runs ?? []} rowKey={(row) => row.id} loading={loading} emptyIconName="Layers" emptyTitle="Sin corridas" emptyDescription="No hay corridas con ese estado." />
      </Card>
      {selected && (
        <RunModal
          run={selected}
          supervisor={supervisor}
          onClose={() => setSelected(null)}
          onDone={(result) => {
            setSelected(null);
            setFeedback(`La corrida #${result.id} quedó en estado ${result.status}.`);
            reload();
          }}
        />
      )}
    </ModuleShell>
  );
}

function RunModal({ run, supervisor, onClose, onDone }) {
  const loader = useCallback(() => liquidationRunService.detail(run.id), [run.id]);
  const { data: detail, loading, error } = useResource(loader);
  const { nameOf } = useTaxpayerIndex();
  const [decision, setDecision] = useState("APPROVED");
  const [reason, setReason] = useState("");
  const [submitError, setSubmitError] = useState(null);
  const [submitting, setSubmitting] = useState(false);
  const current = detail?.run ?? run;

  const action = async () => {
    if (supervisor && decision === "REJECTED" && !reason.trim()) {
      setSubmitError("Indicá el motivo del rechazo.");
      return;
    }
    setSubmitting(true);
    setSubmitError(null);
    try {
      let result;
      if (supervisor) result = await liquidationRunService.resolve({ id: run.id, status: decision, reason: reason.trim() });
      else if (current.status === "DRAFT") result = await liquidationRunService.submit(run.id);
      else result = await liquidationRunService.execute(run.id);
      onDone(result);
    } catch (caught) {
      setSubmitError(caught.message);
    } finally {
      setSubmitting(false);
    }
  };

  const canAct = supervisor ? current.status === "PENDING_APPROVAL" : ["DRAFT", "APPROVED"].includes(current.status);
  const actionLabel = supervisor
    ? decision === "APPROVED" ? "Aprobar corrida" : "Rechazar corrida"
    : current.status === "DRAFT" ? "Enviar a aprobación" : "Ejecutar corrida";

  return (
    <Modal
      open
      size="xl"
      title={`Corrida masiva #${run.id}`}
      description={`${current.period} · creada ${formatDateTime(current.createdAt)}`}
      onClose={onClose}
      footer={<><Button variant="secondary" onClick={onClose}>{canAct ? "Cancelar" : "Cerrar"}</Button>{canAct && <Button variant={supervisor && decision === "REJECTED" ? "danger" : "primary"} loading={submitting} disabled={loading} onClick={action}>{actionLabel}</Button>}</>}
    >
      {error && <Alert variant="error" title="No pudimos abrir la corrida">{error}</Alert>}
      {submitError && <Alert variant="error" title="No se pudo completar">{submitError}</Alert>}
      {detail && (
        <>
          <FieldGrid columns={4} items={[
            { label: "Estado", value: <StatusBadge status={current.status} /> },
            { label: "Registros", value: current.totalItems },
            { label: "Válidos", value: current.validItems },
            { label: "Con error", value: current.errorItems },
            { label: "Total estimado", value: formatCurrency(current.estimatedTotalAmount), span: 2 },
            { label: "Creada por", value: current.createdBy },
            { label: "Resuelta por", value: current.resolvedBy },
          ]} />
          <DataTable
            columns={[
              { key: "taxpayerId", header: "Contribuyente", render: (row) => nameOf(row.taxpayerId) },
              { key: "taxableBase", header: "Base", align: "right", render: (row) => formatCurrency(row.taxableBase) },
              { key: "previewAmount", header: "Liquidación", align: "right", render: (row) => formatCurrency(row.previewAmount) },
              { key: "status", header: "Estado", render: (row) => <StatusBadge status={row.status} /> },
              { key: "errorMessage", header: "Error", render: (row) => row.errorMessage ?? "—" },
            ]}
            rows={detail.items ?? []}
            rowKey={(row) => row.id}
            emptyTitle="Sin elementos"
          />
          {supervisor && canAct && <FormField label="Decisión" name="decision" type="select" value={decision} onChange={(event) => setDecision(event.target.value)} options={[{ value: "APPROVED", label: "Aprobar" }, { value: "REJECTED", label: "Rechazar" }]} />}
          {supervisor && canAct && <FormField label={decision === "REJECTED" ? "Motivo del rechazo" : "Observación"} name="reason" type="textarea" value={reason} onChange={(event) => setReason(event.target.value)} required={decision === "REJECTED"} />}
        </>
      )}
    </Modal>
  );
}
