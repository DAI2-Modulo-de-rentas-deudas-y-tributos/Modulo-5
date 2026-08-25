import { useCallback } from "react";
import { Printer } from "lucide-react";
import Modal from "../common/Modal.jsx";
import Button from "../common/Button.jsx";
import Alert from "../ui/Alert.jsx";
import Spinner from "../ui/Spinner.jsx";
import ReceiptCard, { printReceipt } from "./ReceiptCard.jsx";
import useResource from "../../hooks/useResource.js";
import { cashierService } from "../../services/rentasService.js";

/** Reimpresión del comprobante de un pago ya registrado. */
export default function ReceiptModal({ paymentId, onClose }) {
  const loader = useCallback(
    () => (paymentId ? cashierService.receipt(paymentId) : Promise.resolve(null)),
    [paymentId],
  );
  const { data: receipt, loading, error } = useResource(loader);

  if (!paymentId) return null;

  return (
    <Modal
      open
      title="Comprobante de pago"
      description="Reimpresión del comprobante entregado en ventanilla."
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>
            Cerrar
          </Button>
          <Button variant="primary" onClick={printReceipt} disabled={!receipt}>
            <Printer className="h-4 w-4" strokeWidth={2} />
            Imprimir
          </Button>
        </>
      }
    >
      {error && (
        <Alert variant="error" title="No pudimos abrir el comprobante">
          {error}
        </Alert>
      )}
      {loading ? (
        <div className="flex items-center justify-center gap-3 py-8">
          <Spinner />
          <span className="text-[13px] text-neutral-400">Buscando el comprobante…</span>
        </div>
      ) : (
        <ReceiptCard receipt={receipt} />
      )}
    </Modal>
  );
}
