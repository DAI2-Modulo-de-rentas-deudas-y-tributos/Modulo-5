import { afterEach, describe, expect, it, vi } from "vitest";
import { refinancingService } from "./rentasService.js";
import { installmentChoicesOf } from "../hooks/usePlanConfiguration.js";
import { instalarBackendFalso, pagina } from "../__tests__/fixtures/backendFalso.js";

/**
 * Refinanciación de planes. En archivo propio: varias ramas amplían
 * `rentasService.test.js` y anexar al final del mismo archivo choca al mergear.
 *
 * La elegibilidad la decide el backend por el estado del plan; el frontend no la
 * recalcula, sólo la muestra y explica por qué un plan no se puede refinanciar.
 */

/** El backend nombra el saldo `outstandingPlanAmount`; el adaptador lo renombra. */
const plan = (id, status, outstandingPlanAmount, extra = {}) => ({
  id, status, outstandingPlanAmount, installmentCount: 6, totalPlanAmount: 137500,
  configurationId: 1, refinancingCount: 0, ...extra,
});

const CONFIG_PERMITE = { id: 1, refinancingAllowed: true, maxRefinancingCount: 2, minimumInstallments: 3, maximumInstallments: 12 };
const CONFIG_PROHIBE = { id: 1, refinancingAllowed: false, maxRefinancingCount: 0, minimumInstallments: 3, maximumInstallments: 12 };

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("alternativas de cuotas", () => {
  it("salen de los límites que fija el backend, no de una lista escrita en el código", () => {
    expect(installmentChoicesOf({ minimumInstallments: 3, maximumInstallments: 12 })).toEqual([3, 8, 12]);
  });

  it("no ofrece nada mientras la configuración no llegó", () => {
    expect(installmentChoicesOf(null)).toEqual([]);
  });

  it("descarta un rango incoherente en vez de ofrecer cuotas inválidas", () => {
    expect(installmentChoicesOf({ minimumInstallments: 12, maximumInstallments: 3 })).toEqual([]);
  });
});

describe("elegibilidad para refinanciar", () => {
  it("el plan activo cuya configuración lo permite es elegible", async () => {
    instalarBackendFalso({
      "GET /api/v1/payment-plans": pagina([plan(851, "ACTIVE", 5108.34)]),
      "GET /api/v1/payment-plan-configurations/{id}": CONFIG_PERMITE,
    });

    const [activo] = await refinancingService.eligiblePlans();

    expect(activo.eligible).toBe(true);
    expect(activo.reasons).toEqual([]);
  });

  it("un plan que no está activo no lo es, y lo explica", async () => {
    instalarBackendFalso({
      "GET /api/v1/payment-plans": pagina([plan(850, "COMPLETED", 0)]),
    });

    const [cerrado] = await refinancingService.eligiblePlans();

    expect(cerrado.eligible).toBe(false);
    expect(cerrado.reasons.join(" ")).toMatch(/activos/i);
  });

  it("una configuración que prohíbe refinanciar bloquea el plan", async () => {
    instalarBackendFalso({
      "GET /api/v1/payment-plans": pagina([plan(851, "ACTIVE", 5108.34)]),
      "GET /api/v1/payment-plan-configurations/{id}": CONFIG_PROHIBE,
    });

    const [bloqueado] = await refinancingService.eligiblePlans();

    expect(bloqueado.eligible).toBe(false);
    expect(bloqueado.reasons.join(" ")).toMatch(/no permite refinanciar/i);
  });

  it("un plan que agotó sus refinanciaciones tampoco es elegible", async () => {
    instalarBackendFalso({
      "GET /api/v1/payment-plans": pagina([plan(851, "ACTIVE", 5108.34, { refinancingCount: 2 })]),
      "GET /api/v1/payment-plan-configurations/{id}": CONFIG_PERMITE,
    });

    const [agotado] = await refinancingService.eligiblePlans();

    expect(agotado.eligible).toBe(false);
    expect(agotado.reasons.join(" ")).toMatch(/máximo de refinanciaciones/i);
  });

  it("filtra sólo los refinanciables cuando se pide", async () => {
    instalarBackendFalso({
      "GET /api/v1/payment-plans": pagina([plan(850, "COMPLETED", 0), plan(851, "ACTIVE", 5108.34)]),
      "GET /api/v1/payment-plan-configurations/{id}": CONFIG_PERMITE,
    });

    const todos = await refinancingService.eligiblePlans();
    const soloElegibles = await refinancingService.eligiblePlans({ onlyEligible: true });

    expect(todos).toHaveLength(2);
    expect(soloElegibles).toHaveLength(1);
    expect(soloElegibles[0].outstandingAmount).toBe(5108.34);
  });
});

describe("propuesta de refinanciación", () => {
  it("simula sobre el saldo vivo, no sobre la deuda original", () => {
    const simulacion = refinancingService.simulate({
      outstandingAmount: 10000,
      installments: 6,
      downPayment: 2000,
    });

    expect(simulacion.financedAmount).toBe(8000);
  });

  it("pide la refinanciación contra el plan, sin tocarlo", async () => {
    const backend = instalarBackendFalso({
      "POST /api/v1/payment-plans/{id}/refinancing-requests": { id: 900, status: "REQUESTED" },
    });

    const solicitud = await refinancingService.request({ planId: 851, installments: 6, requestedBy: "mrivas" });

    expect(solicitud.status).toBe("REQUESTED");
    const llamada = backend.llamadas.find((l) => l.metodo === "POST");
    expect(llamada.ruta).toBe("/api/v1/payment-plans/851/refinancing-requests");
    expect(llamada.cuerpo).toEqual({ installments: 6 });
  });
});
