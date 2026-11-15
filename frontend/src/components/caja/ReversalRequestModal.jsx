import { useState } from "react";
import Modal from "../common/Modal.jsx";
import Button from "../common/Button.jsx";
import Alert from "../ui/Alert.jsx";
import FormField from "../ui/FormField.jsx";
import { paymentService } from "../../services/rentasService.js";
import { formatCurrency, formatDateTime } from "../../lib/format.js";

/** El Cajero documenta el error; la solicitud no altera todavía el pago ni la deuda. */
export default function ReversalRequestModal({ payment, onClose, onDone }) {
  const [reason, setReason] = useState("");
  const [confirmed, setConfirmed] = useState(false);
  const [error, setError] = useState(null);
  const [submitting, setSubmitting] = useState(false);

  if (!payment) return null;

  const onSubmit = async (event) => {
    event.preventDefault();
    setError(null);
    if (reason.trim().length < 10) {
      setError("Describí el error con al menos 10 caracteres.");
      return;
    }
    if (!confirmed) {
      setError("Confirmá que querés enviar la solicitud al Supervisor.");
      return;
    }

    setSubmitting(true);
    try {
      onDone(await paymentService.requestReversal({ paymentId: payment.id, reason: reason.trim() }));
    } catch (caught) {
      setError(caught.message);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open
      title={`Solicitar reversión de ${payment.receiptNumber ?? `pago #${payment.id}`}`}
      description={`${formatCurrency(payment.amountPaid ?? payment.amount)} · ${formatDateTime(payment.paidAt)}`}
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>Cancelar</Button>
          <Button variant="danger" loading={submitting} onClick={onSubmit}>
            Enviar solicitud
          </Button>
        </>
      }
    >
      <Alert variant="info" title="El pago no se modifica ahora">
        La solicitud quedará pendiente de aprobación. El pago, sus imputaciones y la deuda
        conservarán su estado actual hasta que el circuito de reversión se complete.
      </Alert>

      {error && <Alert variant="error" title="No se pudo solicitar la reversión">{error}</Alert>}

      <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4">
        <FormField
          label="Motivo de la solicitud"
          name="reversalReason"
          type="textarea"
          placeholder="Por ejemplo: el importe fue cobrado dos veces por error."
          value={reason}
          onChange={(event) => setReason(event.target.value)}
          required
        />
        <label className="flex cursor-pointer items-start gap-3 rounded-lg border border-neutral-200 bg-neutral-50 px-4 py-3 text-[13px] text-neutral-600">
          <input
            type="checkbox"
            checked={confirmed}
            onChange={(event) => setConfirmed(event.target.checked)}
            className="mt-0.5 h-4 w-4 accent-[#0F2C59]"
          />
          <span>
            Confirmo que revisé el comprobante y quiero enviar esta solicitud al Supervisor.
          </span>
        </label>
      </form>
    </Modal>
  );
}
