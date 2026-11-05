import Alert from "../ui/Alert.jsx";
import { labelFor } from "../../lib/format.js";

export default function DebtOriginDetails({ debt, showOriginType = true }) {
  const reason = [debt.cancellationReason, debt.source?.cancellationReason]
    .find((value) => typeof value === "string" && value.trim());
  return <div className="flex flex-col gap-3">
    <dl className="grid grid-cols-1 gap-2 text-sm sm:grid-cols-2">
      {showOriginType && <div><dt className="text-neutral-500">Origen</dt><dd>{labelFor(debt.originType)}</dd></div>}
      <div><dt className="text-neutral-500">Referencia de origen</dt><dd>{debt.source?.externalReferenceId ?? debt.originId ?? "—"}</dd></div>
      {debt.source?.sourceModule && <div><dt className="text-neutral-500">Módulo de origen</dt><dd>{debt.source.sourceModule}</dd></div>}
    </dl>
    {debt.status === "CANCELLED" && <Alert variant="info" title="Deuda anulada">
      {reason ?? "El motivo de anulación no fue informado."}
    </Alert>}
    {debt.sourceError && <Alert variant="info" title="Origen no disponible">{debt.sourceError}</Alert>}
  </div>;
}
