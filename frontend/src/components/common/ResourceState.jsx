import Alert from "../ui/Alert.jsx";
import Spinner from "../ui/Spinner.jsx";
import Button from "./Button.jsx";

export function LoadingState({ message = "Cargando información…" }) {
  return <div role="status" aria-live="polite" className="flex items-center justify-center gap-3 px-5 py-16">
    <span aria-hidden="true"><Spinner /></span><span className="text-[13px] text-neutral-500">{message}</span>
  </div>;
}

export function ErrorState({ error, onRetry }) {
  return <div className="p-5">
    <Alert variant="error" title="No pudimos cargar la información">{error instanceof Error ? error.message : error}</Alert>
    {onRetry && <Button className="mt-3" variant="secondary" onClick={onRetry}>Reintentar</Button>}
  </div>;
}
