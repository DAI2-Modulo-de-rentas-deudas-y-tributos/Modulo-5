import { useCallback } from "react";
import Modal from "./Modal.jsx";
import { ErrorState, LoadingState } from "./ResourceState.jsx";
import StatusBadge from "./StatusBadge.jsx";
import DebtOriginDetails from "./DebtOriginDetails.jsx";
import useResource from "../../hooks/useResource.js";
import { debtService } from "../../services/domains/debts.js";
import { formatCurrency, formatDate } from "../../lib/format.js";

export default function DebtDetailModal({ id, onClose }) {
  const loader = useCallback(() => debtService.detail(id), [id]);
  const { data, loading, error, reload } = useResource(loader);
  return <Modal open title={`Deuda #${id}`} onClose={onClose}>
    {loading ? <LoadingState message="Cargando la deuda…" /> : error ? <ErrorState error={error} onRetry={reload} /> : data && <>
      <StatusBadge status={data.status} />
      <dl className="grid grid-cols-2 gap-2 text-sm">
        <dt>Importe original</dt><dd>{formatCurrency(data.originalAmount)}</dd>
        <dt>Saldo pendiente</dt><dd>{formatCurrency(data.outstandingAmount)}</dd>
        <dt>Vencimiento</dt><dd>{formatDate(data.dueDate)}</dd>
      </dl>
      <DebtOriginDetails debt={data} />
    </>}
  </Modal>;
}
