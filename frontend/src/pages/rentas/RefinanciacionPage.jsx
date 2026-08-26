import { useCallback, useMemo, useState } from "react";
import ModuleShell from "../../components/layout/ModuleShell.jsx";
import Card from "../../components/common/Card.jsx";
import DataTable from "../../components/common/DataTable.jsx";
import StatusBadge from "../../components/common/StatusBadge.jsx";
import Modal from "../../components/common/Modal.jsx";
import Button from "../../components/common/Button.jsx";
import Alert from "../../components/ui/Alert.jsx";
import FormField from "../../components/ui/FormField.jsx";
import useResource from "../../hooks/useResource.js";
import useTaxpayerIndex from "../../hooks/useTaxpayerIndex.js";
import { refinancingService } from "../../services/rentasService.js";
import { REFINANCING_RULES } from "../../services/mockDb.js";
import { useAuth } from "../../context/AuthContext.jsx";
import { formatCurrency, formatDate, formatPercentage } from "../../lib/format.js";

/**
 * Refinanciación de planes incumplidos.
 *
 * El plan viejo nunca se borra: al aprobarse queda como antecedente en estado
 * `REFINANCED`, enlazado con el que lo reemplaza. La solicitud y su evaluación son
 * internas de M5 — no se publica nada por pedirla.
 */
export default function RefinanciacionPage() {
  const { user } = useAuth();
  const [onlyEligible, setOnlyEligible] = useState(true);
  const [proposing, setProposing] = useState(null);
  const [resolving, setResolving] = useState(null);
  const [feedback, setFeedback] = useState(null);

  const plansLoader = useCallback(
    () => refinancingService.eligiblePlans({ onlyEligible }),
    [onlyEligible],
  );
  const { data: plans, loading, error, reload } = useResource(plansLoader, []);

  const requestsLoader = useCallback(() => refinancingService.list(), []);
  const { data: requests, reload: reloadRequests } = useResource(requestsLoader, []);

  const { nameOf } = useTaxpayerIndex();

  const refrescar = () => {
    reload();
    reloadRequests();
  };

  const planColumns = [
    {
      key: "planId",
      header: "Plan",
      render: (row) => (
        <div className="flex flex-col">
          <span className="font-medium tabular-nums text-neutral-800">#{row.planId}</span>
          <span className="text-[12px] text-neutral-400">{nameOf(row.taxpayerId)}</span>
        </div>
      ),
    },
    {
      key: "lifecycle",
      header: "Situación",
      render: (row) => <StatusBadge status={row.lifecycle} />,
    },
    {
      key: "overdueInstallments",
      header: "Cuotas vencidas",
      align: "right",
      render: (row) => (
        <span className={row.overdueInstallments > 0 ? "font-semibold text-[#D63031]" : ""}>
          {row.overdueInstallments}
        </span>
      ),
    },
    {
      key: "outstandingAmount",
      header: "Saldo vivo",
      align: "right",
      render: (row) => formatCurrency(row.outstandingAmount),
    },
    {
      key: "actions",
      header: "",
      align: "right",
      render: (row) =>
        row.eligible ? (
          <Button size="sm" variant="primary" onClick={() => setProposing(row)}>
            Refinanciar
          </Button>
        ) : (
          <span className="text-[12px] text-neutral-400" title={row.reasons.join(" ")}>
            {row.reasons[0]}
          </span>
        ),
    },
  ];

  const requestColumns = [
    {
      key: "requestId",
      header: "Solicitud",
      render: (row) => <span className="tabular-nums">#{row.requestId}</span>,
    },
    {
      key: "planId",
      header: "Plan original",
      render: (row) => <span className="tabular-nums text-neutral-600">#{row.planId}</span>,
    },
    { key: "taxpayerId", header: "Contribuyente", render: (row) => nameOf(row.taxpayerId) },
    {
      key: "totalAmount",
      header: "Total propuesto",
      align: "right",
      render: (row) => formatCurrency(row.totalAmount),
    },
    { key: "installments", header: "Cuotas", align: "right" },
    { key: "status", header: "Estado", render: (row) => <StatusBadge status={row.status} /> },
    {
      key: "actions",
      header: "",
      align: "right",
      render: (row) => {
        if (row.status === "APPROVED") {
          return (
            <span className="text-[12px] text-neutral-400 tabular-nums">
              Plan #{row.newPlanId}
            </span>
          );
        }
        if (row.status === "REJECTED") {
          return <span className="text-[12px] text-neutral-400">Por {row.resolvedBy}</span>;
        }
        if (row.status === "UNDER_REVIEW" && user.role !== "SUPERVISOR") {
          return <span className="text-[12px] text-neutral-400">Esperando al Supervisor</span>;
        }
        return (
          <Button size="sm" variant="primary" onClick={() => setResolving(row)}>
            Evaluar
          </Button>
        );
      },
    },
  ];

  return (
    <ModuleShell
      label="Resoluciones"
      title="Refinanciación"
      highlight="de planes"
      description="Rearmar un plan incumplido sobre su saldo vivo, conservando el original como antecedente."
      breadcrumb={[{ id: "refinanciacion", label: "Refinanciación" }]}
    >
      {feedback && (
        <Alert variant={feedback.variant} title={feedback.title} onDismiss={() => setFeedback(null)}>
          {feedback.message}
        </Alert>
      )}
      {error && (
        <Alert variant="error" title="No pudimos cargar los planes">
          {error}
        </Alert>
      )}

      <Alert variant="info" title="El plan original no se elimina">
        Al aprobarse, el plan viejo pasa a <strong>Refinanciado</strong> y conserva sus
        cuotas, sus pagos y su resolución como antecedente, enlazado con el nuevo. La
        solicitud es interna: no se publica ningún evento hasta que se resuelve.
      </Alert>

      <Card
        title="Planes"
        description={`Se puede refinanciar con ${REFINANCING_RULES.minimumOverdueInstallments} cuota vencida o más.`}
        actions={
          <Button
            size="sm"
            variant="secondary"
            onClick={() => setOnlyEligible((value) => !value)}
          >
            {onlyEligible ? "Ver todos" : "Sólo refinanciables"}
          </Button>
        }
      >
        <DataTable
          columns={planColumns}
          rows={plans ?? []}
          rowKey={(row) => row.planId}
          loading={loading}
          emptyIconName="CalendarClock"
          emptyTitle="Sin planes refinanciables"
          emptyDescription="Ningún plan acumula cuotas vencidas suficientes."
        />
      </Card>

      <Card title="Solicitudes de refinanciación" description="La resolución rearma el plan.">
        <DataTable
          columns={requestColumns}
          rows={requests ?? []}
          rowKey={(row) => row.requestId}
          emptyIconName="ClipboardList"
          emptyTitle="Sin solicitudes"
          emptyDescription="Todavía no se propuso ninguna refinanciación."
        />
      </Card>

      {proposing && (
        <ProposeModal
          plan={proposing}
          taxpayerName={nameOf(proposing.taxpayerId)}
          requestedBy={user.username}
          onClose={() => setProposing(null)}
          onDone={(solicitud) => {
            setProposing(null);
            setFeedback({
              variant: "success",
              title: "Refinanciación propuesta",
              message: `Solicitud #${solicitud.requestId} por ${formatCurrency(solicitud.totalAmount)} en ${solicitud.installments} cuotas. El plan original sigue vigente hasta que se resuelva.`,
            });
            refrescar();
          }}
        />
      )}

      {resolving && (
        <ResolveModal
          solicitud={resolving}
          taxpayerName={nameOf(resolving.taxpayerId)}
          user={user}
          onClose={() => setResolving(null)}
          onDone={(resultado, accion) => {
            setResolving(null);
            setFeedback(
              accion === "APPROVED"
                ? {
                    variant: "success",
                    title: "Refinanciación aprobada",
                    message: `El plan #${resultado.previousPlan.planId} quedó como antecedente y el #${resultado.newPlan.planId} pasa a ser el vigente.`,
                  }
                : accion === "REJECTED"
                  ? { variant: "success", title: "Solicitud rechazada", message: resultado.reason }
                  : {
                      variant: "success",
                      title: "Solicitud derivada",
                      message: "Queda a resolución del Supervisor. No se publicó ningún evento.",
                    },
            );
            refrescar();
          }}
        />
      )}
    </ModuleShell>
  );
}

/** Propone el nuevo plan sobre el saldo vivo, comparando alternativas de cuotas. */
function ProposeModal({ plan, taxpayerName, requestedBy, onClose, onDone }) {
  const [installments, setInstallments] = useState(REFINANCING_RULES.installmentChoices[0]);
  const [downPayment, setDownPayment] = useState("");
  const [note, setNote] = useState("");
  const [error, setError] = useState(null);
  const [submitting, setSubmitting] = useState(false);

  const anticipo = Number(downPayment) || 0;
  const valido = anticipo >= 0 && anticipo < plan.outstandingAmount;

  const alternativas = useMemo(
    () =>
      valido
        ? REFINANCING_RULES.installmentChoices.map((n) =>
            refinancingService.simulate({
              outstandingAmount: plan.outstandingAmount,
              installments: n,
              downPayment: anticipo,
            }),
          )
        : [],
    [plan.outstandingAmount, anticipo, valido],
  );
  const elegida = alternativas.find((a) => a.installments === installments) ?? null;

  const onSubmit = async (event) => {
    event.preventDefault();
    setError(null);
    if (!valido) {
      setError("El anticipo tiene que ser menor al saldo vivo.");
      return;
    }
    setSubmitting(true);
    try {
      onDone(
        await refinancingService.request({
          planId: plan.planId,
          installments,
          downPayment: anticipo,
          requestedBy,
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
      title={`Refinanciar el plan #${plan.planId}`}
      description={`${taxpayerName} · ${plan.overdueInstallments} cuotas vencidas`}
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>
            Cancelar
          </Button>
          <Button variant="primary" loading={submitting} onClick={onSubmit}>
            Proponer refinanciación
          </Button>
        </>
      }
    >
      {error && (
        <Alert variant="error" title="No se pudo proponer">
          {error}
        </Alert>
      )}

      <div className="flex items-center justify-between rounded-lg bg-[#0F2C59]/[0.04] px-4 py-3">
        <span className="text-[13px] text-neutral-600">Saldo vivo a refinanciar</span>
        <span className="text-[16px] font-bold tabular-nums text-[#0F2C59]">
          {formatCurrency(plan.outstandingAmount)}
        </span>
      </div>
      <p className="-mt-2 text-[12px] text-neutral-400">
        Es lo que queda por pagar. Lo ya abonado no se vuelve a financiar.
      </p>

      <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4">
        <FormField
          label="Anticipo (opcional)"
          name="downPayment"
          type="number"
          placeholder="0"
          value={downPayment}
          onChange={(event) => setDownPayment(event.target.value)}
        />

        <fieldset className="flex flex-col gap-2">
          <legend className="mb-1 text-[13px] font-medium text-neutral-700">
            Alternativas de cuotas
          </legend>
          {alternativas.length === 0 ? (
            <p className="text-[13px] text-neutral-400">Corregí el anticipo para ver las opciones.</p>
          ) : (
            <div className="grid grid-cols-1 gap-2 sm:grid-cols-3">
              {alternativas.map((alt) => {
                const activa = alt.installments === installments;
                return (
                  <button
                    key={alt.installments}
                    type="button"
                    onClick={() => setInstallments(alt.installments)}
                    aria-pressed={activa}
                    className={`flex flex-col gap-1 rounded-lg border p-3 text-left transition-colors ${
                      activa
                        ? "border-[#0F2C59] bg-[#0F2C59]/[0.04]"
                        : "border-neutral-200 bg-white hover:border-neutral-300"
                    }`}
                  >
                    <span className="text-[12px] font-semibold text-neutral-500">
                      {alt.installments} cuotas
                    </span>
                    <span className="text-[16px] font-extrabold tabular-nums leading-tight text-[#0F2C59]">
                      {formatCurrency(alt.installmentAmount)}
                    </span>
                    <span className="text-[11px] text-neutral-400">
                      +{formatPercentage(alt.interestRate * 100)} interés
                    </span>
                  </button>
                );
              })}
            </div>
          )}
        </fieldset>

        {elegida && (
          <div className="rounded-lg border border-neutral-200 bg-neutral-50 p-4 text-[13px]">
            <div className="flex items-center justify-between">
              <span className="text-neutral-500">Total del nuevo plan</span>
              <span className="font-semibold tabular-nums text-[#0F2C59]">
                {formatCurrency(elegida.totalAmount)}
              </span>
            </div>
          </div>
        )}

        <FormField
          label="Observaciones"
          name="note"
          type="textarea"
          placeholder="Contexto para quien evalúe la solicitud."
          value={note}
          onChange={(event) => setNote(event.target.value)}
        />
      </form>
    </Modal>
  );
}

/** Aprueba, rechaza o deriva. La refinanciación se hace efectiva sólo al aprobar. */
function ResolveModal({ solicitud, taxpayerName, user, onClose, onDone }) {
  const [decision, setDecision] = useState("APPROVED");
  const [reason, setReason] = useState("");
  const [error, setError] = useState(null);
  const [submitting, setSubmitting] = useState(false);

  const derivada = solicitud.status === "UNDER_REVIEW";
  const puedeDerivar = !derivada && user.role !== "SUPERVISOR";

  const onSubmit = async (event) => {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      if (decision === "ESCALATE") {
        if (!reason.trim()) {
          setError("Indicá por qué la derivás: el Supervisor necesita el contexto.");
          setSubmitting(false);
          return;
        }
        onDone(
          await refinancingService.escalate({
            requestId: solicitud.requestId,
            escalatedBy: user.username,
            note: reason,
          }),
          "ESCALATED",
        );
        return;
      }
      onDone(
        await refinancingService.resolve({
          requestId: solicitud.requestId,
          status: decision,
          resolvedBy: user.username,
          resolverRole: user.role,
          reason,
        }),
        decision,
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
      title={`Evaluar la solicitud #${solicitud.requestId}`}
      description={`${taxpayerName} · plan original #${solicitud.planId}`}
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

      <dl className="grid grid-cols-2 gap-3 text-[13px]">
        <Dato label="Saldo refinanciado" value={formatCurrency(solicitud.outstandingAmount)} />
        <Dato label="Cuotas vencidas" value={solicitud.overdueInstallments} />
        <Dato label="Nuevo total" value={formatCurrency(solicitud.totalAmount)} />
        <Dato label="Cuotas" value={solicitud.installments} />
        <Dato label="Solicitó" value={solicitud.requestedBy} />
        <Dato label="Fecha" value={formatDate(solicitud.requestedAt)} />
      </dl>

      {solicitud.note && (
        <p className="text-[13px] text-neutral-500">Observaciones: {solicitud.note}</p>
      )}

      <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4">
        <FormField
          label="Decisión"
          name="decision"
          type="select"
          value={decision}
          onChange={(event) => setDecision(event.target.value)}
          options={[
            { value: "APPROVED", label: "Aprobar y rearmar el plan" },
            { value: "REJECTED", label: "Rechazar" },
            ...(puedeDerivar ? [{ value: "ESCALATE", label: "Derivar al Supervisor" }] : []),
          ]}
          required
        />

        {decision === "APPROVED" && (
          <Alert variant="info" title="Qué pasa al aprobar">
            El plan #{solicitud.planId} pasa a <strong>Refinanciado</strong> y queda como
            antecedente. Se crea un plan nuevo por {formatCurrency(solicitud.totalAmount)} en{" "}
            {solicitud.installments} cuotas, que pasa a ser el vigente.
          </Alert>
        )}

        {decision !== "APPROVED" && (
          <FormField
            label={decision === "REJECTED" ? "Motivo del rechazo" : "Motivo de la derivación"}
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

function Dato({ label, value }) {
  return (
    <div>
      <dt className="text-[11px] font-semibold uppercase tracking-[0.1em] text-neutral-400">
        {label}
      </dt>
      <dd className="mt-0.5 text-neutral-700">{value}</dd>
    </div>
  );
}
