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

const plan = (id, status, outstandingAmount) => ({ id, planId: id, status, lifecycle: status, outstandingAmount });

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
  it("el plan vencido es elegible", async () => {
    instalarBackendFalso({
      "GET /api/v1/payment-plan-requests": pagina([plan(851, "EXPIRED", 5108.34)]),
    });

    const [vencido] = await refinancingService.eligiblePlans();

    expect(vencido.eligible).toBe(true);
    expect(vencido.reasons).toEqual([]);
    expect(vencido.outstandingAmount).toBe(5108.34);
  });

  it("el plan vigente no lo es y explica por qué", async () => {
    instalarBackendFalso({
      "GET /api/v1/payment-plan-requests": pagina([plan(850, "ACTIVE", 9000)]),
    });

    const [vigente] = await refinancingService.eligiblePlans();

    expect(vigente.eligible).toBe(false);
    expect(vigente.reasons.join(" ")).toMatch(/vencido/i);
  });

  it("filtra sólo los refinanciables cuando se pide", async () => {
    instalarBackendFalso({
      "GET /api/v1/payment-plan-requests": pagina([plan(850, "ACTIVE", 9000), plan(851, "EXPIRED", 5108.34)]),
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
