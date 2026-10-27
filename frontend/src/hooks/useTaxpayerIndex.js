import { useMemo } from "react";
import { taxpayerService } from "../services/rentasService.js";
import { createSessionLookup } from "../services/lookupCache.js";
import useLookupResource from "./useLookupResource.js";

const lookup = createSessionLookup(() => taxpayerService.search());

export default function useTaxpayerIndex() {
  const { data, loading, error, reload } = useLookupResource(lookup, []);
  const index = useMemo(() => Object.fromEntries((data ?? []).map((taxpayer) => [taxpayer.id, taxpayer])), [data]);

  return {
    index,
    loading, error, reload,
    /** Nombre del contribuyente o su referencia lógica si todavía no llegó de M1. */
    nameOf: (id) => index[id]?.name ?? `Contribuyente #${id}`,
    options: Object.values(index).map((t) => ({
      value: String(t.id),
      label: `${t.name} — ${t.document}`,
    })),
  };
}
