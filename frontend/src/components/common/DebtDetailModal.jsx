import { useCallback } from "react";
import Modal from "./Modal.jsx";
import Button from "./Button.jsx";
import StatusBadge from "./StatusBadge.jsx";
import DebtOriginDetails from "./DebtOriginDetails.jsx";
import Alert from "../ui/Alert.jsx";
import Spinner from "../ui/Spinner.jsx";
import useResource from "../../hooks/useResource.js";
import { debtDetailsService } from "../../services/debtDetailsService.js";
import { formatCurrency, formatDate } from "../../lib/format.js";

export default function DebtDetailModal({ debtId, includeExternalOrigin = false, onClose }) {
  const loader = useCallback(() => debtDetailsService.detail(debtId, { includeExternalOrigin }), [debtId, includeExternalOrigin]);
  const { data: debt, loading, error, reload } = useResource(loader);
  return <Modal open title={`Deuda #${debtId}`} onClose={onClose} footer={<Button variant="secondary" onClick={onClose}>Cerrar</Button>}>
    {loading ? <div role="status" className="flex items-center gap-3"><Spinner />Cargando deuda…</div>
      : error ? <Alert variant="error" title="No se pudo cargar la deuda"><p>{error}</p><Button variant="secondary" onClick={reload}>Reintentar</Button></Alert>
        : debt && <div className="flex flex-col gap-5">
          <StatusBadge status={debt.status} />
          <dl className="grid grid-cols-2 gap-3 text-sm">
            <div><dt className="text-neutral-500">Importe original</dt><dd>{formatCurrency(debt.originalAmount)}</dd></div>
            <div><dt className="text-neutral-500">Saldo pendiente</dt><dd>{formatCurrency(debt.outstandingAmount)}</dd></div>
            <div><dt className="text-neutral-500">Vencimiento</dt><dd>{formatDate(debt.dueDate)}</dd></div>
          </dl>
          <DebtOriginDetails debt={debt} />
        </div>}
  </Modal>;
}
