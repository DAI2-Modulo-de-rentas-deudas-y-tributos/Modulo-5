import { useEffect, useState } from "react";
import { request } from "../services/apiClient.js";

/**
 * Configuración vigente de planes de pago.
 *
 * Las alternativas de cuotas salen de los límites que fija el backend
 * (`minimumInstallments`..`maximumInstallments`) y no de una lista fija: proponer
 * un plan de 18 cuotas cuando el máximo vigente es 12 se acepta en pantalla y
 * recién falla al enviarlo.
 *
 * Se cachea a nivel de módulo: la configuración no cambia durante una sesión.
 */
let cache = null;
let inFlight = null;

export async function loadPlanConfiguration() {
  if (cache) return cache;
  if (!inFlight) {
    inFlight = request("/api/v1/payment-plan-configurations")
      .then((rows) => {
        const filas = Array.isArray(rows) ? rows : (rows?.content ?? []);
        const activa = filas.find((fila) => fila.active) ?? filas[0] ?? null;
        cache = activa;
        return activa;
      })
      .finally(() => {
        inFlight = null;
      });
  }
  return inFlight;
}

/** Sólo para las pruebas: evita que la configuración de un caso se filtre al siguiente. */
export function resetPlanConfigurationCache() {
  cache = null;
  inFlight = null;
}

/**
 * Tres alternativas dentro del rango permitido: el mínimo, el máximo y un punto
 * intermedio. Cada una se simula por separado, así que la cantidad se mantiene baja.
 */
export function installmentChoicesOf(configuration) {
  if (!configuration) return [];
  const { minimumInstallments: min, maximumInstallments: max } = configuration;
  if (!Number.isInteger(min) || !Number.isInteger(max) || min > max) return [];
  return [...new Set([min, Math.round((min + max) / 2), max])];
}

export default function usePlanConfiguration() {
  const [configuration, setConfiguration] = useState(cache);
  const [loading, setLoading] = useState(!cache);
  const [error, setError] = useState(null);

  useEffect(() => {
    if (cache) return undefined;
    let active = true;
    loadPlanConfiguration()
      .then((rows) => active && setConfiguration(rows))
      .catch((caught) => active && setError(caught.message ?? "No se pudo cargar la configuración de planes."))
      .finally(() => active && setLoading(false));
    return () => {
      active = false;
    };
  }, []);

  return { configuration, installmentChoices: installmentChoicesOf(configuration), loading, error };
}
