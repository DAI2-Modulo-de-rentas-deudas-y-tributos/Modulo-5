import { taxConfigService } from "../services/rentasService.js";
import { createSessionLookup } from "../services/lookupCache.js";
import useLookupResource from "./useLookupResource.js";

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
const lookup = createSessionLookup(() => taxConfigService.concepts());

export default function useTaxConcepts() {
  const { data, loading, error, reload } = useLookupResource(lookup, []);
  const concepts = data ?? [];

  return {
    concepts,
    loading,
    error,
    reload,
    /** Nombre del concepto, o su código si todavía no llegó el catálogo. */
    labelOf: (code) => concepts.find((c) => c.code === code)?.name ?? code,
    options: concepts.map((c) => ({ value: c.code, label: c.name })),
  };
}

/** Sólo para las pruebas: evita que el catálogo de un caso se filtre al siguiente. */
export function resetTaxConceptsCache() {
  lookup.clear();
}
