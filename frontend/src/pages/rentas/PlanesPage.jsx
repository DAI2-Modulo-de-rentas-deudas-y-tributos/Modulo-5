import { useCallback, useMemo, useState } from "react";
import ModuleShell from "../../components/layout/ModuleShell.jsx";
import Card from "../../components/common/Card.jsx";
import DataTable from "../../components/common/DataTable.jsx";
import FilterBar from "../../components/common/FilterBar.jsx";
import StatusBadge from "../../components/common/StatusBadge.jsx";
import Modal from "../../components/common/Modal.jsx";
import Button from "../../components/common/Button.jsx";
import Alert from "../../components/ui/Alert.jsx";
import FormField from "../../components/ui/FormField.jsx";
import StepIndicatorGeneric from "../../components/ui/StepIndicatorGeneric.jsx";
import useResource from "../../hooks/useResource.js";
import useTaxpayerIndex from "../../hooks/useTaxpayerIndex.js";
import { paymentPlanService } from "../../services/rentasService.js";
import { useAuth } from "../../context/AuthContext.jsx";
import { formatCurrency, formatDateTime } from "../../lib/format.js";

const INSTALLMENT_OPTIONS = [3, 6, 9, 12, 18].map((n) => ({
  value: String(n),
  label: `${n} cuotas`,
}));

const STEPS = [
  { label: "Solicitud" },
  { label: "Simulación" },
  { label: "Resolución" },
];

/**
 * Planes de pago: la solicitud llega con las deudas a consolidar, se simulan las
 * cuotas y la resolución publica updatePaymentPlanStatus (GRANTED | REJECTED).
 */
export default function PlanesPage() {
  const { user } = useAuth();
  const [filters, setFilters] = useState({ status: "", internalStatus: "" });
  const [selected, setSelected] = useState(null);
  const [escalating, setEscalating] = useState(null);
  const [feedback, setFeedback] = useState(null);

  const loader = useCallback(() => paymentPlanService.list(filters), [filters]);
  const { data: plans, loading, error, reload } = useResource(loader, []);
  const { nameOf } = useTaxpayerIndex();

  const columns = [
    {
      key: "requestId",
      header: "Solicitud",
      render: (row) => <span className="tabular-nums">#{row.requestId}</span>,
    },
    { key: "taxpayer", header: "Contribuyente", render: (row) => nameOf(row.taxpayerId) },
    {
      key: "debtIds",
      header: "Deudas",
      render: (row) => (
        <span className="text-[12px] text-neutral-500 tabular-nums">
          {row.debtIds.map((id) => `#${id}`).join(", ")}
        </span>
      ),
    },
    {
      key: "totalDebt",
      header: "Deuda total",
      align: "right",
      render: (row) => formatCurrency(row.totalDebt),
    },
    {
      key: "installments",
      header: "Cuotas",
      align: "right",
      render: (row) => row.installments,
    },
    {
      key: "requestedAt",
      header: "Solicitado",
      render: (row) => formatDateTime(row.requestedAt),
    },
    { key: "status", header: "Estado", render: (row) => <StatusBadge status={row.status} /> },
    {
      key: "internalStatus",
      header: "Gestión interna",
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

        const derivada = row.internalStatus === "PENDING_SUPERVISOR";
        // Derivada sólo la resuelve el Supervisor: el resto ve por qué no puede.
        if (derivada && user.role !== "SUPERVISOR") {
          return (
            <span className="text-[12px] text-neutral-400">
              Esperando al Supervisor
            </span>
          );
        }

        return (
          <div className="flex justify-end gap-2">
            {!derivada && user.role !== "SUPERVISOR" && (
              <Button size="sm" variant="secondary" onClick={() => setEscalating(row)}>
                Derivar
              </Button>
            )}
            <Button size="sm" variant="primary" onClick={() => setSelected(row)}>
              Resolver
            </Button>
          </div>
        );
      },
    },
  ];

  return (
    <ModuleShell
      label="Resoluciones"
      title="Planes de pago"
      highlight="solicitados"
      description="Consolidación de deudas en cuotas. La resolución se comunica con updatePaymentPlanStatus."
      breadcrumb={[{ id: "planes", label: "Planes de pago" }]}
    >
      {feedback && (
        <Alert variant={feedback.variant} title={feedback.title} onDismiss={() => setFeedback(null)}>
          {feedback.message}
        </Alert>
      )}
      {error && <Alert variant="error" title="No pudimos cargar las solicitudes">{error}</Alert>}

      <Alert variant="info" title="La derivación no sale de Rentas">
        Derivar al Supervisor es un estado interno del módulo. Hacia los demás módulos
        sólo viaja la resolución final —otorgado o rechazado— en{" "}
        <code className="font-semibold">updatePaymentPlanStatus</code>.
      </Alert>

      <Card
        title="Solicitudes"
        description="Otorgar genera el plan y sus cuotas; rechazar exige un motivo."
      >
        <FilterBar
          filters={[
            {
              name: "status",
              label: "Estado",
              options: [
                { value: "REQUESTED", label: "Pendientes" },
                { value: "GRANTED", label: "Otorgados" },
                { value: "REJECTED", label: "Rechazados" },
              ],
            },
            {
              name: "internalStatus",
              label: "Gestión interna",
              options: [
                { value: "PENDING_REVIEW", label: "En revisión" },
                { value: "PENDING_SUPERVISOR", label: "Derivado a Supervisor" },
              ],
            },
          ]}
          values={filters}
          onFilterChange={(name, value) =>
            setFilters((previous) => ({ ...previous, [name]: value }))
          }
        />

        <DataTable
          columns={columns}
          rows={plans ?? []}
          rowKey={(row) => row.requestId}
          loading={loading}
          emptyIconName="CalendarClock"
          emptyTitle="Sin solicitudes"
          emptyDescription="No hay planes de pago que coincidan con el filtro."
        />
      </Card>

      {escalating && (
        <EscalatePlanModal
          plan={escalating}
          taxpayerName={nameOf(escalating.taxpayerId)}
          escalatedBy={user.username}
          onClose={() => setEscalating(null)}
          onDone={(plan) => {
            setEscalating(null);
            setFeedback({
              variant: "success",
              title: "Solicitud derivada",
              message: `La solicitud #${plan.requestId} quedó a resolución del Supervisor. No se publicó ningún evento: la derivación es interna.`,
            });
            reload();
          }}
        />
      )}

      {selected && (
        <ResolvePlanModal
          plan={selected}
          taxpayerName={nameOf(selected.taxpayerId)}
          onClose={() => setSelected(null)}
          onDone={(plan) => {
            setSelected(null);
            setFeedback(
              plan.status === "GRANTED"
                ? {
                    variant: "success",
                    title: "Plan otorgado",
                    message: `Plan #${plan.planId} en ${plan.installments} cuotas por ${formatCurrency(plan.totalAmount)}.`,
                  }
                : {
                    variant: "info",
                    title: "Solicitud rechazada",
                    message: `Se comunicó el rechazo de la solicitud #${plan.requestId}.`,
                  },
            );
            reload();
          }}
        />
      )}
    </ModuleShell>
  );
}

/** Simulación de cuotas + resolución en un mismo flujo de tres pasos. */
/**
 * EscalatePaymentPlanRequest: el analista que no puede decidir manda la solicitud
 * al Supervisor con el motivo. No publica nada: el estado es interno de M5.
 */
function EscalatePlanModal({ plan, taxpayerName, escalatedBy, onClose, onDone }) {
  const [note, setNote] = useState("");
  const [error, setError] = useState(null);
  const [submitting, setSubmitting] = useState(false);

  const onSubmit = async (event) => {
    event.preventDefault();
    setError(null);
    if (!note.trim()) {
      setError("Indicá por qué la derivás: el Supervisor necesita el contexto.");
      return;
    }
    setSubmitting(true);
    try {
      onDone(
        await paymentPlanService.escalate({
          requestId: plan.requestId,
          escalatedBy,
          note,
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
      title={`Derivar la solicitud #${plan.requestId}`}
      description={`${taxpayerName} · ${formatCurrency(plan.totalDebt)} en ${plan.installments} cuotas`}
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>
            Cancelar
          </Button>
          <Button variant="primary" loading={submitting} onClick={onSubmit}>
            Derivar al Supervisor
          </Button>
        </>
      }
    >
      {error && (
        <Alert variant="error" title="No se pudo derivar">
          {error}
        </Alert>
      )}

      <Alert variant="info" title="La solicitud sigue pendiente para el exterior">
        Derivar no resuelve nada ni publica ningún evento. Para los demás módulos la
        solicitud sigue en el mismo estado hasta que el Supervisor la otorgue o la rechace.
      </Alert>

      <form onSubmit={onSubmit} noValidate>
        <FormField
          label="Motivo de la derivación"
          name="note"
          type="textarea"
          placeholder="Por ejemplo: el contribuyente pide más cuotas de las habituales."
          value={note}
          onChange={(event) => setNote(event.target.value)}
          required
        />
      </form>
    </Modal>
  );
}

function ResolvePlanModal({ plan, taxpayerName, onClose, onDone }) {
  const { user } = useAuth();
  const [installments, setInstallments] = useState(String(plan.installments));
  const [decision, setDecision] = useState("GRANTED");
  const [reason, setReason] = useState("");
  const [error, setError] = useState(null);
  const [submitting, setSubmitting] = useState(false);

  const simulation = useMemo(
    () => paymentPlanService.simulate({ totalDebt: plan.totalDebt, installments }),
    [plan.totalDebt, installments],
  );

  const currentStep = decision === "REJECTED" ? 2 : installments ? 1 : 0;

  const onSubmit = async (event) => {
    event.preventDefault();
    setError(null);
    if (decision === "REJECTED" && !reason.trim()) {
      setError("El motivo del rechazo viaja en el evento: es obligatorio.");
      return;
    }
    setSubmitting(true);
    try {
      onDone(
        await paymentPlanService.resolve({
          requestId: plan.requestId,
          status: decision,
          installments: Number(installments),
          reason,
          resolvedBy: user.username,
          // El servicio rechaza que un analista resuelva una solicitud ya derivada.
          resolverRole: user.role,
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
      title={`Solicitud #${plan.requestId}`}
      description={`${taxpayerName} · ${formatCurrency(plan.totalDebt)} en ${plan.debtIds.length} deudas`}
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>
            Cancelar
          </Button>
          <Button
            variant={decision === "GRANTED" ? "primary" : "accent"}
            loading={submitting}
            onClick={onSubmit}
          >
            {decision === "GRANTED" ? "Otorgar plan" : "Rechazar solicitud"}
          </Button>
        </>
      }
    >
      <StepIndicatorGeneric steps={STEPS} currentStep={currentStep} />

      {error && <Alert variant="error" title="No se pudo resolver">{error}</Alert>}

      <FormField
        label="Resolución"
        name="decision"
        type="select"
        value={decision}
        onChange={(event) => setDecision(event.target.value)}
        required
        options={[
          { value: "GRANTED", label: "Otorgar" },
          { value: "REJECTED", label: "Rechazar" },
        ]}
      />

      {decision === "GRANTED" ? (
        <>
          <FormField
            label="Cantidad de cuotas"
            name="installments"
            type="select"
            value={installments}
            onChange={(event) => setInstallments(event.target.value)}
            options={INSTALLMENT_OPTIONS}
            required
          />

          <div className="rounded-lg border border-neutral-200 bg-neutral-50 p-4">
            <p className="text-[11px] font-semibold uppercase tracking-[0.12em] text-neutral-400">
              Simulación
            </p>
            <div className="mt-3 grid grid-cols-3 gap-3 text-center">
              <div>
                <p className="text-[12px] text-neutral-400">Cuotas</p>
                <p className="text-[18px] font-bold tabular-nums text-[#0F2C59]">
                  {simulation.installments}
                </p>
              </div>
              <div>
                <p className="text-[12px] text-neutral-400">Valor cuota</p>
                <p className="text-[18px] font-bold tabular-nums text-[#0F2C59]">
                  {formatCurrency(simulation.installmentAmount)}
                </p>
              </div>
              <div>
                <p className="text-[12px] text-neutral-400">Total con interés</p>
                <p className="text-[18px] font-bold tabular-nums text-[#D63031]">
                  {formatCurrency(simulation.totalAmount)}
                </p>
              </div>
            </div>
            <p className="mt-3 text-[12px] text-neutral-400">
              Interés aplicado: {(simulation.interestRate * 100).toFixed(0)}% sobre la deuda
              consolidada.
            </p>
          </div>
        </>
      ) : (
        <FormField
          label="Motivo del rechazo"
          name="reason"
          type="textarea"
          placeholder="La deuda no cumple las condiciones"
          value={reason}
          onChange={(event) => setReason(event.target.value)}
          required
        />
      )}
    </Modal>
  );
}
