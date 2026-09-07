import { useCallback, useState } from "react";
import ModuleShell from "../../components/layout/ModuleShell.jsx";
import Card from "../../components/common/Card.jsx";
import DataTable from "../../components/common/DataTable.jsx";
import StatusBadge from "../../components/common/StatusBadge.jsx";
import Modal from "../../components/common/Modal.jsx";
import Button from "../../components/common/Button.jsx";
import Alert from "../../components/ui/Alert.jsx";
import FormField from "../../components/ui/FormField.jsx";
import FileUpload from "../../components/common/FileUpload.jsx";
import useResource from "../../hooks/useResource.js";
import useTaxConcepts from "../../hooks/useTaxConcepts.js";
import { portalService } from "../../services/rentasService.js";
import { useAuth } from "../../context/AuthContext.jsx";
import { formatDate, formatPercentage } from "../../lib/format.js";

/**
 * Exenciones del contribuyente. El ciudadano solicita; la resolución la toma Rentas
 * y viaja a Desarrollo Social en `updateExemptionStatus`.
 */
export default function ExencionesPortalPage() {
  const { user } = useAuth();
  const [requesting, setRequesting] = useState(false);
  const [selected, setSelected] = useState(null);
  const [feedback, setFeedback] = useState(null);

  const loader = useCallback(
    () => portalService.exemptions({ taxpayerId: user.taxpayerId }),
    [user.taxpayerId],
  );
  const { data: exemptions, loading, error, reload } = useResource(loader, []);

  const columns = [
    {
      key: "requestId",
      header: "Solicitud",
      render: (row) => <span className="tabular-nums">#{row.requestId}</span>,
    },
    { key: "conceptName", header: "Concepto" },
    { key: "requestedAt", header: "Solicitada", render: (row) => formatDate(row.requestedAt) },
    {
      key: "requestedPercentage",
      header: "Pedido",
      align: "right",
      render: (row) => formatPercentage(row.requestedPercentage),
    },
    {
      key: "percentage",
      header: "Otorgado",
      align: "right",
      render: (row) =>
        row.percentage === undefined || row.percentage === null ? (
          <span className="text-neutral-300">—</span>
        ) : (
          formatPercentage(row.percentage)
        ),
    },
    { key: "status", header: "Estado", render: (row) => <StatusBadge status={row.status} /> },
  ];

  return (
    <ModuleShell
      label="Mi cuenta"
      title="Exenciones"
      highlight="tributarias"
      description="Pedí una exención total o parcial y seguí cómo se resuelve."
      breadcrumb={[{ id: "exenciones", label: "Exenciones" }]}
      homePath="/portal"
      homeLabel="Inicio"
    >
      {feedback && (
        <Alert variant={feedback.variant} title={feedback.title} onDismiss={() => setFeedback(null)}>
          {feedback.message}
        </Alert>
      )}
      {error && (
        <Alert variant="error" title="No pudimos cargar tus exenciones">
          {error}
        </Alert>
      )}

      <Card
        title="Mis solicitudes"
        description="Hacé clic en una solicitud para ver el detalle y la resolución."
        actions={
          <Button size="sm" variant="primary" onClick={() => setRequesting(true)}>
            Solicitar exención
          </Button>
        }
      >
        <DataTable
          columns={columns}
          rows={exemptions ?? []}
          rowKey={(row) => row.requestId}
          loading={loading}
          emptyIconName="ShieldCheck"
          emptyTitle="Sin solicitudes"
          emptyDescription="Todavía no solicitaste ninguna exención."
          onRowClick={setSelected}
        />
      </Card>

      <Alert variant="info" title="Qué documentación hace falta">
        Si pedís la exención por motivos socioeconómicos, Rentas puede pedirte
        documentación respaldatoria. La presentás en la oficina y queda asociada a tu
        solicitud.
      </Alert>

      {requesting && (
        <RequestExemptionModal
          taxpayerId={user.taxpayerId}
          onClose={() => setRequesting(false)}
          onDone={(exemption) => {
            setRequesting(false);
            setFeedback({
              variant: "success",
              title: "Solicitud enviada",
              message: `Tu solicitud #${exemption.requestId} quedó pendiente de resolución.`,
            });
            reload();
          }}
        />
      )}

      <ExemptionDetailModal exemption={selected} onClose={() => setSelected(null)} />
    </ModuleShell>
  );
}

/** RequestExemptionRequest → publica exemptionRequested hacia M8. */
function RequestExemptionModal({ taxpayerId, onClose, onDone }) {
  const { options: conceptOptions, loading: loadingConcepts, error: conceptError } = useTaxConcepts();
  const [form, setForm] = useState({
    conceptCode: "",
    reason: "",
    requestedPercentage: "100",
    requestedFrom: "",
    requestedUntil: "",
  });
  const [attachments, setAttachments] = useState([]);
  const [errors, setErrors] = useState({});
  const [submitError, setSubmitError] = useState(null);
  const [submitting, setSubmitting] = useState(false);

  const onChange = (event) => {
    const { name, value } = event.target;
    setForm((previous) => ({ ...previous, [name]: value }));
    setErrors((previous) => ({ ...previous, [name]: undefined }));
  };

  const validate = () => {
    const found = {};
    if (!form.conceptCode) found.conceptCode = "Elegí el concepto que querés eximir.";
    if (!form.reason.trim()) found.reason = "Contanos por qué solicitás la exención.";
    const pct = Number(form.requestedPercentage);
    if (!(pct > 0 && pct <= 100)) found.requestedPercentage = "Ingresá un porcentaje entre 1 y 100.";
    if (!form.requestedFrom) found.requestedFrom = "Indicá desde cuándo la necesitás.";
    if (!form.requestedUntil) found.requestedUntil = "Indicá hasta cuándo la necesitás.";
    if (form.requestedFrom && form.requestedUntil && form.requestedUntil <= form.requestedFrom) {
      found.requestedUntil = "El fin de la vigencia tiene que ser posterior al inicio.";
    }
    setErrors(found);
    return Object.keys(found).length === 0;
  };

  const onSubmit = async (event) => {
    event.preventDefault();
    setSubmitError(null);
    if (!validate()) return;
    setSubmitting(true);
    try {
      onDone(await portalService.requestExemption({ taxpayerId, ...form, attachments }));
    } catch (caught) {
      setSubmitError(caught.message);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open
      title="Solicitar exención"
      description="Contanos qué concepto querés eximir y por qué motivo."
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>
            Cancelar
          </Button>
          <Button variant="primary" loading={submitting} onClick={onSubmit}>
            Enviar solicitud
          </Button>
        </>
      }
    >
      {submitError && (
        <Alert variant="error" title="No se pudo enviar">
          {submitError}
        </Alert>
      )}

      <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4">
        <FormField
          label="Concepto"
          name="conceptCode"
          type="select"
          value={form.conceptCode}
          onChange={onChange}
          options={conceptOptions}
          disabled={loadingConcepts}
          error={errors.conceptCode}
          required
        />
        {conceptError && <Alert variant="error" title="No se pudieron cargar los conceptos">{conceptError}</Alert>}
        <FormField
          label="Motivo"
          name="reason"
          type="textarea"
          placeholder="Por ejemplo: situación socioeconómica, jubilado con haber mínimo…"
          value={form.reason}
          onChange={onChange}
          error={errors.reason}
          required
        />
        <FormField
          label="Porcentaje solicitado"
          name="requestedPercentage"
          type="number"
          value={form.requestedPercentage}
          onChange={onChange}
          error={errors.requestedPercentage}
          required
        />
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <FormField
            label="Vigente desde"
            name="requestedFrom"
            type="date"
            value={form.requestedFrom}
            onChange={onChange}
            error={errors.requestedFrom}
            required
          />
          <FormField
            label="Vigente hasta"
            name="requestedUntil"
            type="date"
            value={form.requestedUntil}
            onChange={onChange}
            error={errors.requestedUntil}
            required
          />
        </div>

        <FileUpload
          files={attachments}
          onChange={setAttachments}
          hint="Certificados, constancias o comprobantes que respalden el pedido. PDF, JPG o PNG, hasta 5 MB."
        />

        <Alert variant="info" title="Podés recibir menos de lo que pedís">
          Rentas puede otorgar un porcentaje menor al solicitado según tu situación. La
          resolución queda registrada en esta misma pantalla.
        </Alert>
      </form>
    </Modal>
  );
}

/** Detalle de la solicitud y su resolución. */
function ExemptionDetailModal({ exemption, onClose }) {
  if (!exemption) return null;

  const reducida =
    exemption.status === "APPROVED" && exemption.percentage < exemption.requestedPercentage;

  return (
    <Modal
      open
      title={`Solicitud #${exemption.requestId}`}
      description={`${exemption.conceptName} · solicitada el ${formatDate(exemption.requestedAt)}`}
      onClose={onClose}
      footer={
        <Button variant="secondary" onClick={onClose}>
          Cerrar
        </Button>
      }
    >
      <dl className="grid grid-cols-2 gap-3">
        <Field label="Estado" value={<StatusBadge status={exemption.status} />} />
        <Field label="Expediente" value={exemption.fileNumber} />
        <Field
          label="Porcentaje solicitado"
          value={formatPercentage(exemption.requestedPercentage)}
        />
        <Field
          label="Porcentaje otorgado"
          value={
            exemption.percentage === undefined || exemption.percentage === null
              ? "—"
              : formatPercentage(exemption.percentage)
          }
        />
        <Field
          label="Vigencia solicitada"
          value={`${formatDate(exemption.requestedFrom)} — ${formatDate(exemption.requestedUntil)}`}
        />
        <Field
          label="Vigencia otorgada"
          value={
            exemption.validFrom
              ? `${formatDate(exemption.validFrom)} — ${formatDate(exemption.validUntil)}`
              : "—"
          }
        />
        <Field label="Motivo" value={exemption.reason} />
        <Field
          label="Documentación presentada"
          value={
            exemption.attachments?.length > 0
              ? `${exemption.attachments.length} archivo(s)`
              : "Sin adjuntos"
          }
        />
      </dl>

      {reducida && (
        <Alert variant="info" title="Se otorgó por un porcentaje menor">
          Pediste {formatPercentage(exemption.requestedPercentage)} y se aprobó{" "}
          {formatPercentage(exemption.percentage)}.
          {exemption.observations ? ` ${exemption.observations}` : ""}
        </Alert>
      )}

      {exemption.status === "REJECTED" && (
        <Alert variant="error" title="Solicitud rechazada">
          {exemption.reason_rejected}
        </Alert>
      )}

      {exemption.status === "REQUESTED" && (
        <Alert variant="info" title="Pendiente de resolución">
          Rentas todavía está evaluando tu solicitud. Mientras tanto, el tributo se
          liquida sin la exención.
        </Alert>
      )}
    </Modal>
  );
}

function Field({ label, value }) {
  return (
    <div>
      <dt className="text-[11px] font-semibold uppercase tracking-[0.1em] text-neutral-400">
        {label}
      </dt>
      <dd className="mt-0.5 text-[13px] text-neutral-700 break-words">{value}</dd>
    </div>
  );
}
