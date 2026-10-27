import Alert from "../ui/Alert.jsx";
import { labelFor } from "../../lib/format.js";

export default function DebtOriginDetails({ debt }) {
  return <div className="space-y-3 text-sm">
    <dl className="grid gap-2 sm:grid-cols-2">
      <dt className="text-neutral-500">Origen</dt><dd>{labelFor(debt.originType)}</dd>
      <dt className="text-neutral-500">Referencia de origen</dt><dd>{debt.source?.externalReferenceId ?? debt.originId ?? "—"}</dd>
      {debt.source?.sourceModule && <><dt className="text-neutral-500">Módulo de origen</dt><dd>{debt.source.sourceModule}</dd></>}
    </dl>
    {debt.status === "CANCELLED" && <Alert variant="info" title="Deuda anulada">
      {debt.cancellationReason ?? debt.source?.cancellationReason ?? "El motivo de anulación no fue informado por el sistema."}
    </Alert>}
    {debt.sourceError && <p className="text-neutral-500">No se pudo consultar el origen: {debt.sourceError}</p>}
  </div>;
}
