import { useCallback, useEffect, useRef, useState } from "react";

/** Una operación por vez; el cerrojo se toma antes del siguiente render. */
export default function useAsyncAction() {
  const inFlight = useRef(null);
  const mounted = useRef(true);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState(null);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  const run = useCallback((action) => {
    if (inFlight.current) return inFlight.current;
    setPending(true);
    setError(null);
    const operation = Promise.resolve().then(action).catch((caught) => {
      if (mounted.current) setError(caught.message ?? "No se pudo completar la operación.");
      throw caught;
    }).finally(() => {
      inFlight.current = null;
      if (mounted.current) setPending(false);
    });
    inFlight.current = operation;
    return operation;
  }, []);
  return { run, pending, error, clearError: useCallback(() => setError(null), []) };
}
