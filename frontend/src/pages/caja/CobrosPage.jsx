import { useCallback, useEffect, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { ArrowLeft, CircleCheckBig, Printer, Search } from "lucide-react";
import ModuleShell from "../../components/layout/ModuleShell.jsx";
import Card from "../../components/common/Card.jsx";
import DataTable from "../../components/common/DataTable.jsx";
import StatusBadge from "../../components/common/StatusBadge.jsx";
import Button from "../../components/common/Button.jsx";
import Alert from "../../components/ui/Alert.jsx";
import FormField from "../../components/ui/FormField.jsx";
import Spinner from "../../components/ui/Spinner.jsx";
import StepIndicatorGeneric from "../../components/ui/StepIndicatorGeneric.jsx";
import ReceiptCard, { printReceipt } from "../../components/caja/ReceiptCard.jsx";
import useResource from "../../hooks/useResource.js";
import { cashierService } from "../../services/rentasService.js";
import { PAYMENT_METHODS } from "../../services/mockDb.js";
import { useAuth } from "../../context/AuthContext.jsx";
import { formatCurrency, formatDate } from "../../lib/format.js";

const STEPS = [{ label: "Buscar" }, { label: "Cobrar" }, { label: "Comprobante" }];

const KIND_LABELS = {
  TAXPAYER: "Contribuyente",
  BILL: "Boleta",
  DEBT: "Deuda",
};

/**
 * Cobros: el flujo principal de la ventanilla.
 *
 * Buscar → cobrar → comprobante. La búsqueda acepta documento, CUIT o nombre (devuelve
 * al contribuyente con su deuda consolidada) y también un N° de boleta o de deuda
 * (devuelve la obligación puntual y a qué contribuyente está vinculada).
 */
export default function CobrosPage() {
  const { user } = useAuth();
  const [searchParams, setSearchParams] = useSearchParams();

  const [query, setQuery] = useState(searchParams.get("q") ?? "");
  const [submitted, setSubmitted] = useState(searchParams.get("q") ?? "");
  const [selection, setSelection] = useState(() => {
    const bill = searchParams.get("boleta");
    if (bill) return { kind: "BILL", id: Number(bill) };
    const debt = searchParams.get("deuda");
    if (debt) return { kind: "DEBT", id: Number(debt) };
    const taxpayer = searchParams.get("contribuyente");
    if (taxpayer) return { kind: "TAXPAYER", id: Number(taxpayer) };
    return null;
  });
  const [receipt, setReceipt] = useState(null);

  const searchLoader = useCallback(
    () => (submitted.trim() ? cashierService.search({ query: submitted }) : Promise.resolve([])),
    [submitted],
  );
  const { data: results, loading: searching, error: searchError } = useResource(searchLoader, []);

  const contextLoader = useCallback(
    () => (selection ? cashierService.chargeContext(selection) : Promise.resolve(null)),
    [selection],
  );
  const { data: context, loading: loadingContext, error: contextError } = useResource(contextLoader);

  const step = receipt ? 2 : selection ? 1 : 0;

  const backToSearch = () => {
    setSelection(null);
    setReceipt(null);
    setSearchParams({}, { replace: true });
  };

  const startOver = () => {
    setQuery("");
    setSubmitted("");
    backToSearch();
  };

  const columns = [
    {
      key: "kind",
      header: "Tipo",
      render: (row) => (
        <StatusBadge tone={row.kind === "TAXPAYER" ? "navy" : "info"} label={KIND_LABELS[row.kind]} />
      ),
    },
    {
      key: "title",
      header: "Resultado",
      render: (row) => (
        <div className="flex flex-col">
          <span className="font-medium text-neutral-800">{row.title}</span>
          <span className="text-[12px] text-neutral-400">{row.subtitle}</span>
        </div>
      ),
    },
    { key: "detail", header: "Detalle", render: (row) => row.detail },
    {
      key: "dueDate",
      header: "Vencimiento",
      render: (row) => (row.dueDate ? formatDate(row.dueDate) : "—"),
    },
    {
      key: "amount",
      header: "Importe",
      align: "right",
      render: (row) => formatCurrency(row.amount),
    },
    { key: "status", header: "Estado", render: (row) => <StatusBadge status={row.status} /> },
  ];

  return (
    <ModuleShell
      label="Ventanilla"
      title="Cobros"
      highlight="en caja"
      description="Buscá al contribuyente o su boleta, registrá el cobro y entregá el comprobante."
      breadcrumb={[{ id: "cobros", label: "Cobros" }]}
      homePath="/caja"
      homeLabel="Panel de caja"
      actions={<StepIndicatorGeneric steps={STEPS} currentStep={step} />}
    >
      {step === 0 && (
        <>
          {searchError && (
            <Alert variant="error" title="No pudimos buscar">
              {searchError}
            </Alert>
          )}

          <Card
            title="Buscar contribuyente o boleta"
            description="Documento o CUIT para ver toda su deuda; N° de boleta o de deuda para cobrar una obligación puntual."
          >
            <form
              onSubmit={(event) => {
                event.preventDefault();
                setSubmitted(query);
              }}
              className="flex flex-col gap-3 border-b border-neutral-100 px-5 py-4 sm:flex-row sm:items-center"
            >
              <div className="relative flex-1">
                <Search
                  className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-neutral-400"
                  strokeWidth={2}
                />
                <input
                  type="search"
                  value={query}
                  onChange={(event) => setQuery(event.target.value)}
                  placeholder="DNI / CUIT / N° de boleta / N° de deuda"
                  aria-label="DNI / CUIT / N° de boleta / N° de deuda"
                  className="w-full rounded-lg border border-neutral-200 bg-neutral-50 py-2.5 pl-9 pr-3 text-[14px] text-neutral-900 placeholder-neutral-400 outline-none transition-colors focus:border-[#D63031]/40 focus:bg-white focus:ring-2 focus:ring-[#D63031]/10"
                />
              </div>
              <Button type="submit" variant="primary">
                Buscar
              </Button>
            </form>

            {submitted.trim() ? (
              <DataTable
                columns={columns}
                rows={results ?? []}
                rowKey={(row) => `${row.kind}-${row.id}`}
                loading={searching}
                emptyIconName="SearchX"
                emptyTitle="Sin coincidencias"
                emptyDescription="Revisá el documento o el número ingresado."
                onRowClick={(row) => setSelection({ kind: row.kind, id: row.id })}
              />
            ) : (
              <p className="px-5 py-10 text-center text-[13px] text-neutral-400">
                Ingresá un dato para comenzar el cobro.
              </p>
            )}
          </Card>
        </>
      )}

      {step === 1 && (
        <>
          <Button variant="ghost" size="sm" className="self-start" onClick={backToSearch}>
            <ArrowLeft className="h-3.5 w-3.5" strokeWidth={2} />
            Volver a la búsqueda
          </Button>

          {contextError && (
            <Alert variant="error" title="No pudimos abrir el cobro">
              {contextError}
            </Alert>
          )}

          {loadingContext && (
            <div className="flex items-center justify-center gap-3 py-14">
              <Spinner />
              <span className="text-[13px] text-neutral-400">Buscando la deuda…</span>
            </div>
          )}

          {context && (
            <ChargeStep
              context={context}
              cashier={user.username}
              onCharged={setReceipt}
              onCancel={backToSearch}
            />
          )}
        </>
      )}

      {step === 2 && receipt && (
        <>
          <Alert variant="success" title="Pago registrado correctamente">
            {receipt.receiptNumber} por {formatCurrency(receipt.amountPaid)}
            {receipt.settled
              ? " — la deuda quedó cancelada."
              : ` — queda un saldo de ${formatCurrency(receipt.remainingBalance ?? 0)}.`}
          </Alert>

          <div className="flex items-center gap-2 text-emerald-600 no-print">
            <CircleCheckBig className="h-5 w-5" strokeWidth={2} />
            <span className="text-[14px] font-semibold">Entregá el comprobante al contribuyente</span>
          </div>

          <ReceiptCard receipt={receipt} />

          <div className="flex flex-wrap gap-2 no-print">
            <Button variant="primary" onClick={printReceipt}>
              <Printer className="h-4 w-4" strokeWidth={2} />
              Imprimir comprobante
            </Button>
            <Button variant="secondary" onClick={startOver}>
              Registrar otro
            </Button>
          </div>
        </>
      )}
    </ModuleShell>
  );
}

/**
 * Paso de cobro: muestra a quién se le cobra y qué se cobra, y registra el pago.
 * Si la búsqueda entró por boleta o por deuda, la obligación ya viene elegida.
 */
function ChargeStep({ context, cashier, onCharged, onCancel }) {
  const { taxpayer, bill, debts, totals, kind } = context;

  const [debtId, setDebtId] = useState(String(context.selectedDebtId ?? ""));
  const [amount, setAmount] = useState("");
  const [method, setMethod] = useState("");
  const [errors, setErrors] = useState({});
  const [submitError, setSubmitError] = useState(null);
  const [submitting, setSubmitting] = useState(false);

  const selectedDebt = debts.find((d) => d.id === Number(debtId)) ?? null;

  // Por defecto se cobra el saldo completo de la deuda elegida.
  useEffect(() => {
    setAmount(selectedDebt ? String(selectedDebt.outstandingAmount) : "");
  }, [selectedDebt]);

  const validate = () => {
    const found = {};
    if (!debtId) found.debtId = "Elegí la deuda a cobrar.";
    if (!(Number(amount) > 0)) found.amount = "Ingresá un importe mayor a cero.";
    if (selectedDebt && Number(amount) > selectedDebt.outstandingAmount) {
      found.amount = "El importe supera el saldo de la deuda.";
    }
    if (!method) found.method = "Indicá el medio de pago.";
    setErrors(found);
    return Object.keys(found).length === 0;
  };

  const onSubmit = async (event) => {
    event.preventDefault();
    setSubmitError(null);
    if (!validate()) return;
    setSubmitting(true);
    try {
      onCharged(
        await cashierService.registerCounterPayment({
          debtId,
          billId: bill?.id ?? null,
          amountPaid: Number(amount),
          method,
          registeredBy: cashier,
        }),
      );
    } catch (caught) {
      setSubmitError(caught.message);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <>
      <Card title={taxpayer.name} description={`${taxpayer.documentType} ${taxpayer.document} · CUIT ${taxpayer.cuit}`}>
        <div className="grid grid-cols-2 gap-4 px-5 py-4 sm:grid-cols-4">
          <Metric label="Situación" value={<StatusBadge status={taxpayer.status} />} />
          <Metric label="Deudas pendientes" value={totals.pendingCount} />
          <Metric label="Deuda total" value={formatCurrency(totals.outstanding)} />
          <Metric
            label="Vencida"
            value={formatCurrency(totals.overdue)}
            tone={totals.overdue > 0 ? "danger" : "neutral"}
          />
        </div>
      </Card>

      {kind !== "TAXPAYER" && (
        <Alert variant="info" title={bill ? `Boleta #${bill.id}` : `Deuda #${debts[0]?.id}`}>
          Vinculada a {taxpayer.name} ({taxpayer.documentType} {taxpayer.document}).
        </Alert>
      )}

      {taxpayer.status === "BLOCKED" && (
        <Alert variant="info" title="Contribuyente bloqueado en M1">
          El bloqueo no impide cobrar: la deuda se puede cancelar igual en ventanilla.
        </Alert>
      )}

      {debts.length === 0 ? (
        <Alert variant="info" title="Sin deuda para cobrar">
          {kind === "TAXPAYER"
            ? "El contribuyente no tiene deudas con saldo pendiente."
            : "La obligación seleccionada ya está cancelada."}
        </Alert>
      ) : (
        <Card
          title="Registrar el cobro"
          description="El pago se imputa a la deuda y publica paymentRegistered hacia el módulo de origen."
        >
          <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4 px-5 py-5">
            {submitError && (
              <Alert variant="error" title="No se pudo registrar el pago">
                {submitError}
              </Alert>
            )}

            {kind === "TAXPAYER" ? (
              <FormField
                label="Deuda a cobrar"
                name="debtId"
                type="select"
                value={debtId}
                onChange={(event) => setDebtId(event.target.value)}
                error={errors.debtId}
                required
                options={debts.map((debt) => ({
                  value: String(debt.id),
                  label: `#${debt.id} · ${debt.conceptCode} · ${formatCurrency(debt.outstandingAmount)}`,
                }))}
              />
            ) : (
              selectedDebt && (
                <div className="rounded-lg border border-neutral-200 bg-neutral-50 p-4 text-[13px] text-neutral-600">
                  <p>
                    Concepto:{" "}
                    <span className="font-medium text-neutral-800">{selectedDebt.conceptCode}</span>
                  </p>
                  <p className="mt-1">
                    Vencimiento:{" "}
                    <span className="font-medium text-neutral-800">
                      {formatDate(selectedDebt.dueDate)}
                    </span>{" "}
                    · <StatusBadge status={selectedDebt.status} />
                  </p>
                  <p className="mt-1">
                    Saldo:{" "}
                    <span className="font-semibold text-[#0F2C59]">
                      {formatCurrency(selectedDebt.outstandingAmount)}
                    </span>
                  </p>
                </div>
              )
            )}

            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <FormField
                label="Medio de pago"
                name="method"
                type="select"
                value={method}
                onChange={(event) => setMethod(event.target.value)}
                options={PAYMENT_METHODS}
                error={errors.method}
                required
              />
              <FormField
                label="Importe a cobrar"
                name="amount"
                type="number"
                value={amount}
                onChange={(event) => setAmount(event.target.value)}
                error={errors.amount}
                required
              />
            </div>

            {selectedDebt && Number(amount) > 0 && Number(amount) < selectedDebt.outstandingAmount && (
              <Alert variant="info" title="Pago parcial">
                Queda un saldo de{" "}
                {formatCurrency(selectedDebt.outstandingAmount - Number(amount))} en la deuda #
                {selectedDebt.id}.
              </Alert>
            )}

            <div className="flex flex-col gap-2 sm:flex-row-reverse">
              <Button
                type="submit"
                variant="accent"
                loading={submitting}
                className="w-full py-3.5 text-[15px] sm:w-auto sm:px-10"
              >
                Registrar pago
              </Button>
              <Button type="button" variant="secondary" onClick={onCancel}>
                Cancelar
              </Button>
            </div>
          </form>
        </Card>
      )}
    </>
  );
}

function Metric({ label, value, tone = "neutral" }) {
  return (
    <div>
      <p className="text-[11px] font-semibold uppercase tracking-[0.1em] text-neutral-400">
        {label}
      </p>
      <p
        className={`mt-1 text-[15px] font-bold tabular-nums ${
          tone === "danger" ? "text-[#D63031]" : "text-[#0F2C59]"
        }`}
      >
        {value}
      </p>
    </div>
  );
}
