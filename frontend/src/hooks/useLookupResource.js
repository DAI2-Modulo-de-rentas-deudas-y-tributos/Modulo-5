import { useCallback } from "react";
import useResource from "./useResource.js";

/** Comparte la carga inicial y fuerza una consulta al servidor al recargar. */
export default function useLookupResource(lookup, initialData) {
  const resource = useResource(lookup.load, initialData);
  const reloadResource = resource.reload;
  const reload = useCallback(() => {
    lookup.clear();
    return reloadResource();
  }, [lookup, reloadResource]);
  return { ...resource, reload };
}
