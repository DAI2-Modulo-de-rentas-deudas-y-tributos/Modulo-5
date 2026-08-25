import { useCallback, useEffect, useState } from "react";

/**
 * Carga asincrónica con estados de loading/error y recarga manual.
 * `loader` debe ser estable (useCallback en el componente).
 */
export default function useResource(loader, initialData = null) {
  const [data, setData] = useState(initialData);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const reload = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setData(await loader());
    } catch (caught) {
      setError(caught.message ?? "No se pudo cargar la información.");
    } finally {
      setLoading(false);
    }
  }, [loader]);

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError(null);
    loader()
      .then((result) => active && setData(result))
      .catch((caught) => active && setError(caught.message ?? "No se pudo cargar la información."))
      .finally(() => active && setLoading(false));
    return () => {
      active = false;
    };
  }, [loader]);

  return { data, loading, error, reload, setData };
}
