import { hasWorkspaceRole } from "../../config/permissions.js";
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
import useAsyncAction from "../../hooks/useAsyncAction.js";
import { taxConfigService } from "../../services/rentasService.js";
import { useAuth } from "../../context/AuthContext.jsx";
import { formatCurrency, formatDate, formatPercentage, labelFor } from "../../lib/format.js";
import { validAmount, validDate } from "../../lib/validation.js";

const CALCULATION_OPTIONS = [
  { value: "PORCENTAJE", label: "Porcentaje sobre la base" },
  { value: "FIJO", label: "Importe fijo" },
  { value: "IMPORTE_EXTERNO", label: "Importe informado por el módulo de origen" },
];

const CONCEPT_STATUS_OPTIONS = [
  { value: "ACTIVE", label: "Activo" },
  { value: "INACTIVE", label: "Inactivo" },
];

/**
 * Configuración de tributos y su aprobación.
 *
 * Una versión nueva requiere aprobación y vigencia. Las liquidaciones ya emitidas conservan la
 * versión con la que se calcularon: un cambio de alícuota vale de acá en adelante.
 */
export default function ConfiguracionTributosPage() {
  const { user } = useAuth();
  const esSupervisor = hasWorkspaceRole(user, "SUPERVISOR");

  const [filters, setFilters] = useState({ type: "", status: "" });
  const [detalle, setDetalle] = useState(null);
  const [proponiendo, setProponiendo] = useState(null);
  const [resolviendo, setResolviendo] = useState(null);
  const [feedback, setFeedback] = useState(null);

  const listLoader = useCallback(() => taxConfigService.list(filters), [filters]);
  const { data: concepts, loading, error, reload } = useResource(listLoader, []);

  const pendingLoader = useCallback(() => taxConfigService.pendingApprovals(), []);
  const { data: pending, reload: reloadPending } = useResource(pendingLoader, []);

  const refrescar = () => {
    reload();
    reloadPending();
  };

  const conceptColumns = [
    {
      key: "code",
      header: "Concepto",
      render: (row) => (
        <div className="flex flex-col">
          <span className="font-medium text-neutral-800">{row.code}</span>
          <span className="text-[12px] text-neutral-400">{row.name}</span>
        </div>
      ),
    },
    { key: "type", header: "Tipo", render: (row) => <StatusBadge status={row.type} /> },
    {
      key: "calculationType",
      header: "Cálculo",
      render: (row) =>
        row.calculationType === "PORCENTAJE"
          ? `${formatPercentage(row.rate)} sobre la base`
          : labelFor(row.calculationType),
    },
    {
      key: "activeVersion",
      header: "Versión vigente",
      align: "right",
      render: (row) =>
        row.activeVersion ? (
          <span className="tabular-nums">v{row.activeVersion.version}</span>
        ) : (
          <span className="text-neutral-300">—</span>
        ),
    },
    {
      key: "enCurso",
      header: "En curso",
      render: (row) =>
        row.pendingVersion ? (
          <StatusBadge status="PENDING_APPROVAL" />
        ) : row.draftVersion ? (
          <StatusBadge status="DRAFT" />
        ) : (
          <span className="text-neutral-300">—</span>
        ),
    },
    { key: "status", header: "Estado", render: (row) => <StatusBadge status={row.status} /> },
    {
      key: "actions",
      header: "",
      align: "right",
      render: (row) => (
        <div className="flex justify-end gap-2">
          <Button size="sm" variant="secondary" onClick={() => setDetalle(row.code)}>
            Versiones
          </Button>
          {!row.pendingVersion && !row.draftVersion && (
            <Button size="sm" variant="primary" onClick={() => setProponiendo(row)}>
              Nueva versión
            </Button>
          )}
        </div>
      ),
    },
  ];

  const pendingColumns = [
    {
      key: "code",
      header: "Concepto",
      render: (row) => (
        <div className="flex flex-col">
          <span className="font-medium text-neutral-800">{row.code}</span>
          <span className="text-[12px] text-neutral-400">{row.name}</span>
        </div>
      ),
    },
    {
      key: "version",
      header: "Versión",
      align: "right",
      render: (row) => (
        <span className="tabular-nums">
          v{row.currentVersion ? `${row.currentVersion.version} → ${row.version}` : row.version}
        </span>
      ),
    },
    { key: "note", header: "Cambio propuesto" },
    { key: "submittedBy", header: "Propuso" },
    {
      key: "actions",
      header: "",
      align: "right",
      render: (row) =>
        esSupervisor ? (
          <Button size="sm" variant="primary" onClick={() => setResolviendo(row)}>
            Evaluar
          </Button>
        ) : (
          <span className="text-[12px] text-neutral-400">Espera al Supervisor</span>
        ),
    },
  ];

  return (
    <ModuleShell
      label="Configuración"
      title="Tributos"
      highlight="y reglas de cálculo"
      description="Parámetros de cada concepto, versionados y con aprobación del Supervisor."
      breadcrumb={[{ id: "tributos", label: "Configuración de tributos" }]}
    >
      {feedback && (
        <Alert variant={feedback.variant} title={feedback.title} onDismiss={() => setFeedback(null)}>
          {feedback.message}
        </Alert>
      )}


      <Alert variant="info" title="Los cambios rigen hacia adelante">
        Una versión aprobada se aplica dentro de su período de vigencia. Las ya
        emitidas conservan el cálculo con el que se hicieron; para
        corregirlas hay que usar un ajuste, no cambiar la configuración.
      </Alert>

      {(pending?.length ?? 0) > 0 && (
        <Card
          title="Esperando aprobación"
          description="Sólo el Supervisor puede aprobar o rechazar una configuración."
        >
          <DataTable
            columns={pendingColumns}
            rows={pending}
            rowKey={(row) => `${row.code}-${row.version}`}
            emptyTitle="Sin pendientes"
          />
        </Card>
      )}

      <Card title="Conceptos" description="Cada concepto conserva todas sus versiones.">
        <FilterBar
          filters={[
            {
              name: "type",
              label: "Tipo",
              options: [
                { value: "TASA", label: "Tasa" },
                { value: "MULTA", label: "Multa" },
                { value: "CARGO", label: "Cargo" },
              ],
            },
            {
              name: "status",
              label: "Estado",
              options: CONCEPT_STATUS_OPTIONS,
            },
          ]}
          values={filters}
          onFilterChange={(name, value) =>
            setFilters((previous) => ({ ...previous, [name]: value }))
          }
        />
        <DataTable error={error} onRetry={reload}
          columns={conceptColumns}
          rows={concepts ?? []}
          rowKey={(row) => row.code}
          loading={loading}
          emptyIconName="Tags"
          emptyTitle="Sin conceptos"
          emptyDescription="Ningún concepto coincide con el filtro."
        />
      </Card>

      {proponiendo && (
        <ProposeVersionModal
          concept={proponiendo}
          requestedBy={user.username}
          onClose={() => setProponiendo(null)}
          onDone={(version, enviada) => {
            setProponiendo(null);
            setFeedback({
              variant: "success",
              title: enviada ? "Versión enviada a aprobación" : "Versión guardada en borrador",
              message: enviada
                ? `La v${version.version} de ${proponiendo.code} espera la aprobación del Supervisor. Hasta entonces sigue rigiendo la anterior.`
                : `La v${version.version} de ${proponiendo.code} quedó en borrador.`,
            });
            refrescar();
          }}
        />
      )}

      {resolviendo && (
        <ResolveVersionModal
          propuesta={resolviendo}
          user={user}
          onClose={() => setResolviendo(null)}
          onDone={(resultado, decision) => {
            setResolviendo(null);
            setFeedback(
              decision === "APPROVED"
                ? {
                    variant: "success",
                    title: "Configuración aprobada",
                    message: `La v${resultado.version} de ${resolviendo.code} fue aprobada. Se aplicará dentro de su vigencia; las liquidaciones ya emitidas conservan su configuración.`,
                  }
                : { variant: "success", title: "Configuración rechazada", message: resultado.reason },
            );
            refrescar();
          }}
        />
      )}

      {detalle && <VersionsModal code={detalle} onClose={() => setDetalle(null)} />}
    </ModuleShell>
  );
}

/** Formulario de configuración: reglas y parámetros de cálculo de una versión nueva. */
function ProposeVersionModal({ concept, requestedBy, onClose, onDone }) {
  const current = concept.activeVersion ?? concept;
  const [form, setForm] = useState({
    calculationType: current.calculationType ?? "PORCENTAJE",
    rate: current.rate ?? "",
    fixedAmount: current.fixedAmount ?? "",
    minimumAmount: current.minimumAmount ?? "",
    maximumAmount: current.maximumAmount ?? "",
    validFrom: "",
    validUntil: "",
    conceptStatus: concept.status,
    note: "",
  });
  const [error, setError] = useState(null);
  const { run, pending: submitting } = useAsyncAction();

  const onChange = (event) => {
    const { name, value } = event.target;
    setForm((previous) => ({ ...previous, [name]: value }));
    setError(null);
  };

  const guardar = async (enviar) => {
    setError(null);
    if (!validDate(form.validFrom) || (form.validUntil && (!validDate(form.validUntil) || form.validUntil < form.validFrom))) {
      setError("Indicá una fecha inicial válida. La vigencia tiene que terminar después de empezar o en la misma fecha; el final es opcional.");
      return;
    }
    const amountField = form.calculationType === "FIJO" ? "fixedAmount" : form.calculationType === "PORCENTAJE" ? "rate" : null;
    if (amountField && !validAmount(form[amountField], { allowZero: true })) {
      setError("Ingresá un importe fijo o una alícuota mayor o igual a cero.");
      return;
    }
    if (["minimumAmount", "maximumAmount"].some((field) => form[field] !== "" && !validAmount(form[field], { allowZero: true }))) {
      setError("Los límites tienen que ser importes mayores o iguales a cero.");
      return;
    }
    if (form.maximumAmount !== "" && Number(form.maximumAmount) < Number(form.minimumAmount || 0)) {
      setError("El máximo no puede ser menor que el mínimo.");
      return;
    }
    try {
      await run(async () => {
        const version = await taxConfigService.proposeVersion({
          code: concept.code,
          ...form,
          requestedBy,
        });
        if (enviar) {
          await taxConfigService.submitForApproval({
            code: concept.code,
            version: version.version,
            requestedBy,
          });
        }
        onDone(version, enviar);
      });
    } catch (caught) {
      setError(caught.message);
    }
  };

  const esPorcentaje = form.calculationType === "PORCENTAJE";

  return (
    <Modal
      open
      title={`Nueva versión de ${concept.code}`}
      description={`${concept.name} · vigente v${concept.activeVersion?.version ?? "—"}`}
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" loading={submitting} onClick={() => guardar(false)}>
            Guardar borrador
          </Button>
          <Button variant="primary" loading={submitting} onClick={() => guardar(true)}>
            Enviar a aprobación
          </Button>
        </>
      }
    >
      {error && (
        <Alert variant="error" title="No se pudo guardar">
          {error}
        </Alert>
      )}

      <form onSubmit={(e) => e.preventDefault()} noValidate className="flex flex-col gap-4">
        <FormField
          label="Forma de cálculo"
          name="calculationType"
          type="select"
          value={form.calculationType}
          onChange={onChange}
          options={CALCULATION_OPTIONS}
          required
        />

        {esPorcentaje && (
          <FormField
            label="Alícuota (%)"
            name="rate"
            type="number"
            value={form.rate}
            onChange={onChange}
            required
          />
        )}
        {form.calculationType === "FIJO" && (
          <FormField label="Importe fijo" name="fixedAmount" type="number" value={form.fixedAmount} onChange={onChange} min={0} step="0.01" required />
        )}

        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <FormField
            label="Mínimo"
            name="minimumAmount"
            type="number"
            value={form.minimumAmount}
            onChange={onChange}
          />
          <FormField
            label="Máximo"
            name="maximumAmount"
            type="number"
            value={form.maximumAmount}
            onChange={onChange}
          />
        </div>

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
          />
        </div>
        <p className="text-[12px] text-neutral-400">Vigente hasta es opcional. Dejá vacío si no tiene fecha final.</p>

        <FormField
          label="Estado del concepto"
          name="conceptStatus"
          type="select"
          value={form.conceptStatus}
          onChange={onChange}
          options={CONCEPT_STATUS_OPTIONS}
        />
        <p className="-mt-3 text-[12px] text-neutral-400">
          Un concepto inactivo deja de usarse para liquidaciones nuevas, pero las deudas
          que lo referencian siguen vigentes y consultables.
        </p>

        <FormField
          label="Descripción del cambio"
          name="note"
          type="textarea"
          placeholder="Por ejemplo: actualización de alícuota al 12%."
          value={form.note}
          onChange={onChange}
          required
        />
      </form>
    </Modal>
  );
}

/** Detalle de la propuesta y decisión del Supervisor. */
function ResolveVersionModal({ propuesta, user, onClose, onDone }) {
  const [decision, setDecision] = useState("APPROVED");
  const [reason, setReason] = useState("");
  const [error, setError] = useState(null);
  const [submitting, setSubmitting] = useState(false);

  const anterior = propuesta.currentVersion;

  const onSubmit = async (event) => {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      const resultado = await taxConfigService.resolveVersion({
        code: propuesta.code,
        version: propuesta.version,
        status: decision,
        resolvedBy: user.username,
        resolverRole: user.role,
        reason,
      });
      onDone(resultado, decision);
    } catch (caught) {
      setError(caught.message);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open
      title={`${propuesta.code} · v${propuesta.version}`}
      description={propuesta.note}
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>
            Cancelar
          </Button>
          <Button
            variant={decision === "REJECTED" ? "accent" : "primary"}
            loading={submitting}
            onClick={onSubmit}
          >
            Confirmar
          </Button>
        </>
      }
    >
      {error && (
        <Alert variant="error" title="No se pudo resolver">
          {error}
        </Alert>
      )}

      <div className="overflow-hidden rounded-lg border border-neutral-200">
        <table className="w-full text-left text-[13px]">
          <thead>
            <tr className="border-b border-neutral-200 bg-neutral-50">
              <th className="px-3 py-2 text-[11px] font-semibold uppercase tracking-[0.08em] text-neutral-400">
                Parámetro
              </th>
              <th className="px-3 py-2 text-[11px] font-semibold uppercase tracking-[0.08em] text-neutral-400">
                Vigente
              </th>
              <th className="px-3 py-2 text-[11px] font-semibold uppercase tracking-[0.08em] text-neutral-400">
                Propuesto
              </th>
            </tr>
          </thead>
          <tbody>
            <Comparacion
              label="Cálculo"
              antes={anterior && labelFor(anterior.calculationType)}
              despues={labelFor(propuesta.calculationType)}
            />
            <Comparacion
              label="Alícuota"
              antes={anterior?.rate != null ? formatPercentage(anterior.rate) : "—"}
              despues={propuesta.rate != null ? formatPercentage(propuesta.rate) : "—"}
            />
            <Comparacion
              label="Importe fijo"
              antes={anterior?.calculationType === "FIJO" ? formatCurrency(anterior.fixedAmount) : "—"}
              despues={propuesta.calculationType === "FIJO" ? formatCurrency(propuesta.fixedAmount) : "—"}
            />
            <Comparacion
              label="Mínimo"
              antes={anterior?.minimumAmount != null ? formatCurrency(anterior.minimumAmount) : "—"}
              despues={
                propuesta.minimumAmount != null ? formatCurrency(propuesta.minimumAmount) : "—"
              }
            />
            <Comparacion
              label="Máximo"
              antes={anterior?.maximumAmount != null ? formatCurrency(anterior.maximumAmount) : "—"}
              despues={
                propuesta.maximumAmount != null ? formatCurrency(propuesta.maximumAmount) : "—"
              }
            />
            <Comparacion
              label="Vigencia"
              antes={anterior ? `${formatDate(anterior.validFrom)} — ${formatDate(anterior.validUntil)}` : "—"}
              despues={`${formatDate(propuesta.validFrom)} — ${formatDate(propuesta.validUntil)}`}
            />
            <Comparacion
              label="Estado del concepto"
              antes={anterior ? labelFor(anterior.conceptStatus ?? "ACTIVE") : "—"}
              despues={labelFor(propuesta.conceptStatus ?? "ACTIVE")}
            />
          </tbody>
        </table>
      </div>

      <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4">
        <FormField
          label="Decisión"
          name="decision"
          type="select"
          value={decision}
          onChange={(event) => setDecision(event.target.value)}
          options={[
            { value: "APPROVED", label: "Aprobar: pasa a regir" },
            { value: "REJECTED", label: "Rechazar" },
          ]}
          required
        />

        {decision === "APPROVED" ? (
          <Alert variant="info" title="Qué pasa al aprobar">
            La v{propuesta.version} queda aprobada para su período de vigencia.
            El historial se conserva y las liquidaciones ya emitidas no cambian.
          </Alert>
        ) : (
          <FormField
            label="Motivo del rechazo"
            name="reason"
            type="textarea"
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            required
          />
        )}
      </form>
    </Modal>
  );
}

/** Historial completo de versiones del concepto. */
function VersionsModal({ code, onClose }) {
  const loader = useCallback(() => taxConfigService.detail(code), [code]);
  const { data: concept, loading } = useResource(loader);

  return (
    <Modal
      open
      title={`Versiones de ${code}`}
      description="Ninguna versión se borra: el historial permite auditar con qué regla se liquidó."
      onClose={onClose}
      footer={
        <Button variant="secondary" onClick={onClose}>
          Cerrar
        </Button>
      }
    >
      {loading || !concept ? (
        <p className="text-[13px] text-neutral-400">Cargando…</p>
      ) : (
        <>
          {concept.openDebtCount > 0 && concept.status === "INACTIVE" && (
            <Alert variant="info" title="Concepto inactivo con deuda viva">
              Tiene {concept.openDebtCount} deudas con saldo. No se usa para liquidaciones
              nuevas, pero esas obligaciones siguen vigentes.
            </Alert>
          )}

          <DataTable
            columns={[
              {
                key: "version",
                header: "Versión",
                render: (row) => <span className="tabular-nums">v{row.version}</span>,
              },
              { key: "date", header: "Desde", render: (row) => formatDate(row.date) },
              {
                key: "calculationType",
                header: "Cálculo",
                render: (row) =>
                  row.calculationType ? (
                    row.calculationType === "PORCENTAJE"
                      ? formatPercentage(row.rate)
                      : labelFor(row.calculationType)
                  ) : (
                    <span className="text-neutral-300">—</span>
                  ),
              },
              { key: "status", header: "Estado", render: (row) => <StatusBadge status={row.status} /> },
              { key: "user", header: "Usuario" },
              { key: "note", header: "Cambio" },
            ]}
            rows={concept.versions}
            rowKey={(row) => row.version}
            emptyTitle="Sin versiones"
          />

          <p className="text-[12px] text-neutral-400">
            {concept.settlementCount} liquidaciones referencian este concepto.
          </p>
        </>
      )}
    </Modal>
  );
}

function Comparacion({ label, antes, despues }) {
  const cambio = antes !== despues;
  return (
    <tr className="border-b border-neutral-100 last:border-0">
      <td className="px-3 py-2 text-neutral-500">{label}</td>
      <td className="px-3 py-2 text-neutral-400">{antes ?? "—"}</td>
      <td className={`px-3 py-2 ${cambio ? "font-semibold text-[#0F2C59]" : "text-neutral-400"}`}>
        {despues ?? "—"}
      </td>
    </tr>
  );
}
