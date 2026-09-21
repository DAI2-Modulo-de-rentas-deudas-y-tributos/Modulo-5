import { useCallback, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
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
import { portalService } from "../../services/rentasService.js";
import { useAuth } from "../../context/AuthContext.jsx";
import { formatCurrency, formatDateTime } from "../../lib/format.js";

const OPTIONS = [3, 6, 9, 12, 18, 24].map((value) => ({ value: String(value), label: `${value} cuotas` }));

export default function RefinanciacionPortalPage() {
  const { requestId } = useParams();
  if (requestId) return <RefinancingStatus requestId={requestId} />;
  return <RefinancingList />;
}

function RefinancingList() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const [selected, setSelected] = useState(null);
  const loader = useCallback(() => portalService.activePaymentPlans({ taxpayerId: user.taxpayerId }), [user.taxpayerId]);
  const { data: plans, loading, error } = useResource(loader, []);
  const eligible = (plans ?? []).filter((plan) => plan.status === "ACTIVE" || plan.lifecycle === "CURRENT");

  return (
    <ModuleShell
      label="Mi cuenta"
      title="Refinanciación"
      highlight="de planes"
      description="Solicitá nuevas condiciones sobre el saldo pendiente de un plan activo."
      breadcrumb={[{ id: "refinanciacion", label: "Refinanciación" }]}
      homePath="/portal"
      homeLabel="Inicio"
    >
      {error && <Alert variant="error" title="No pudimos cargar tus planes">{error}</Alert>}
      <Alert variant="info" title="El plan actual sigue vigente">
        Enviar la solicitud no reemplaza el plan. La refinanciación recién entra en vigencia cuando Rentas la aprueba.
      </Alert>
      <Card title="Planes elegibles">
        <DataTable
          columns={[
            { key: "id", header: "Plan", render: (row) => `#${row.id}` },
            { key: "installments", header: "Cuotas", align: "right" },
            { key: "totalAmount", header: "Total original", align: "right", render: (row) => formatCurrency(row.totalAmount) },
            { key: "paidAmount", header: "Pagado", align: "right", render: (row) => formatCurrency(row.paidAmount) },
            { key: "outstandingAmount", header: "Saldo", align: "right", render: (row) => formatCurrency(row.outstandingAmount) },
            { key: "lifecycle", header: "Estado", render: (row) => <StatusBadge status={row.lifecycle} /> },
            { key: "actions", header: "", align: "right", render: (row) => <Button size="sm" variant="primary" onClick={() => setSelected(row)}>Solicitar</Button> },
          ]}
          rows={eligible}
          rowKey={(row) => row.id}
          loading={loading}
          emptyIconName="RefreshCw"
          emptyTitle="Sin planes elegibles"
          emptyDescription="No tenés planes activos que admitan una solicitud de refinanciación."
        />
      </Card>
      {selected && <RefinancingModal plan={selected} onClose={() => setSelected(null)} onDone={(result) => navigate(`/portal/refinanciacion/${result.id}`)} />}
    </ModuleShell>
  );
}

function RefinancingModal({ plan, onClose, onDone }) {
  const [installments, setInstallments] = useState("6");
  const [preview, setPreview] = useState(null);
  const [error, setError] = useState(null);
  const [working, setWorking] = useState(false);

  const simulate = async () => {
    setWorking(true);
    setError(null);
    try {
      setPreview(await portalService.simulateRefinancing({ planId: plan.id, installments: Number(installments) }));
    } catch (caught) {
      setError(caught.message);
    } finally {
      setWorking(false);
    }
  };

  const submit = async () => {
    setWorking(true);
    setError(null);
    try {
      onDone(await portalService.requestRefinancing({ planId: plan.id, installments: Number(installments) }));
    } catch (caught) {
      setError(caught.message);
    } finally {
      setWorking(false);
    }
  };

  return (
    <Modal
      open
      size="lg"
      title={`Refinanciar plan #${plan.id}`}
      description={`Saldo pendiente ${formatCurrency(plan.outstandingAmount)}`}
      onClose={onClose}
      footer={<><Button variant="secondary" onClick={onClose}>Cancelar</Button>{preview ? <Button variant="accent" loading={working} onClick={submit}>Confirmar solicitud</Button> : <Button variant="primary" loading={working} onClick={simulate}>Simular</Button>}</>}
    >
      {error && <Alert variant="error" title="No se pudo completar">{error}</Alert>}
      <FormField label="Nuevas cuotas" name="installments" type="select" value={installments} onChange={(event) => { setInstallments(event.target.value); setPreview(null); }} options={OPTIONS} />
      {preview && (
        <div className="flex flex-col gap-4">
          <Alert variant="info" title="Resumen antes de confirmar">La propuesta se calcula sobre el saldo pendiente, sin volver a financiar lo que ya pagaste.</Alert>
          <FieldGrid columns={3} items={[
            { label: "Saldo a refinanciar", value: formatCurrency(preview.outstandingPrincipal ?? preview.principal) },
            { label: "Interés estimado", value: formatCurrency(preview.estimatedInterest ?? preview.interest) },
            { label: "Total estimado", value: formatCurrency(preview.estimatedTotalAmount ?? preview.total) },
            { label: "Cuotas", value: preview.installments },
            { label: "Valor de cuota", value: formatCurrency(preview.regularInstallmentAmount) },
            { label: "Excepcional", value: preview.exceptional ? "Sí" : "No" },
          ]} />
        </div>
      )}
    </Modal>
  );
}

function RefinancingStatus({ requestId }) {
  const navigate = useNavigate();
  const loader = useCallback(() => portalService.refinancingRequest(requestId), [requestId]);
  const { data: requestData, loading, error } = useResource(loader);
  return (
    <ModuleShell
      label="Mi cuenta"
      title={`Solicitud de refinanciación #${requestId}`}
      highlight="en seguimiento"
      description="Estado informado directamente por Rentas."
      breadcrumb={[{ id: "refinanciacion", label: "Refinanciación", path: "/portal/refinanciacion" }, { id: "estado", label: `#${requestId}` }]}
      homePath="/portal"
      homeLabel="Inicio"
    >
      {error && <Alert variant="error" title="No pudimos abrir la solicitud">{error}</Alert>}
      {loading && <p className="text-[13px] text-neutral-400">Consultando estado…</p>}
      {requestData && (
        <Card title="Estado de la solicitud">
          <div className="flex flex-col gap-5 px-5 py-5">
            <Alert variant={requestData.status === "REJECTED" ? "error" : requestData.status === "GRANTED" ? "success" : "info"} title={requestData.status === "REQUESTED" ? "Pendiente de evaluación" : "Solicitud actualizada"}>
              El plan actual continúa vigente hasta que esta solicitud sea aprobada.
            </Alert>
            <FieldGrid columns={3} items={[
              { label: "Estado", value: <StatusBadge status={requestData.status} /> },
              { label: "Plan original", value: `#${requestData.planId}` },
              { label: "Cuotas solicitadas", value: requestData.installments },
              { label: "Saldo al solicitar", value: formatCurrency(requestData.outstandingAmount) },
              { label: "Total estimado", value: formatCurrency(requestData.totalAmount) },
              { label: "Fecha", value: formatDateTime(requestData.requestedAt) },
              { label: "Nuevo plan", value: requestData.newPaymentPlanId ? `#${requestData.newPaymentPlanId}` : "Pendiente" },
              { label: "Resuelto por", value: requestData.resolvedBy ?? "Pendiente" },
            ]} />
            <Button variant="secondary" onClick={() => navigate("/portal/refinanciacion")}>Volver a mis planes</Button>
          </div>
        </Card>
      )}
    </ModuleShell>
  );
}
