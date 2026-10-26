import { useCallback, useMemo, useRef, useState } from "react";
import ModuleShell from "../../components/layout/ModuleShell.jsx";
import Card from "../../components/common/Card.jsx";
import DataTable from "../../components/common/DataTable.jsx";
import StatusBadge from "../../components/common/StatusBadge.jsx";
import Button from "../../components/common/Button.jsx";
import Alert from "../../components/ui/Alert.jsx";
import FormField from "../../components/ui/FormField.jsx";
import FieldGrid from "../../components/auditoria/FieldGrid.jsx";
import useResource from "../../hooks/useResource.js";
import { portalService } from "../../services/rentasService.js";
import { useAuth } from "../../context/AuthContext.jsx";
import { formatCurrency, formatDateTime } from "../../lib/format.js";

const METHODS = [
  { value: "CARD", label: "Tarjeta" },
  { value: "DIGITAL_WALLET", label: "Billetera virtual / QR" },
];
const PAYMENT_INTENT_STORAGE_KEY = "rentas.electronicPaymentIntent";

function readPaymentIntent() {
  try {
    return JSON.parse(sessionStorage.getItem(PAYMENT_INTENT_STORAGE_KEY) ?? "null");
  } catch {
    return null;
  }
}

export default function PagoElectronicoPage() {
  const { user } = useAuth();
  const loader = useCallback(() => portalService.debts({ taxpayerId: user.taxpayerId }), [user.taxpayerId]);
  const { data: debts, loading, error, reload } = useResource(loader, []);
  const payable = useMemo(() => (debts ?? []).filter((debt) => Number(debt.outstandingAmount) > 0 && !debt.inPaymentPlan), [debts]);
  const [selected, setSelected] = useState(null);
  const [method, setMethod] = useState("CARD");
  const [amount, setAmount] = useState("");
  const [preview, setPreview] = useState(null);
  const [result, setResult] = useState(null);
  const [receipt, setReceipt] = useState(null);
  const [receiptError, setReceiptError] = useState(null);
  const [working, setWorking] = useState(false);
  const [submitError, setSubmitError] = useState(null);
  const paymentIntent = useRef(null);

  const selectDebt = (debt) => {
    setSelected(debt);
    setAmount(String(debt.outstandingAmount));
    setPreview(null);
    setResult(null);
    setReceipt(null);
    setReceiptError(null);
  };

  const previewPayment = async () => {
    if (!selected || !(Number(amount) > 0)) {
      setSubmitError("Elegí una deuda e ingresá un importe mayor a cero.");
      return;
    }
    setWorking(true);
    setSubmitError(null);
    try {
      setPreview(await portalService.previewElectronicPayment({ debtId: selected.id, paymentMethod: method, amount: Number(amount) }));
    } catch (caught) {
      setSubmitError(caught.message);
    } finally {
      setWorking(false);
    }
  };

  const confirmPayment = async () => {
    setWorking(true);
    setSubmitError(null);
    try {
      const fingerprint = JSON.stringify([selected.id, method, preview.payableAmount]);
      if (!paymentIntent.current || paymentIntent.current.fingerprint !== fingerprint) {
        const storedIntent = readPaymentIntent();
        paymentIntent.current = storedIntent?.fingerprint === fingerprint
          ? storedIntent
          : { fingerprint, idempotencyKey: crypto.randomUUID() };
        sessionStorage.setItem(PAYMENT_INTENT_STORAGE_KEY, JSON.stringify(paymentIntent.current));
      }
      const payment = await portalService.createElectronicPayment({
        debtId: selected.id,
        paymentMethod: method,
        amount: preview.payableAmount,
        idempotencyKey: paymentIntent.current.idempotencyKey,
      });
      setResult(payment);
      paymentIntent.current = null;
      sessionStorage.removeItem(PAYMENT_INTENT_STORAGE_KEY);
      reload();
      if (payment.status === "APPROVED") {
        try {
          setReceipt(await portalService.paymentReceipt(payment.paymentId));
        } catch {
          setReceiptError("El pago se confirmó, pero no pudimos cargar el comprobante. Podés consultarlo en Mis pagos.");
        }
      }
    } catch (caught) {
      setSubmitError(caught.message);
    } finally {
      setWorking(false);
    }
  };

  return (
    <ModuleShell
      label="Mi cuenta"
      title="Pago electrónico"
      highlight="simulado"
      description="Elegí una deuda, revisá el importe y confirmá con tarjeta o billetera virtual."
      breadcrumb={[{ id: "pago-electronico", label: "Pago electrónico" }]}
      homePath="/portal"
      homeLabel="Inicio"
    >
      {error && <Alert variant="error" title="No pudimos cargar tus deudas">{error}</Alert>}
      {submitError && <Alert variant="error" title="No se pudo completar">{submitError}</Alert>}
      {result ? (
        <Card title="Resultado del pago" description="El comprobante proviene del backend y queda asociado a tu cuenta.">
          <div className="flex flex-col gap-5 px-5 py-5">
            <Alert variant={result.status === "APPROVED" ? "success" : "error"} title={result.status === "APPROVED" ? "Pago aprobado" : "Pago rechazado"}>
              Operación {result.gatewayReference} · {formatCurrency(result.amount)}.
            </Alert>
            {receiptError && <Alert variant="error" title="Comprobante no disponible">{receiptError}</Alert>}
            <FieldGrid columns={3} items={[
              { label: "Pago", value: `#${result.paymentId}` },
              { label: "Estado", value: <StatusBadge status={result.status} /> },
              { label: "Fecha", value: formatDateTime(result.createdAt) },
              { label: "Comprobante", value: receipt?.receiptNumber ?? "No emitido" },
              { label: "Importe", value: formatCurrency(receipt?.amount ?? result.amount) },
              { label: "Referencia", value: result.gatewayReference },
            ]} />
            <Button variant="secondary" disabled={working} onClick={() => { setSelected(null); setPreview(null); setResult(null); setReceipt(null); setReceiptError(null); }}>Realizar otro pago</Button>
          </div>
        </Card>
      ) : (
        <>
          <Card title="1. Elegí la deuda">
            <DataTable
              columns={[
                { key: "conceptName", header: "Concepto" },
                { key: "id", header: "Deuda", render: (row) => `#${row.id}` },
                { key: "outstandingAmount", header: "Saldo", align: "right", render: (row) => formatCurrency(row.outstandingAmount) },
                { key: "status", header: "Estado", render: (row) => <StatusBadge status={row.status} /> },
                { key: "actions", header: "", align: "right", render: (row) => <Button size="sm" variant={selected?.id === row.id ? "primary" : "secondary"} onClick={() => selectDebt(row)}>{selected?.id === row.id ? "Seleccionada" : "Elegir"}</Button> },
              ]}
              rows={payable}
              rowKey={(row) => row.id}
              loading={loading}
              emptyIconName="CircleCheckBig"
              emptyTitle="Sin deudas pagables"
              emptyDescription="No tenés obligaciones habilitadas para pago electrónico."
            />
          </Card>
          {selected && (
            <Card title="2. Medio e importe" description="Primero se valida la operación; nada se registra hasta confirmar.">
              <div className="grid grid-cols-1 gap-4 px-5 py-5 sm:grid-cols-2">
                <FormField label="Medio de pago" name="method" type="select" value={method} onChange={(event) => { setMethod(event.target.value); setPreview(null); }} options={METHODS} />
                <FormField label="Importe" name="amount" type="number" value={amount} onChange={(event) => { setAmount(event.target.value); setPreview(null); }} />
                <div className="sm:col-span-2"><Button variant="primary" loading={working} onClick={previewPayment}>Revisar pago</Button></div>
              </div>
            </Card>
          )}
          {preview && (
            <Card title="3. Confirmación" description="Este es el último paso antes de registrar el pago.">
              <div className="flex flex-col gap-4 px-5 py-5">
                <Alert variant={preview.approved ? "info" : "error"} title={preview.approved ? "Operación disponible" : "La deuda no admite el pago"}>{preview.message}</Alert>
                <FieldGrid columns={3} items={[
                  { label: "Deuda", value: `#${preview.debtId}` },
                  { label: "Solicitado", value: formatCurrency(preview.requestedAmount) },
                  { label: "Importe a pagar", value: formatCurrency(preview.payableAmount) },
                ]} />
                {preview.approved && <Button variant="accent" loading={working} onClick={confirmPayment}>Confirmar y pagar</Button>}
              </div>
            </Card>
          )}
        </>
      )}
    </ModuleShell>
  );
}
