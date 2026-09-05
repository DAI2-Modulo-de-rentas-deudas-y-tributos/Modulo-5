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
import useTaxpayerIndex from "../../hooks/useTaxpayerIndex.js";
import { debtService, paymentService, reconciliationService } from "../../services/rentasService.js";
import { formatCurrency, formatDateTime } from "../../lib/format.js";

const CHANNELS = [
  { value: "VENTANILLA", label: "Ventanilla" },
  { value: "TRANSFERENCIA", label: "Transferencia" },
  { value: "HOMEBANKING", label: "Homebanking" },
  { value: "DEBITO_AUTOMATICO", label: "Débito automático" },
];

/**
 * Pagos: el corazón operativo del área.
 *
 * Registrar imputa el pago a la deuda y publica paymentRegistered (y debtSettled si
 * el saldo llega a cero). Reversar publica paymentReversed y devuelve el saldo a la
 * deuda. Ambos eventos viajan a M4 o M7 según el origen lógico de la obligación.
 */
export default function PagosPage() {
  const [status, setStatus] = useState("");
  const [modal, setModal] = useState(null); // { type: "register" | "allocate" | "reverse", payment }
  const [feedback, setFeedback] = useState(null);

  const loader = useCallback(() => paymentService.list({ status }), [status]);
  const { data: payments, loading, error, reload } = useResource(loader, []);
  const { nameOf, options: taxpayerOptions } = useTaxpayerIndex();

  const closeModal = () => setModal(null);

  const afterAction = (title, message) => {
    closeModal();
    setFeedback({ variant: "success", title, message });
    reload();
  };

  const columns = [
    {
      key: "receiptNumber",
      header: "Comprobante",
      render: (row) => (
        <div className="flex flex-col">
          <span className="font-medium text-neutral-800">{row.receiptNumber}</span>
          <span className="text-[12px] text-neutral-400 tabular-nums">Pago #{row.id}</span>
        </div>
      ),
    },
    { key: "taxpayer", header: "Contribuyente", render: (row) => nameOf(row.taxpayerId) },
    {
      key: "debtId",
      header: "Imputación",
      render: (row) =>
        row.debtId ? (
          <span className="tabular-nums text-neutral-600">Deuda #{row.debtId}</span>
        ) : (
          <StatusBadge tone="warning" label="Sin imputar" />
        ),
    },
    {
      key: "amountPaid",
      header: "Importe",
      align: "right",
      render: (row) => (
        <span className={row.status === "REVERSED" ? "text-neutral-400 line-through" : ""}>
          {formatCurrency(row.amountPaid)}
        </span>
      ),
    },
    { key: "channel", header: "Canal" },
    { key: "paidAt", header: "Fecha", render: (row) => formatDateTime(row.paidAt) },
    { key: "status", header: "Estado", render: (row) => <StatusBadge status={row.status} /> },
    {
      key: "actions",
      header: "",
      align: "right",
      render: (row) => (
        <div className="flex justify-end gap-2">
          {!row.allocated && row.status !== "REVERSED" && (
            <Button
              size="sm"
              variant="secondary"
              onClick={() => setModal({ type: "allocate", payment: row })}
            >
              Imputar
            </Button>
          )}
          {row.status !== "REVERSED" && (
            <Button
              size="sm"
              variant="danger"
              onClick={() => setModal({ type: "reverse", payment: row })}
            >
              Reversar
            </Button>
          )}
        </div>
      ),
    },
  ];

  return (
    <ModuleShell
      label="Operación"
      title="Pagos"
      highlight="registrados"
      description="Registrá cobros, imputá los pagos sin identificar y reversá los cargados por error."
      breadcrumb={[{ id: "pagos", label: "Pagos" }]}
    >
      {feedback && (
        <Alert variant={feedback.variant} title={feedback.title} onDismiss={() => setFeedback(null)}>
          {feedback.message}
        </Alert>
      )}
      {error && <Alert variant="error" title="No pudimos cargar los pagos">{error}</Alert>}

      <Card
        title="Movimientos"
        description="Cada pago imputado publica paymentRegistered hacia el módulo de origen."
        actions={
          <Button size="sm" variant="primary" onClick={() => setModal({ type: "register" })}>
            Registrar pago
          </Button>
        }
      >
        <FilterBar
          filters={[
            {
              name: "status",
              label: "Estado",
              options: [
                { value: "REGISTERED", label: "Registrado" },
                { value: "UNALLOCATED", label: "Sin imputar" },
                { value: "REVERSED", label: "Reversado" },
              ],
            },
          ]}
          values={{ status }}
          onFilterChange={(_, value) => setStatus(value)}
        />

        <DataTable
          columns={columns}
          rows={payments ?? []}
          rowKey={(row) => row.id}
          loading={loading}
          emptyIconName="Banknote"
          emptyTitle="Sin pagos registrados"
          emptyDescription="Registrá un cobro para verlo en este listado."
        />
      </Card>

      <ReconciliationPanel />

      {modal?.type === "register" && (
        <RegisterPaymentModal
          taxpayerOptions={taxpayerOptions}
          onClose={closeModal}
          onDone={(payment) =>
            afterAction(
              "Pago registrado",
              payment.allocated
                ? `${payment.receiptNumber} imputado a la deuda #${payment.debtId}.`
                : `${payment.receiptNumber} quedó sin imputar: asignalo a una deuda cuando se identifique.`,
            )
          }
        />
      )}

      {modal?.type === "allocate" && (
        <AllocatePaymentModal
          payment={modal.payment}
          onClose={closeModal}
          onDone={(payment) =>
            afterAction("Pago imputado", `Se aplicó a la deuda #${payment.debtId}.`)
          }
        />
      )}

      {modal?.type === "reverse" && (
        <ReversePaymentModal
          payment={modal.payment}
          onClose={closeModal}
          onDone={(payment) =>
            afterAction(
              "Pago reversado",
              `Se publicó paymentReversed por ${formatCurrency(payment.amountPaid)}.`,
            )
          }
        />
      )}
    </ModuleShell>
  );
}

function ReconciliationPanel() {
  const [modal,setModal]=useState(null);const [feedback,setFeedback]=useState(null);
  const loader=useCallback(()=>reconciliationService.observed(),[]);const {data:observed,loading,reload}=useResource(loader,[]);
  const columns=[
    {key:"externalReference",header:"Referencia"},
    {key:"taxpayerDocument",header:"Documento"},
    {key:"amount",header:"Importe",align:"right",render:(row)=>formatCurrency(row.amount)},
    {key:"status",header:"Estado",render:(row)=><StatusBadge status={row.status}/>},
    {key:"actions",header:"",align:"right",render:(row)=><Button size="sm" variant="secondary" onClick={()=>setModal({type:"resolve",item:row})}>Resolver</Button>},
  ];
  return <Card title="Conciliación electrónica" description="Importá lotes del canal electrónico y resolvé movimientos observados." actions={<Button size="sm" variant="primary" onClick={()=>setModal({type:"import"})}>Importar lote</Button>}>
    {feedback&&<Alert variant={feedback.variant} title={feedback.title} onDismiss={()=>setFeedback(null)}>{feedback.message}</Alert>}
    <DataTable columns={columns} rows={observed??[]} rowKey={(row)=>row.id} loading={loading} emptyIconName="BadgeCheck" emptyTitle="Sin movimientos observados" emptyDescription="Los pagos importados conciliaron o todavía no se cargó un lote." />
    {modal?.type==="import"&&<ImportReconciliationModal onClose={()=>setModal(null)} onDone={(batch)=>{setModal(null);setFeedback({variant:"success",title:"Lote importado",message:`${batch.reconciledItems} conciliados, ${batch.observedItems} observados y ${batch.notFoundItems} no encontrados.`});reload();}}/>}
    {modal?.type==="resolve"&&<ResolveReconciliationModal item={modal.item} onClose={()=>setModal(null)} onDone={()=>{setModal(null);setFeedback({variant:"success",title:"Movimiento resuelto",message:"La conciliación manual quedó persistida y auditada."});reload();}}/>}
  </Card>;
}

function ImportReconciliationModal({onClose,onDone}){
  const [batchReference,setBatchReference]=useState("");const [payload,setPayload]=useState('[\n  {"externalReference":"TX-001","taxpayerDocument":"00000000","amount":1000,"paidAt":"2026-09-04T12:00:00-03:00"}\n]');const [error,setError]=useState(null);const [submitting,setSubmitting]=useState(false);
  const submit=async(event)=>{event.preventDefault();setError(null);try{const items=JSON.parse(payload);if(!batchReference.trim()||!Array.isArray(items)||items.length===0)throw new Error("Indicá la referencia y al menos un movimiento válido.");setSubmitting(true);onDone(await reconciliationService.importBatch(batchReference,items));}catch(caught){setError(caught.message);}finally{setSubmitting(false);}};
  return <Modal open title="Importar conciliación" description="El lote queda almacenado en PostgreSQL y no puede repetirse." onClose={onClose} footer={<><Button variant="secondary" onClick={onClose}>Cancelar</Button><Button variant="primary" loading={submitting} onClick={submit}>Importar</Button></>}>
    {error&&<Alert variant="error" title="No se pudo importar">{error}</Alert>}<form onSubmit={submit} className="flex flex-col gap-4"><FormField label="Referencia del lote" name="batchReference" value={batchReference} onChange={(e)=>setBatchReference(e.target.value)} required/><FormField label="Movimientos (JSON)" name="items" type="textarea" value={payload} onChange={(e)=>setPayload(e.target.value)} required/></form>
  </Modal>;
}

function ResolveReconciliationModal({item,onClose,onDone}){
  const [paymentId,setPaymentId]=useState("");const [reason,setReason]=useState("");const [error,setError]=useState(null);const [submitting,setSubmitting]=useState(false);
  const submit=async(event)=>{event.preventDefault();if(!paymentId||!reason.trim()){setError("Indicá el pago y el motivo de la resolución.");return;}setSubmitting(true);try{await reconciliationService.resolve(item.id,Number(paymentId),reason);onDone();}catch(caught){setError(caught.message);}finally{setSubmitting(false);}};
  return <Modal open title={`Resolver ${item.externalReference}`} description={`${item.taxpayerDocument} · ${formatCurrency(item.amount)}`} onClose={onClose} footer={<><Button variant="secondary" onClick={onClose}>Cancelar</Button><Button variant="primary" loading={submitting} onClick={submit}>Conciliar</Button></>}>
    {error&&<Alert variant="error" title="No se pudo resolver">{error}</Alert>}<form onSubmit={submit} className="flex flex-col gap-4"><FormField label="ID de pago" name="paymentId" type="number" value={paymentId} onChange={(e)=>setPaymentId(e.target.value)} required/><FormField label="Motivo" name="reason" type="textarea" value={reason} onChange={(e)=>setReason(e.target.value)} required/></form>
  </Modal>;
}

/** RegisterPaymentRequest → PaymentResponse */
function RegisterPaymentModal({ taxpayerOptions, onClose, onDone }) {
  const [form, setForm] = useState({
    taxpayerId: "",
    debtId: "",
    amountPaid: "",
    channel: "",
    paidAt: "",
  });
  const [errors, setErrors] = useState({});
  const [submitError, setSubmitError] = useState(null);
  const [submitting, setSubmitting] = useState(false);

  const loader = useCallback(
    () =>
      form.taxpayerId
        ? debtService.list({ taxpayerId: form.taxpayerId })
        : Promise.resolve([]),
    [form.taxpayerId],
  );
  const { data: debts } = useResource(loader, []);
  const pendingDebts = (debts ?? []).filter((d) => d.outstandingAmount > 0);
  const selectedDebt = pendingDebts.find((d) => d.id === Number(form.debtId));

  const onChange = (event) => {
    const { name, value } = event.target;
    setForm((previous) => ({
      ...previous,
      [name]: value,
      // Cambiar de contribuyente invalida la deuda elegida.
      ...(name === "taxpayerId" ? { debtId: "" } : {}),
    }));
    setErrors((previous) => ({ ...previous, [name]: undefined }));
  };

  const validate = () => {
    const found = {};
    if (!form.taxpayerId) found.taxpayerId = "Seleccioná el contribuyente.";
    if (!(Number(form.amountPaid) > 0)) found.amountPaid = "Ingresá un importe mayor a cero.";
    if (!form.channel) found.channel = "Indicá el canal de cobro.";
    if (selectedDebt && Number(form.amountPaid) > selectedDebt.outstandingAmount) {
      found.amountPaid = "El importe supera el saldo de la deuda.";
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
      onDone(await paymentService.register(form));
    } catch (caught) {
      setSubmitError(caught.message);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open
      title="Registrar pago"
      description="Si todavía no se identifica la deuda, dejá la imputación vacía."
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>
            Cancelar
          </Button>
          <Button variant="primary" loading={submitting} onClick={onSubmit}>
            Registrar
          </Button>
        </>
      }
    >
      {submitError && <Alert variant="error" title="No se pudo registrar">{submitError}</Alert>}

      <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4">
        <FormField
          label="Contribuyente"
          name="taxpayerId"
          type="select"
          value={form.taxpayerId}
          onChange={onChange}
          options={taxpayerOptions}
          error={errors.taxpayerId}
          required
        />
        <FormField
          label="Deuda a imputar"
          name="debtId"
          type="select"
          value={form.debtId}
          onChange={onChange}
          disabled={!form.taxpayerId}
          options={pendingDebts.map((debt) => ({
            value: String(debt.id),
            label: `#${debt.id} · ${debt.conceptCode} · ${formatCurrency(debt.outstandingAmount)}`,
          }))}
        />
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <FormField
            label="Importe"
            name="amountPaid"
            type="number"
            placeholder="50000.00"
            value={form.amountPaid}
            onChange={onChange}
            error={errors.amountPaid}
            required
          />
          <FormField
            label="Canal"
            name="channel"
            type="select"
            value={form.channel}
            onChange={onChange}
            options={CHANNELS}
            error={errors.channel}
            required
          />
        </div>
        <FormField
          label="Fecha de pago"
          name="paidAt"
          type="datetime-local"
          value={form.paidAt}
          onChange={onChange}
        />

        {selectedDebt && (
          <div className="rounded-lg border border-neutral-200 bg-neutral-50 p-4 text-[13px] text-neutral-600">
            Saldo de la deuda:{" "}
            <span className="font-semibold text-[#0F2C59]">
              {formatCurrency(selectedDebt.outstandingAmount)}
            </span>
            {Number(form.amountPaid) > 0 &&
              Number(form.amountPaid) <= selectedDebt.outstandingAmount && (
                <p className="mt-1">
                  Saldo restante tras el pago:{" "}
                  <span className="font-semibold text-neutral-800">
                    {formatCurrency(selectedDebt.outstandingAmount - Number(form.amountPaid))}
                  </span>
                </p>
              )}
          </div>
        )}
      </form>
    </Modal>
  );
}

/** AllocatePaymentRequest → PaymentAllocationResponse */
function AllocatePaymentModal({ payment, onClose, onDone }) {
  const loader = useCallback(
    () => debtService.list({ taxpayerId: payment.taxpayerId }),
    [payment.taxpayerId],
  );
  const { data: debts } = useResource(loader, []);
  const [debtId, setDebtId] = useState("");
  const [error, setError] = useState(null);
  const [submitting, setSubmitting] = useState(false);

  const eligible = (debts ?? []).filter((d) => d.outstandingAmount >= payment.amountPaid);

  const onSubmit = async (event) => {
    event.preventDefault();
    setError(null);
    if (!debtId) {
      setError("Seleccioná la deuda a la que corresponde el pago.");
      return;
    }
    setSubmitting(true);
    try {
      onDone(await paymentService.allocate({ paymentId: payment.id, debtId }));
    } catch (caught) {
      setError(caught.message);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open
      title={`Imputar ${payment.receiptNumber}`}
      description={`${formatCurrency(payment.amountPaid)} sin asignar a una deuda.`}
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>
            Cancelar
          </Button>
          <Button variant="primary" loading={submitting} onClick={onSubmit}>
            Imputar
          </Button>
        </>
      }
    >
      {error && <Alert variant="error" title="No se pudo imputar">{error}</Alert>}

      {eligible.length === 0 ? (
        <Alert variant="info" title="Sin deudas compatibles">
          Ninguna deuda del contribuyente tiene saldo suficiente para absorber este importe.
          Corresponde generar saldo a favor.
        </Alert>
      ) : (
        <form onSubmit={onSubmit} noValidate>
          <FormField
            label="Deuda"
            name="debtId"
            type="select"
            value={debtId}
            onChange={(event) => setDebtId(event.target.value)}
            required
            options={eligible.map((debt) => ({
              value: String(debt.id),
              label: `#${debt.id} · ${debt.conceptCode} · saldo ${formatCurrency(debt.outstandingAmount)}`,
            }))}
          />
        </form>
      )}
    </Modal>
  );
}

/** RequestPaymentReversalRequest → PaymentReversalResponse */
function ReversePaymentModal({ payment, onClose, onDone }) {
  const [reason, setReason] = useState("");
  const [error, setError] = useState(null);
  const [submitting, setSubmitting] = useState(false);

  const onSubmit = async (event) => {
    event.preventDefault();
    setError(null);
    if (!reason.trim()) {
      setError("El motivo de la reversión es obligatorio: viaja en el evento paymentReversed.");
      return;
    }
    setSubmitting(true);
    try {
      onDone(await paymentService.reverse({ paymentId: payment.id, reason }));
    } catch (caught) {
      setError(caught.message);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open
      title={`Reversar ${payment.receiptNumber}`}
      description={`${formatCurrency(payment.amountPaid)} volverán al saldo de la deuda.`}
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>
            Cancelar
          </Button>
          <Button variant="accent" loading={submitting} onClick={onSubmit}>
            Confirmar reversión
          </Button>
        </>
      }
    >
      {error && <Alert variant="error" title="No se pudo reversar">{error}</Alert>}

      <Alert variant="info" title="La operación notifica al módulo de origen">
        Se publicará <code className="font-semibold">paymentReversed</code> con originType{" "}
        <code className="font-semibold">{payment.originType}</code>
        {payment.originId ? ` y originId ${payment.originId}` : ""}.
      </Alert>

      <form onSubmit={onSubmit} noValidate>
        <FormField
          label="Motivo"
          name="reason"
          type="textarea"
          placeholder="Pago registrado por error"
          value={reason}
          onChange={(event) => setReason(event.target.value)}
          required
        />
      </form>
    </Modal>
  );
}
