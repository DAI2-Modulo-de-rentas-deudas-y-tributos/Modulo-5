import { useCallback, useState } from "react";
import { Paperclip } from "lucide-react";
import ModuleShell from "../../components/layout/ModuleShell.jsx";
import Card from "../../components/common/Card.jsx";
import DataTable from "../../components/common/DataTable.jsx";
import FilterBar from "../../components/common/FilterBar.jsx";
import StatusBadge from "../../components/common/StatusBadge.jsx";
import Modal from "../../components/common/Modal.jsx";
import Button from "../../components/common/Button.jsx";
import Alert from "../../components/ui/Alert.jsx";
import FormField from "../../components/ui/FormField.jsx";
import FileUpload from "../../components/common/FileUpload.jsx";
import useResource from "../../hooks/useResource.js";
import useTaxpayerIndex from "../../hooks/useTaxpayerIndex.js";
import useTaxConcepts from "../../hooks/useTaxConcepts.js";
import { exemptionService } from "../../services/rentasService.js";
import { useAuth } from "../../context/AuthContext.jsx";
import { formatDate, formatDateTime, formatPercentage, labelFor } from "../../lib/format.js";

/**
 * Exenciones: `exemptionRequested` abre el flujo hacia M8 y la resolución viaja
 * en `updateExemptionStatus` (APPROVED | REJECTED). Son hechos de negocio distintos,
 * por eso se mantienen como dos eventos separados.
 */
export default function ExencionesPage() {
  const [status, setStatus] = useState("");
  const { user } = useAuth();
  const [creating, setCreating] = useState(false);
  const [selected, setSelected] = useState(null);
  const [feedback, setFeedback] = useState(null);
  const [workflow, setWorkflow] = useState(null);

  const loader = useCallback(() => exemptionService.list({ status }), [status]);
  const { data: exemptions, loading, error, reload } = useResource(loader, []);
  const { nameOf, options: taxpayerOptions } = useTaxpayerIndex();
  const { options: conceptOptions } = useTaxConcepts();

  const columns = [
    {
      key: "requestId",
      header: "Solicitud",
      render: (row) => <span className="tabular-nums">#{row.requestId}</span>,
    },
    { key: "citizen", header: "Ciudadano", render: (row) => nameOf(row.citizenId) },
    { key: "conceptCode", header: "Concepto" },
    {
      key: "requestedPercentage",
      header: "Solicitado",
      align: "right",
      render: (row) => formatPercentage(row.requestedPercentage),
    },
    {
      key: "vigencia",
      header: "Vigencia pedida",
      render: (row) => `${formatDate(row.requestedFrom)} — ${formatDate(row.requestedUntil)}`,
    },
    {
      key: "hasSocialBenefit",
      header: "Beneficio M8",
      render: (row) =>
        row.hasSocialBenefit ? (
          <StatusBadge tone="success" label="Activo" />
        ) : (
          <span className="text-neutral-300">—</span>
        ),
    },
    {
      key: "attachments",
      header: "Doc.",
      render: (row) =>
        row.attachments.length > 0 ? (
          <span className="inline-flex items-center gap-1 text-[12px] text-neutral-500">
            <Paperclip className="h-3 w-3" strokeWidth={2} />
            {row.attachments.length}
          </span>
        ) : (
          <span className="text-neutral-300">—</span>
        ),
    },
    { key: "status", header: "Estado", render: (row) => <StatusBadge status={row.status} /> },
    {
      key: "internalStatus",
      header: "Trámite",
      render: (row) =>
        row.status === "REQUESTED" ? (
          <StatusBadge status={row.internalStatus} />
        ) : (
          <span className="text-neutral-300">—</span>
        ),
    },
    {
      key: "actions",
      header: "",
      align: "right",
      render: (row) => {
        if (row.status !== "REQUESTED") {
          return (
            <span className="text-[12px] text-neutral-400">
              {row.resolvedBy ? `Por ${row.resolvedBy}` : "—"}
            </span>
          );
        }
        return (
          <div className="flex justify-end gap-2">
            <Button size="sm" variant="secondary" onClick={() => setWorkflow(row)}>
              Trámite
            </Button>
            {row.internalStatus === "PENDING_RESOLUTION" && (
              <Button size="sm" variant="primary" onClick={() => setSelected(row)}>
                Resolver
              </Button>
            )}
          </div>
        );
      },
    },
  ];

  return (
    <ModuleShell
      label="Resoluciones"
      title="Exenciones"
      highlight="tributarias"
      description="Solicitudes de exención total o parcial, su documentación y la resolución hacia Desarrollo Social."
      breadcrumb={[{ id: "exenciones", label: "Exenciones" }]}
    >
      {feedback && (
        <Alert variant={feedback.variant} title={feedback.title} onDismiss={() => setFeedback(null)}>
          {feedback.message}
        </Alert>
      )}
      {error && <Alert variant="error" title="No pudimos cargar las solicitudes">{error}</Alert>}

      <Card
        title="Solicitudes de exención"
        description="Un beneficio social activo en M8 respalda la solicitud, pero no la aprueba automáticamente."
        actions={
          <Button size="sm" variant="primary" onClick={() => setCreating(true)}>
            Nueva solicitud
          </Button>
        }
      >
        <FilterBar
          filters={[
            {
              name: "status",
              label: "Estado",
              options: [
                { value: "REQUESTED", label: "Pendientes" },
                { value: "APPROVED", label: "Aprobadas" },
                { value: "REJECTED", label: "Rechazadas" },
              ],
            },
          ]}
          values={{ status }}
          onFilterChange={(_, value) => setStatus(value)}
        />

        <DataTable
          columns={columns}
          rows={exemptions ?? []}
          rowKey={(row) => row.requestId}
          loading={loading}
          emptyIconName="ShieldCheck"
          emptyTitle="Sin solicitudes"
          emptyDescription="No hay exenciones que coincidan con el filtro."
        />
      </Card>

      {creating && (
        <NewExemptionModal
          taxpayerOptions={taxpayerOptions}
          conceptOptions={conceptOptions}
          onClose={() => setCreating(false)}
          onDone={(exemption) => {
            setCreating(false);
            setFeedback({
              variant: "success",
              title: "Solicitud registrada",
              message: `Se publicó exemptionRequested para la solicitud #${exemption.requestId}.`,
            });
            reload();
          }}
        />
      )}

      {workflow && (
        <WorkflowModal
          exemption={workflow}
          actor={user.username}
          onClose={() => setWorkflow(null)}
          onDone={(exemption, mensaje) => {
            setWorkflow(null);
            setFeedback({ variant: "success", title: "Trámite actualizado", message: mensaje });
            reload();
          }}
        />
      )}

      {selected && (
        <ResolveExemptionModal
          exemption={selected}
          citizenName={nameOf(selected.citizenId)}
          onClose={() => setSelected(null)}
          onDone={(exemption) => {
            setSelected(null);
            setFeedback(
              exemption.status === "APPROVED"
                ? {
                    variant: "success",
                    title: "Exención aprobada",
                    message: `Exención #${exemption.exemptionId} del ${formatPercentage(exemption.percentage)} vigente hasta ${formatDate(exemption.validUntil)}.`,
                  }
                : {
                    variant: "info",
                    title: "Solicitud rechazada",
                    message: `Se comunicó el rechazo de la solicitud #${exemption.requestId}.`,
                  },
            );
            reload();
          }}
        />
      )}
    </ModuleShell>
  );
}

/** Alta por mesa de entradas → publica exemptionRequested hacia M8. */
function NewExemptionModal({ taxpayerOptions, conceptOptions, onClose, onDone }) {
  const [form, setForm] = useState({
    citizenId: "",
    conceptCode: "",
    reason: "",
    requestedPercentage: "",
    requestedFrom: "",
    requestedUntil: "",
  });
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
    const percentage = Number(form.requestedPercentage);
    if (!form.citizenId) found.citizenId = "Seleccioná el ciudadano.";
    if (!form.conceptCode) found.conceptCode = "Seleccioná el concepto alcanzado.";
    if (!form.reason.trim()) found.reason = "Describí el motivo de la solicitud.";
    if (!(percentage > 0 && percentage <= 100)) {
      found.requestedPercentage = "El porcentaje debe estar entre 1 y 100.";
    }
    if (!form.requestedFrom) found.requestedFrom = "Indicá el inicio de vigencia.";
    if (!form.requestedUntil) found.requestedUntil = "Indicá el fin de vigencia.";
    if (
      form.requestedFrom &&
      form.requestedUntil &&
      form.requestedUntil <= form.requestedFrom
    ) {
      found.requestedUntil = "El fin de vigencia debe ser posterior al inicio.";
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
      onDone(await exemptionService.requestExemption(form));
    } catch (caught) {
      setSubmitError(caught.message);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open
      title="Nueva solicitud de exención"
      description="Se registra a pedido del ciudadano y se informa a Desarrollo Social."
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>
            Cancelar
          </Button>
          <Button variant="primary" loading={submitting} onClick={onSubmit}>
            Registrar solicitud
          </Button>
        </>
      }
    >
      {submitError && <Alert variant="error" title="No se pudo registrar">{submitError}</Alert>}

      <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4">
        <FormField
          label="Ciudadano"
          name="citizenId"
          type="select"
          value={form.citizenId}
          onChange={onChange}
          options={taxpayerOptions}
          error={errors.citizenId}
          required
        />
        <FormField
          label="Concepto"
          name="conceptCode"
          type="select"
          value={form.conceptCode}
          onChange={onChange}
          options={conceptOptions}
          error={errors.conceptCode}
          required
        />
        <FormField
          label="Porcentaje solicitado"
          name="requestedPercentage"
          type="number"
          placeholder="100"
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
        <FormField
          label="Motivo"
          name="reason"
          type="textarea"
          placeholder="Situación socioeconómica"
          value={form.reason}
          onChange={onChange}
          error={errors.reason}
          required
        />
      </form>
    </Modal>
  );
}

/** Resolución → publica updateExemptionStatus (APPROVED | REJECTED). */
/**
 * Pasos internos del trámite. Ninguno viaja a M8: el contrato sólo contempla la
 * resolución final. Pedir documentación, registrarla y enviar a resolución son
 * estados de gestión de Rentas.
 */
function WorkflowModal({ exemption, actor, onClose, onDone }) {
  const [accion, setAccion] = useState(
    exemption.internalStatus === "DOCUMENTATION_REQUIRED" ? "RECEIVE" : "REQUIRE",
  );
  const [note, setNote] = useState("");
  const [files, setFiles] = useState([]);
  const [error, setError] = useState(null);
  const [submitting, setSubmitting] = useState(false);

  const onSubmit = async (event) => {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      if (accion === "RECEIVE") {
        const resultado = await exemptionService.attachDocumentation({
          requestId: exemption.requestId,
          attachments: files,
          actor,
        });
        onDone(resultado, `Se registraron ${files.length} archivo(s) de la solicitud #${exemption.requestId}.`);
        return;
      }
      const internalStatus = accion === "REQUIRE" ? "DOCUMENTATION_REQUIRED" : "PENDING_RESOLUTION";
      const resultado = await exemptionService.advanceWorkflow({
        requestId: exemption.requestId,
        internalStatus,
        note,
        actor,
      });
      onDone(
        resultado,
        internalStatus === "DOCUMENTATION_REQUIRED"
          ? `Se le pidió documentación al ciudadano por la solicitud #${exemption.requestId}.`
          : `La solicitud #${exemption.requestId} quedó lista para resolver.`,
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
      title={`Trámite de la solicitud #${exemption.requestId}`}
      description={`Estado interno: ${labelFor(exemption.internalStatus)}`}
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>
            Cancelar
          </Button>
          <Button variant="primary" loading={submitting} onClick={onSubmit}>
            Confirmar
          </Button>
        </>
      }
    >
      {error && (
        <Alert variant="error" title="No se pudo actualizar">
          {error}
        </Alert>
      )}

      <Alert variant="info" title="Estos pasos no salen de Rentas">
        Desarrollo Social sólo recibe la resolución final, aprobada o rechazada. La
        gestión de documentación es interna del módulo.
      </Alert>

      <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4">
        <FormField
          label="Acción"
          name="accion"
          type="select"
          value={accion}
          onChange={(event) => setAccion(event.target.value)}
          options={[
            { value: "REQUIRE", label: "Solicitar documentación" },
            { value: "RECEIVE", label: "Registrar documentación recibida" },
            { value: "RESOLUTION", label: "Enviar a resolución" },
          ]}
          required
        />

        {accion === "RECEIVE" ? (
          <FileUpload
            files={files}
            onChange={setFiles}
            label="Documentación presentada"
            hint="Lo que el ciudadano entregó por mesa de entradas."
          />
        ) : (
          <FormField
            label={accion === "REQUIRE" ? "Qué documentación falta" : "Observaciones"}
            name="note"
            type="textarea"
            placeholder={
              accion === "REQUIRE"
                ? "Por ejemplo: certificado de ingresos de los últimos tres meses."
                : "Contexto para quien resuelva."
            }
            value={note}
            onChange={(event) => setNote(event.target.value)}
            required={accion === "REQUIRE"}
          />
        )}

        {exemption.attachments.length > 0 && (
          <p className="text-[12px] text-neutral-400">
            Ya tiene {exemption.attachments.length} archivo(s) presentados.
          </p>
        )}
      </form>
    </Modal>
  );
}

function ResolveExemptionModal({ exemption, citizenName, onClose, onDone }) {
  const { user } = useAuth();
  const [form, setForm] = useState({
    status: "APPROVED",
    percentage: String(exemption.requestedPercentage),
    validFrom: exemption.requestedFrom,
    validUntil: exemption.requestedUntil,
    reason: "",
  });
  const [error, setError] = useState(null);
  const [submitting, setSubmitting] = useState(false);

  const onChange = (event) => {
    const { name, value } = event.target;
    setForm((previous) => ({ ...previous, [name]: value }));
  };

  const onSubmit = async (event) => {
    event.preventDefault();
    setError(null);
    if (form.status === "REJECTED" && !form.reason.trim()) {
      setError("El motivo del rechazo viaja en el evento: es obligatorio.");
      return;
    }
    if (form.status === "APPROVED" && !(Number(form.percentage) > 0)) {
      setError("Indicá el porcentaje aprobado.");
      return;
    }
    setSubmitting(true);
    try {
      onDone(
        await exemptionService.resolve({
          requestId: exemption.requestId,
          ...form,
          percentage: Number(form.percentage),
          resolvedBy: user.username,
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
      title={`Solicitud #${exemption.requestId}`}
      description={`${citizenName} · ${exemption.conceptCode} · pedida el ${formatDateTime(exemption.requestedAt)}`}
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>
            Cancelar
          </Button>
          <Button
            variant={form.status === "APPROVED" ? "primary" : "accent"}
            loading={submitting}
            onClick={onSubmit}
          >
            {form.status === "APPROVED" ? "Aprobar exención" : "Rechazar solicitud"}
          </Button>
        </>
      }
    >
      {error && <Alert variant="error" title="No se pudo resolver">{error}</Alert>}

      <div className="rounded-lg border border-neutral-200 bg-neutral-50 p-4 text-[13px] text-neutral-600">
        <p>
          Motivo declarado:{" "}
          <span className="font-medium text-neutral-800">{exemption.reason}</span>
        </p>
        <p className="mt-1">
          Solicitado: {formatPercentage(exemption.requestedPercentage)} entre{" "}
          {formatDate(exemption.requestedFrom)} y {formatDate(exemption.requestedUntil)}
        </p>
      </div>

      {exemption.hasSocialBenefit && (
        <Alert variant="info" title="El ciudadano tiene un beneficio social activo">
          M8 informó un beneficio vigente mediante socialBenefitUpdated. Verificá que el
          porcentaje aprobado no se superponga con el descuento ya aplicado en la liquidación.
        </Alert>
      )}

      <FormField
        label="Resolución"
        name="status"
        type="select"
        value={form.status}
        onChange={onChange}
        required
        options={[
          { value: "APPROVED", label: "Aprobar" },
          { value: "REJECTED", label: "Rechazar" },
        ]}
      />

      {form.status === "APPROVED" ? (
        <>
          <FormField
            label="Porcentaje aprobado"
            name="percentage"
            type="number"
            value={form.percentage}
            onChange={onChange}
            required
          />
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <FormField
              label="Vigente desde"
              name="validFrom"
              type="date"
              value={form.validFrom}
              onChange={onChange}
              required
            />
            <FormField
              label="Vigente hasta"
              name="validUntil"
              type="date"
              value={form.validUntil}
              onChange={onChange}
              required
            />
          </div>
        </>
      ) : (
        <FormField
          label="Motivo del rechazo"
          name="reason"
          type="textarea"
          placeholder="No cumple los requisitos"
          value={form.reason}
          onChange={onChange}
          required
        />
      )}
    </Modal>
  );
}
