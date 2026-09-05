import { useEffect, useState } from "react";
import { taxConfigService } from "../services/rentasService.js";

/**
 * Conceptos tributarios que el módulo tiene dados de alta.
 *
 * Los combos de las pantallas operativas se arman con esto y no con una lista fija:
 * un código hardcodeado que el backend no conoce se elige sin error y recién falla
 * al registrar la operación ("Concepto inexistente").
 *
 * Se cachea a nivel de módulo, igual que el padrón: el catálogo cambia poco y no
 * justifica una consulta por pantalla.
 */
let cache = null;

export default function useTaxConcepts() {
  const [concepts, setConcepts] = useState(cache ?? []);
  const [loading, setLoading] = useState(!cache);
  const [error, setError] = useState(null);

  useEffect(() => {
    if (cache) return undefined;
    let active = true;
    taxConfigService
      .concepts()
      .then((rows) => {
        cache = rows;
        if (active) setConcepts(rows);
      })
      .catch((caught) => {
        if (active) setError(caught.message ?? "No se pudieron cargar los conceptos.");
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, []);

  return {
    concepts,
    loading,
    error,
    /** Nombre del concepto, o su código si todavía no llegó el catálogo. */
    labelOf: (code) => concepts.find((c) => c.code === code)?.name ?? code,
    options: concepts.map((c) => ({ value: c.code, label: c.name })),
  };
}

/** Sólo para las pruebas: evita que el catálogo de un caso se filtre al siguiente. */
export function resetTaxConceptsCache() {
  cache = null;
}
