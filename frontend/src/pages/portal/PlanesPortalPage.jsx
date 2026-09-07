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
import { portalService } from "../../services/rentasService.js";
import { useAuth } from "../../context/AuthContext.jsx";
import { formatCurrency, formatDate, formatPercentage } from "../../lib/format.js";

/** Las tres alternativas que se comparan lado a lado antes de elegir. */
const INSTALLMENT_CHOICES = [3, 6, 12];

/**
 * Planes de pago del contribuyente: es la vía para conseguir más plazo sobre una
 * deuda. El ciudadano *solicita*; la resolución (otorgar o rechazar) es de Rentas.
 */
export default function PlanesPortalPage() {
  const { user } = useAuth();
  const [requesting, setRequesting] = useState(false);
  const [selected, setSelected] = useState(null);
  const [feedback, setFeedback] = useState(null);

  const loader = useCallback(
    () => portalService.paymentPlans({ taxpayerId: user.taxpayerId }),
    [user.taxpayerId],
  );
  const { data: plans, loading, error, reload } = useResource(loader, []);

  const columns = [
    {
      key: "requestId",
      header: "Solicitud",
      render: (row) => (
        <div className="flex flex-col">
          <span className="font-medium tabular-nums text-neutral-800">#{row.requestId}</span>
          {row.planId && (
            <span className="text-[12px] tabular-nums text-neutral-400">Plan #{row.planId}</span>
          )}
        </div>
      ),
    },
    { key: "requestedAt", header: "Solicitado", render: (row) => formatDate(row.requestedAt) },
    {
      key: "debts",
      header: "Deudas incluidas",
      render: (row) => row.debts.map((d) => d.conceptName).join(", "),
    },
    { key: "installments", header: "Cuotas", align: "right" },
    {
      key: "totalDebt",
      header: "Deuda incluida",
      align: "right",
      render: (row) => formatCurrency(row.totalDebt),
    },
    {
      key: "downPayment",
      header: "Anticipo",
      align: "right",
      render: (row) =>
        row.downPayment > 0 ? (
          formatCurrency(row.downPayment)
        ) : (
          <span className="text-neutral-300">Sin anticipo</span>
        ),
    },
    {
      key: "status",
      header: "Estado",
      render: (row) => <StatusBadge status={row.lifecycle ?? row.status} />,
    },
  ];

  return (
    <ModuleShell
      label="Mi cuenta"
      title="Planes de pago"
      highlight="en cuotas"
      description="Pedí financiar tu deuda y seguí cómo se resuelve tu solicitud."
      breadcrumb={[{ id: "planes", label: "Planes de pago" }]}
      homePath="/portal"
      homeLabel="Inicio"
    >
      {feedback && (
        <Alert variant={feedback.variant} title={feedback.title} onDismiss={() => setFeedback(null)}>
          {feedback.message}
        </Alert>
      )}
      {error && (
        <Alert variant="error" title="No pudimos cargar tus planes">
          {error}
        </Alert>
      )}

      <Card
        title="Mis solicitudes"
        description="Hacé clic en una solicitud para ver el detalle y las cuotas."
        actions={
          <Button size="sm" variant="primary" onClick={() => setRequesting(true)}>
            Solicitar plan de pago
          </Button>
        }
      >
        <DataTable
          columns={columns}
          rows={plans ?? []}
          rowKey={(row) => row.requestId}
          loading={loading}
          emptyIconName="CalendarClock"
          emptyTitle="Sin solicitudes"
          emptyDescription="Todavía no pediste financiar ninguna deuda."
          onRowClick={setSelected}
        />
      </Card>

      <Alert variant="info" title="Cómo funciona">
        Elegís qué deudas querés financiar y en cuántas cuotas. La solicitud pasa a Rentas,
        que la evalúa y la otorga o la rechaza. Mientras esté pendiente, la deuda sigue
        corriendo con su vencimiento original.
      </Alert>

      {requesting && (
        <RequestPlanModal
          taxpayerId={user.taxpayerId}
          onClose={() => setRequesting(false)}
          onDone={(plan) => {
            setRequesting(false);
            setFeedback({
              variant: "success",
              title: "Solicitud enviada",
              message: `Tu solicitud #${plan.requestId} por ${formatCurrency(plan.totalDebt)} en ${plan.installments} cuotas quedó pendiente de resolución.`,
            });
            reload();
          }}
        />
      )}

      <PlanDetailModal plan={selected} onClose={() => setSelected(null)} />
    </ModuleShell>
  );
}

/**
 * RequestPaymentPlanRequest: elegir deudas, comparar alternativas de cuotas y enviar.
 *
 * Las alternativas se muestran lado a lado en vez de una por vez: el contribuyente
 * decide viendo la cuota de cada opción, no probando de a una. El anticipo es
 * opcional y baja la base financiada, así que abarata el interés y la cuota.
 */
function RequestPlanModal({ taxpayerId, onClose, onDone }) {
  const debtsLoader = useCallback(() => portalService.debts({ taxpayerId }), [taxpayerId]);
  const { data: debts } = useResource(debtsLoader, []);

  const [selectedIds, setSelectedIds] = useState([]);
  const [downPayment, setDownPayment] = useState("");
  const [installments, setInstallments] = useState(6);
  const [errors, setErrors] = useState({});
  const [submitError, setSubmitError] = useState(null);
  const [submitting, setSubmitting] = useState(false);

  const elegibles = (debts ?? []).filter((d) => d.outstandingAmount > 0 && !d.planRequestId);

  const totalDebt = useMemo(
    () =>
      elegibles
        .filter((d) => selectedIds.includes(d.id))
        .reduce((acc, d) => acc + d.outstandingAmount, 0),
    [elegibles, selectedIds],
  );

  const anticipo = Number(downPayment) || 0;
  const anticipoValido = anticipo >= 0 && anticipo < totalDebt;

  const alternativas = useMemo(
    () =>
      totalDebt > 0 && anticipoValido
        ? INSTALLMENT_CHOICES.map((n) =>
            portalService.simulatePaymentPlan({
              totalDebt,
              installments: n,
              downPayment: anticipo,
            }),
          )
        : [],
    [totalDebt, anticipo, anticipoValido],
  );

  const elegida = alternativas.find((a) => a.installments === installments) ?? null;

  const toggle = (id) =>
    setSelectedIds((prev) => (prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]));

  const validate = () => {
    const found = {};
    if (selectedIds.length === 0) found.debts = "Elegí al menos una deuda para financiar.";
    if (anticipo < 0) found.downPayment = "El anticipo no puede ser negativo.";
    if (totalDebt > 0 && anticipo >= totalDebt) {
      found.downPayment = "El anticipo tiene que ser menor a la deuda: si no, no hay nada que financiar.";
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
      onDone(
        await portalService.requestPaymentPlan({
          taxpayerId,
          debtIds: selectedIds,
          installments,
          downPayment: anticipo,
        }),
      );
    } catch (caught) {
      setSubmitError(caught.message);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open
      title="Solicitar plan de pago"
      description="Elegí las deudas, compará las alternativas y enviá la solicitud."
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

      {elegibles.length === 0 ? (
        <Alert variant="info" title="No tenés deudas para financiar">
          Todas tus obligaciones están canceladas o ya incluidas en una solicitud.
        </Alert>
      ) : (
        <form onSubmit={onSubmit} noValidate className="flex flex-col gap-5">
          <fieldset className="flex flex-col gap-2">
            <legend className="mb-1 text-[13px] font-medium text-neutral-700">
              Deudas a financiar <span className="text-[#D63031]">*</span>
            </legend>
            {elegibles.map((debt) => (
              <label
                key={debt.id}
                className="flex cursor-pointer items-center gap-3 rounded-lg border border-neutral-200 bg-neutral-50 px-3.5 py-2.5 transition-colors hover:border-neutral-300"
              >
                <input
                  type="checkbox"
                  checked={selectedIds.includes(debt.id)}
                  onChange={() => toggle(debt.id)}
                  className="h-4 w-4 accent-[#0F2C59]"
                />
                <span className="flex min-w-0 flex-1 flex-col">
                  <span className="text-[13px] font-medium text-neutral-800">
                    {debt.conceptName}
                  </span>
                  <span className="text-[12px] text-neutral-400">
                    Vence {formatDate(debt.dueDate)} · deuda #{debt.id}
                  </span>
                </span>
                <span className="shrink-0 text-[13px] font-semibold tabular-nums text-[#0F2C59]">
                  {formatCurrency(debt.outstandingAmount)}
                </span>
              </label>
            ))}
            {errors.debts && <p className="text-[12px] text-red-500">{errors.debts}</p>}
          </fieldset>

          {totalDebt > 0 && (
            <>
              <div className="flex items-center justify-between rounded-lg bg-[#0F2C59]/[0.04] px-4 py-3">
                <span className="text-[13px] text-neutral-600">Deuda seleccionada</span>
                <span className="text-[16px] font-bold tabular-nums text-[#0F2C59]">
                  {formatCurrency(totalDebt)}
                </span>
              </div>

              <FormField
                label="Anticipo (opcional)"
                name="downPayment"
                type="number"
                placeholder="0"
                value={downPayment}
                onChange={(event) => {
                  setDownPayment(event.target.value);
                  setErrors((previous) => ({ ...previous, downPayment: undefined }));
                }}
                error={errors.downPayment}
              />
              <p className="-mt-3 text-[12px] text-neutral-400">
                Lo que pagues al contado baja la base financiada, así que reduce el interés
                y la cuota.
              </p>

              <fieldset className="flex flex-col gap-2">
                <legend className="mb-1 text-[13px] font-medium text-neutral-700">
                  Elegí en cuántas cuotas
                </legend>
                {alternativas.length === 0 ? (
                  <p className="text-[13px] text-neutral-400">
                    Corregí el anticipo para ver las alternativas.
                  </p>
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
                          <span className="text-[11px] tabular-nums text-neutral-400">
                            Total {formatCurrency(alt.totalAmount)}
                          </span>
                        </button>
                      );
                    })}
                  </div>
                )}
              </fieldset>

              {elegida && (
                <div className="rounded-lg border border-neutral-200 bg-neutral-50 p-4">
                  <p className="text-[11px] font-semibold uppercase tracking-[0.12em] text-neutral-400">
                    Así quedaría tu plan
                  </p>
                  <dl className="mt-3 flex flex-col gap-2">
                    <Line label="Deuda seleccionada" value={formatCurrency(totalDebt)} />
                    <Line label="Anticipo al contado" value={formatCurrency(elegida.downPayment)} />
                    <Line label="Importe financiado" value={formatCurrency(elegida.financedAmount)} />
                    <Line
                      label={`Interés (${formatPercentage(elegida.interestRate * 100)})`}
                      value={formatCurrency(elegida.interestAmount)}
                    />
                    <Line label="Total del plan" value={formatCurrency(elegida.totalAmount)} />
                    <div className="mt-1 flex items-center justify-between border-t border-neutral-200 pt-2.5">
                      <dt className="text-[13px] font-semibold text-neutral-700">
                        {elegida.installments} cuotas de
                      </dt>
                      <dd className="text-[18px] font-extrabold tabular-nums text-[#0F2C59]">
                        {formatCurrency(elegida.installmentAmount)}
                      </dd>
                    </div>
                  </dl>
                  <p className="mt-3 text-[12px] text-neutral-400">
                    Es una estimación. El importe final lo confirma Rentas al resolver.
                  </p>
                </div>
              )}
            </>
          )}
        </form>
      )}
    </Modal>
  );
}

/** Detalle del plan con su cuadro de cuotas, cuando ya fue otorgado. */
function PlanDetailModal({ plan, onClose }) {
  if (!plan) return null;

  return (
    <Modal
      open
      title={plan.planId ? `Plan #${plan.planId}` : `Solicitud #${plan.requestId}`}
      description={`Solicitado el ${formatDate(plan.requestedAt)}`}
      onClose={onClose}
      footer={
        <Button variant="secondary" onClick={onClose}>
          Cerrar
        </Button>
      }
    >
      <dl className="grid grid-cols-2 gap-3">
        <Field label="Estado" value={<StatusBadge status={plan.lifecycle ?? plan.status} />} />
        <Field label="Cuotas" value={plan.installments} />
        <Field label="Deuda incluida" value={formatCurrency(plan.totalDebt)} />
        <Field label="Anticipo" value={formatCurrency(plan.downPayment)} />
        <Field
          label="Importe financiado"
          value={plan.financedAmount ? formatCurrency(plan.financedAmount) : "A confirmar"}
        />
        <Field
          label="Interés"
          value={plan.interestAmount ? formatCurrency(plan.interestAmount) : "A confirmar"}
        />
        <Field
          label="Total del plan"
          value={plan.totalAmount ? formatCurrency(plan.totalAmount) : "A confirmar"}
        />
      </dl>

      {plan.status === "REJECTED" && (
        <Alert variant="error" title="Solicitud rechazada">
          {plan.reason}
        </Alert>
      )}

      {plan.status === "REQUESTED" && (
        <Alert variant="info" title="Pendiente de resolución">
          Rentas todavía no resolvió tu solicitud. Mientras tanto, la deuda mantiene su
          vencimiento original.
        </Alert>
      )}

      <div>
        <p className="text-[11px] font-semibold uppercase tracking-[0.12em] text-neutral-400">
          Deudas incluidas
        </p>
        <ul className="mt-2 flex flex-col gap-1">
          {plan.debts.map((debt) => (
            <li key={debt.id} className="flex items-center justify-between text-[13px]">
              <span className="text-neutral-600">
                {debt.conceptName} <span className="text-neutral-400">#{debt.id}</span>
              </span>
              <span className="font-medium tabular-nums text-neutral-800">
                {formatCurrency(debt.originalAmount)}
              </span>
            </li>
          ))}
        </ul>
      </div>

      {plan.schedule?.length > 0 && (
        <div>
          <p className="text-[11px] font-semibold uppercase tracking-[0.12em] text-neutral-400">
            Cuotas
          </p>
          <ul className="mt-2 flex flex-col gap-1">
            {plan.schedule.map((cuota) => (
              <li key={cuota.number} className="flex items-center justify-between gap-3 text-[13px]">
                <span className="text-neutral-600">
                  Cuota {cuota.number} · vence {formatDate(cuota.dueDate)}
                </span>
                <span className="flex items-center gap-2">
                  <span className="font-medium tabular-nums text-neutral-800">
                    {formatCurrency(cuota.amount)}
                  </span>
                  <StatusBadge status={cuota.status} />
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </Modal>
  );
}

function Line({ label, value }) {
  return (
    <div className="flex items-center justify-between">
      <dt className="text-[13px] text-neutral-500">{label}</dt>
      <dd className="text-[13px] font-medium tabular-nums text-neutral-700">{value}</dd>
    </div>
  );
}

function Field({ label, value }) {
  return (
    <div>
      <dt className="text-[11px] font-semibold uppercase tracking-[0.1em] text-neutral-400">
        {label}
      </dt>
      <dd className="mt-0.5 text-[13px] text-neutral-700">{value}</dd>
    </div>
  );
}
