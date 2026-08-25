import { useEffect, useState } from "react";
import { taxpayerService } from "../services/rentasService.js";

/**
 * Padrón local cacheado: los listados muestran el nombre del contribuyente
 * sin repetir una consulta por fila.
 */
let cache = null;

export default function useTaxpayerIndex() {
  const [index, setIndex] = useState(cache ?? {});

  useEffect(() => {
    if (cache) return undefined;
    let active = true;
    taxpayerService.search().then((taxpayers) => {
      cache = Object.fromEntries(taxpayers.map((t) => [t.id, t]));
      if (active) setIndex(cache);
    });
    return () => {
      active = false;
    };
  }, []);

  return {
    index,
    /** Nombre del contribuyente o su referencia lógica si todavía no llegó de M1. */
    nameOf: (id) => index[id]?.name ?? `Contribuyente #${id}`,
    options: Object.values(index).map((t) => ({
      value: String(t.id),
      label: `${t.name} — ${t.document}`,
    })),
  };
}
