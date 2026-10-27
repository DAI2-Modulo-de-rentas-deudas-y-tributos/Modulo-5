import { useCallback, useEffect, useRef, useState } from "react";

/**
 * Carga asincrónica con estados de loading/error y recarga manual.
 * `loader` debe ser estable (useCallback en el componente).
 */
export default function useResource(loader, initialData = null) {
  const [data, setData] = useState(initialData);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const generation = useRef(0);

  const reload = useCallback(async () => {
    const current = ++generation.current;
    setLoading(true);
    setError(null);
    try {
      const result = await loader();
      if (current === generation.current) setData(result);
      return result;
    } catch (caught) {
      if (current === generation.current && caught.name !== "AbortError") setError(caught.message ?? "No se pudo cargar la información.");
      return null;
    } finally {
      if (current === generation.current) setLoading(false);
    }
  }, [loader]);

  useEffect(() => {
    reload();
    return () => { ++generation.current; };
  }, [reload]);

  return { data, loading, error, reload, setData };
}
