import { useCallback, useState } from "react";
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
import {
  creditBalanceService,
  debtAdjustmentService,
  debtService,
} from "../../services/rentasService.js";
import { useAuth } from "../../context/AuthContext.jsx";
import { formatCurrency, formatDate, formatDateTime } from "../../lib/format.js";

/**
 * Ajustes manuales y saldos a favor.
 *
 * Dos operaciones que corrigen el saldo de una deuda sin que medie un pago nuevo.
 * El ajuste exige autorización del Supervisor y se ejecuta en un segundo acto;
 * aplicar un saldo a favor sólo usa un crédito que ya se había registrado.
 */
export default function AjustesYSaldosPage() {
  const { user } = useAuth();
  const { nameOf } = useTaxpayerIndex();

  const [proponiendo, setProponiendo] = useState(false);
  const [resolviendo, setResolviendo] = useState(null);
  const [aplicando, setAplicando] = useState(null);
  const [feedback, setFeedback] = useState(null);
  const [trabajando, setTrabajando] = useState(null);

  const adjLoader = useCallback(() => debtAdjustmentService.list(), []);
  const { data: ajustes, loading, error, reload } = useResource(adjLoader, []);

  const creditLoader = useCallback(() => creditBalanceService.list({ status: "ACTIVE" }), []);
  const { data: saldos, reload: reloadSaldos } = useResource(creditLoader, []);

  const refrescar = () => {
    reload();
    reloadSaldos();
  };

  const ejecutar = async (ajuste) => {
    setTrabajando(ajuste.id);
    setFeedback(null);
    try {
      const { debt } = await debtAdjustmentService.execute({
        adjustmentId: ajuste.id,
        executedBy: user.username,
      });
      setFeedback({
        variant: "success",
        title: "Ajuste ejecutado",
        message: ajuste.reportedToM8
          ? `La deuda #${debt.id} quedó en ${formatCurrency(debt.outstandingAmount)}. Se informó la actualización a M8 con debtUpdated.`
          : `La deuda #${debt.id} quedó en ${formatCurrency(debt.outstandingAmount)}.`,
      });
      refrescar();
    } catch (caught) {
      setFeedback({ variant: "error", title: "No se pudo ejecutar", message: caught.message });
    } finally {
      setTrabajando(null);
    }
  };

  const adjColumns = [
    {
      key: "debtId",
      header: "Deuda",
      render: (row) => (
        <div className="flex flex-col">
          <span className="font-medium tabular-nums text-neutral-800">#{row.debtId}</span>
          <span className="text-[12px] text-neutral-400">
            {row.taxpayerId ? nameOf(row.taxpayerId) : row.conceptName}
          </span>
        </div>
      ),
    },
    {
      key: "importe",
      header: "Importe",
      align: "right",
      render: (row) =>
        row.previousAmount === row.newAmount ? (
          <span className="text-neutral-300">sin cambio</span>
        ) : (
          <span className="tabular-nums">
            <span className="text-neutral-400 line-through">
              {formatCurrency(row.previousAmount)}
            </span>{" "}
            <span className="font-semibold text-[#0F2C59]">{formatCurrency(row.newAmount)}</span>
          </span>
        ),
    },
    {
      key: "vencimiento",
      header: "Vencimiento",
      render: (row) =>
        row.previousDueDate === row.newDueDate ? (
          <span className="text-neutral-300">sin cambio</span>
        ) : (
          <span>
            <span className="text-neutral-400 line-through">
              {formatDate(row.previousDueDate)}
            </span>{" "}
            <span className="font-semibold text-[#0F2C59]">{formatDate(row.newDueDate)}</span>
          </span>
        ),
    },
    { key: "reason", header: "Motivo" },
    { key: "requestedBy", header: "Propuso" },
    { key: "status", header: "Estado", render: (row) => <StatusBadge status={row.status} /> },
    {
      key: "actions",
      header: "",
      align: "right",
      render: (row) => {
        if (row.status === "PENDING_APPROVAL") {
          return user.role === "SUPERVISOR" ? (
            <Button size="sm" variant="primary" onClick={() => setResolviendo(row)}>
              Autorizar
            </Button>
          ) : (
            <span className="text-[12px] text-neutral-400">Espera al Supervisor</span>
          );
        }
        if (row.status === "APPROVED") {
          return (
            <Button
              size="sm"
              variant="accent"
              loading={trabajando === row.id}
              onClick={() => ejecutar(row)}
            >
              Ejecutar
            </Button>
          );
        }
        return (
          <span className="text-[12px] text-neutral-400">
            {row.status === "EXECUTED" ? `Por ${row.executedBy}` : row.rejectionReason}
          </span>
        );
      },
    },
  ];

  const creditColumns = [
    {
      key: "id",
      header: "Saldo",
      render: (row) => (
        <div className="flex flex-col">
          <span className="font-medium tabular-nums text-neutral-800">#{row.id}</span>
          <span className="text-[12px] text-neutral-400">{nameOf(row.taxpayerId)}</span>
        </div>
      ),
    },
    {
      key: "amount",
      header: "Original",
      align: "right",
      render: (row) => formatCurrency(row.amount),
    },
    {
      key: "remainingAmount",
      header: "Disponible",
      align: "right",
      render: (row) => (
        <span className="font-semibold text-emerald-600">
          {formatCurrency(row.remainingAmount)}
        </span>
      ),
    },
    {
      key: "generatedAt",
      header: "Generado",
      render: (row) => formatDateTime(row.generatedAt),
    },
    {
      key: "actions",
      header: "",
      align: "right",
      render: (row) => (
        <Button size="sm" variant="primary" onClick={() => setAplicando(row)}>
          Aplicar a una deuda
        </Button>
      ),
    },
  ];

  return (
    <ModuleShell
      label="Operación"
      title="Ajustes y saldos"
      highlight="a favor"
      description="Corregir una deuda con autorización, o aplicarle un crédito ya registrado."
      breadcrumb={[{ id: "ajustes", label: "Ajustes y saldos" }]}
    >
      {feedback && (
        <Alert variant={feedback.variant} title={feedback.title} onDismiss={() => setFeedback(null)}>
          {feedback.message}
        </Alert>
      )}
      {error && (
        <Alert variant="error" title="No pudimos cargar los ajustes">
          {error}
        </Alert>
      )}

      <Card
        title="Saldos a favor"
        description="Aplicar un saldo no genera un pago: usa un crédito que ya se registró."
      >
        <DataTable
          columns={creditColumns}
          rows={saldos ?? []}
          rowKey={(row) => row.id}
          emptyIconName="Wallet"
          emptyTitle="Sin saldos a favor"
          emptyDescription="Ningún contribuyente tiene crédito disponible."
        />
      </Card>

      <Alert variant="info" title="Autorizar y ejecutar son actos distintos">
        El Supervisor autoriza el ajuste, pero se aplica cuando el analista lo ejecuta.
        Si la deuda ya fue informada a Desarrollo Social, la ejecución comunica la
        corrección con <code className="font-semibold">debtUpdated</code>, sin volver a
        publicar <code className="font-semibold">overdueDebt</code>: se leería como una
        deuda nueva.
      </Alert>

      <Card
        title="Ajustes manuales"
        description="Sólo se puede corregir el importe, el vencimiento o ambos."
        actions={
          <Button size="sm" variant="primary" onClick={() => setProponiendo(true)}>
            Proponer ajuste
          </Button>
        }
      >
        <DataTable
          columns={adjColumns}
          rows={ajustes ?? []}
          rowKey={(row) => row.id}
          loading={loading}
          emptyIconName="ClipboardList"
          emptyTitle="Sin ajustes"
          emptyDescription="Todavía no se propuso ninguna corrección."
        />
      </Card>

      {proponiendo && (
        <ProposeAdjustmentModal
          requestedBy={user.username}
          onClose={() => setProponiendo(false)}
          onDone={(ajuste) => {
            setProponiendo(false);
            setFeedback({
              variant: "success",
              title: "Ajuste propuesto",
              message: `El ajuste #${ajuste.id} sobre la deuda #${ajuste.debtId} espera la autorización del Supervisor.`,
            });
            refrescar();
          }}
        />
      )}

      {resolviendo && (
        <ResolveAdjustmentModal
          ajuste={resolviendo}
          user={user}
          onClose={() => setResolviendo(null)}
          onDone={(ajuste, decision) => {
            setResolviendo(null);
            setFeedback({
              variant: "success",
              title: decision === "APPROVED" ? "Ajuste autorizado" : "Ajuste rechazado",
              message:
                decision === "APPROVED"
                  ? "Todavía no se aplicó: alguien del área tiene que ejecutarlo."
                  : ajuste.rejectionReason,
            });
            refrescar();
          }}
        />
      )}

      {aplicando && (
        <ApplyCreditModal
          credit={aplicando}
          taxpayerName={nameOf(aplicando.taxpayerId)}
          appliedBy={user.username}
          onClose={() => setAplicando(null)}
          onDone={(resultado) => {
            setAplicando(null);
            setFeedback({
              variant: "success",
              title: "Saldo aplicado",
              message: resultado.debtSettled
                ? `Se aplicaron ${formatCurrency(resultado.appliedAmount)} y la deuda #${resultado.debt.id} quedó cancelada.`
                : `Se aplicaron ${formatCurrency(resultado.appliedAmount)}. La deuda queda en ${formatCurrency(resultado.debt.outstandingAmount)}.`,
            });
            refrescar();
          }}
        />
      )}
    </ModuleShell>
  );
}

/** RequestDebtAdjustmentRequest: importe, vencimiento o ambos, con motivo. */
function ProposeAdjustmentModal({ requestedBy, onClose, onDone }) {
  const loader = useCallback(() => debtService.list(), []);
  const { data: debts } = useResource(loader, []);
  const { nameOf } = useTaxpayerIndex();

  const [debtId, setDebtId] = useState("");
  const [form, setForm] = useState({ newAmount: "", newDueDate: "", reason: "" });
  const [error, setError] = useState(null);
  const [submitting, setSubmitting] = useState(false);

  const ajustables = (debts ?? []).filter((d) => d.status !== "SETTLED");
  const seleccionada = ajustables.find((d) => d.id === Number(debtId));

  const onChange = (event) => {
    const { name, value } = event.target;
    setForm((previous) => ({ ...previous, [name]: value }));
  };

  const onSubmit = async (event) => {
    event.preventDefault();
    setError(null);
    if (!debtId) {
      setError("Elegí la deuda a ajustar.");
      return;
    }
    setSubmitting(true);
    try {
      onDone(await debtAdjustmentService.request({ debtId, ...form, requestedBy }));
    } catch (caught) {
      setError(caught.message);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open
      title="Proponer ajuste manual"
      description="El cambio no se aplica hasta que el Supervisor lo autorice."
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>
            Cancelar
          </Button>
          <Button variant="primary" loading={submitting} onClick={onSubmit}>
            Proponer
          </Button>
        </>
      }
    >
      {error && (
        <Alert variant="error" title="No se pudo proponer">
          {error}
        </Alert>
      )}

      <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4">
        <FormField
          label="Deuda"
          name="debtId"
          type="select"
          value={debtId}
          onChange={(event) => {
            setDebtId(event.target.value);
            const d = ajustables.find((x) => x.id === Number(event.target.value));
            setForm((previous) => ({
              ...previous,
              newAmount: d ? String(d.outstandingAmount) : "",
              newDueDate: d ? d.dueDate : "",
            }));
          }}
          required
          options={ajustables.map((d) => ({
            value: String(d.id),
            label: `#${d.id} · ${nameOf(d.taxpayerId)} · ${formatCurrency(d.outstandingAmount)}`,
          }))}
        />

        {seleccionada && (
          <div className="rounded-lg border border-neutral-200 bg-neutral-50 p-4 text-[13px] text-neutral-600">
            Hoy: {formatCurrency(seleccionada.outstandingAmount)} con vencimiento{" "}
            {formatDate(seleccionada.dueDate)}.
            {seleccionada.reportedToM8 && (
              <p className="mt-1 font-medium text-[#0F2C59]">
                Ya fue informada a M8: al ejecutar se comunicará la corrección.
              </p>
            )}
          </div>
        )}

        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <FormField
            label="Importe ajustado"
            name="newAmount"
            type="number"
            value={form.newAmount}
            onChange={onChange}
            disabled={!debtId}
          />
          <FormField
            label="Vencimiento ajustado"
            name="newDueDate"
            type="date"
            value={form.newDueDate}
            onChange={onChange}
            disabled={!debtId}
          />
        </div>

        <FormField
          label="Motivo del ajuste"
          name="reason"
          type="textarea"
          placeholder="Por ejemplo: error en la base imponible informada."
          value={form.reason}
          onChange={onChange}
          required
        />
      </form>
    </Modal>
  );
}

/** El Supervisor autoriza o rechaza. Autorizar no aplica el cambio. */
function ResolveAdjustmentModal({ ajuste, user, onClose, onDone }) {
  const [decision, setDecision] = useState("APPROVED");
  const [reason, setReason] = useState("");
  const [error, setError] = useState(null);
  const [submitting, setSubmitting] = useState(false);

  const onSubmit = async (event) => {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      const resultado = await debtAdjustmentService.resolve({
        adjustmentId: ajuste.id,
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
      title={`Ajuste #${ajuste.id} sobre la deuda #${ajuste.debtId}`}
      description={ajuste.reason}
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
        <Campo label="Importe actual" value={formatCurrency(ajuste.previousAmount)} />
        <Campo label="Importe propuesto" value={formatCurrency(ajuste.newAmount)} />
        <Campo label="Vencimiento actual" value={formatDate(ajuste.previousDueDate)} />
        <Campo label="Vencimiento propuesto" value={formatDate(ajuste.newDueDate)} />
        <Campo label="Propuso" value={ajuste.requestedBy} />
        <Campo label="Fecha" value={formatDate(ajuste.requestedAt)} />
      </dl>

      <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4">
        <FormField
          label="Decisión"
          name="decision"
          type="select"
          value={decision}
          onChange={(event) => setDecision(event.target.value)}
          options={[
            { value: "APPROVED", label: "Autorizar" },
            { value: "REJECTED", label: "Rechazar" },
          ]}
          required
        />

        {decision === "APPROVED" ? (
          <Alert variant="info" title="Autorizar no aplica el cambio">
            El ajuste queda habilitado, pero la deuda se modifica recién cuando alguien
            del área lo ejecuta.
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

/** ApplyCreditBalanceRequest: total o parcial, nunca más que el saldo ni que la deuda. */
function ApplyCreditModal({ credit, taxpayerName, appliedBy, onClose, onDone }) {
  const loader = useCallback(() => creditBalanceService.applicableDebts(credit.id), [credit.id]);
  const { data: debts } = useResource(loader, []);

  const [debtId, setDebtId] = useState("");
  const [amount, setAmount] = useState("");
  const [error, setError] = useState(null);
  const [submitting, setSubmitting] = useState(false);

  const seleccionada = (debts ?? []).find((d) => d.id === Number(debtId));
  const tope = seleccionada
    ? Math.min(credit.remainingAmount, seleccionada.outstandingAmount)
    : credit.remainingAmount;

  const onSubmit = async (event) => {
    event.preventDefault();
    setError(null);
    if (!debtId) {
      setError("Elegí la deuda a la que aplicar el saldo.");
      return;
    }
    setSubmitting(true);
    try {
      onDone(
        await creditBalanceService.apply({
          creditId: credit.id,
          debtId,
          amount: amount === "" ? tope : Number(amount),
          appliedBy,
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
      title={`Aplicar el saldo a favor #${credit.id}`}
      description={`${taxpayerName} · ${formatCurrency(credit.remainingAmount)} disponibles`}
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>
            Cancelar
          </Button>
          <Button variant="primary" loading={submitting} onClick={onSubmit}>
            Aplicar
          </Button>
        </>
      }
    >
      {error && (
        <Alert variant="error" title="No se pudo aplicar">
          {error}
        </Alert>
      )}

      <Alert variant="info" title="No se registra un pago nuevo">
        El dinero ya se registró cuando se generó el saldo. Aplicarlo sólo usa ese
        crédito para reducir una deuda.
      </Alert>

      {(debts ?? []).length === 0 ? (
        <Alert variant="info" title="Sin deudas para aplicar">
          El contribuyente no tiene deudas con saldo pendiente.
        </Alert>
      ) : (
        <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4">
          <FormField
            label="Deuda"
            name="debtId"
            type="select"
            value={debtId}
            onChange={(event) => {
              setDebtId(event.target.value);
              setAmount("");
            }}
            required
            options={(debts ?? []).map((d) => ({
              value: String(d.id),
              label: `#${d.id} · ${d.conceptName} · ${formatCurrency(d.outstandingAmount)}`,
            }))}
          />

          <FormField
            label="Importe a aplicar"
            name="amount"
            type="number"
            placeholder={String(tope)}
            value={amount}
            onChange={(event) => setAmount(event.target.value)}
            disabled={!debtId}
          />
          {seleccionada && (
            <p className="-mt-3 text-[12px] text-neutral-400">
              Dejalo vacío para aplicar el máximo posible: {formatCurrency(tope)}.
            </p>
          )}
        </form>
      )}
    </Modal>
  );
}

function Campo({ label, value }) {
  return (
    <div>
      <dt className="text-[11px] font-semibold uppercase tracking-[0.1em] text-neutral-400">
        {label}
      </dt>
      <dd className="mt-0.5 text-neutral-700">{value}</dd>
    </div>
  );
}
