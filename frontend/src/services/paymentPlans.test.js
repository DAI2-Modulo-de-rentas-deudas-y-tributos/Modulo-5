import { afterEach, describe, expect, it, vi } from "vitest";
import { paymentPlanService, portalService } from "./rentasService.js";
import { instalarBackendFalso, pagina } from "../__tests__/fixtures/backendFalso.js";

/**
 * Solicitudes de plan de pago. Quién puede resolver y con qué condiciones lo decide
 * el backend; el cliente traduce la resolución al endpoint correcto y arma el cuerpo
 * que cada uno espera, que no es el mismo para otorgar y para rechazar.
 */

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("resolución de la solicitud", () => {
  it("otorgar y rechazar son operaciones distintas con cuerpos distintos", async () => {
    const otorgar = instalarBackendFalso({
      "POST /api/v1/payment-plan-requests/{id}/grant": { id: 800, status: "GRANTED" },
    });
    await paymentPlanService.resolve({ requestId: 800, status: "GRANTED", installments: 6, resolvedBy: "jlopez" });
    expect(otorgar.llamadas[0].ruta).toBe("/api/v1/payment-plan-requests/800/grant");
    expect(otorgar.llamadas[0].cuerpo).toBeNull();
    vi.unstubAllGlobals();

    const rechazar = instalarBackendFalso({
      "POST /api/v1/payment-plan-requests/{id}/reject": { id: 800, status: "REJECTED" },
    });
    await paymentPlanService.resolve({ requestId: 800, status: "REJECTED", reason: "No cumple las condiciones", resolvedBy: "jlopez" });
    expect(rechazar.llamadas[0].ruta).toBe("/api/v1/payment-plan-requests/800/reject");
    expect(rechazar.llamadas[0].cuerpo).toEqual({ reason: "No cumple las condiciones" });
  });
});

describe("derivación al Supervisor", () => {
  it("viaja como excepción con su motivo", async () => {
    const backend = instalarBackendFalso({
      "POST /api/v1/payment-plan-requests/{id}/submit-exception": { id: 800, exceptional: true },
    });

    await paymentPlanService.escalate({ requestId: 800, escalatedBy: "mrivas", note: "Supera el máximo de cuotas" });

    expect(backend.llamadas[0].ruta).toBe("/api/v1/payment-plan-requests/800/submit-exception");
    expect(backend.llamadas[0].cuerpo).toEqual({ reason: "Supera el máximo de cuotas" });
  });
});

describe("listado", () => {
  it("muestra el ciclo real de un plan refinanciado", async () => {
    instalarBackendFalso({
      "GET /api/v1/taxpayers/{id}/payment-plan-requests": pagina([{
        id: 800, taxpayerId: 123, status: "GRANTED", paymentPlanId: 850,
        totalDebtAtRequest: 120000, requestedInstallments: 6,
      }]),
      "GET /api/v1/payment-plans/{id}": {
        id: 850, taxpayerId: 123, status: "REFINANCED", installmentCount: 6,
        downPaymentAmount: 0, financedPrincipalAmount: 120000,
        financingInterestAmount: 0, totalPlanAmount: 120000,
      },
      "GET /api/v1/payment-plans/{id}/installments": [],
      "GET /api/v1/payment-plan-requests/{id}/debts": [{
        id: 3001, taxConceptId: 2, conceptName: "Alumbrado, barrido y limpieza", balanceAtRequest: 120000,
      }],
    });

    const [plan] = await portalService.paymentPlans({ taxpayerId: 123 });
    expect(plan).toMatchObject({ lifecycle: "REFINANCED", debtIds: [3001] });
  });

  it("lee las solicitudes desde el recurso real", async () => {
    const backend = instalarBackendFalso({
      "GET /api/v1/payment-plan-requests": pagina([
        { id: 800, taxpayerId: 123, status: "PENDING", totalDebtAtRequest: 200000, requestedInstallments: 6 },
      ]),
    });

    const solicitudes = await paymentPlanService.list({ status: "PENDING" });

    expect(backend.llamadas[0].ruta).toBe("/api/v1/payment-plan-requests");
    expect(solicitudes).toHaveLength(1);
    expect(solicitudes[0]).toMatchObject({ requestId: 800, totalDebt: 200000, installments: 6 });
  });

  it("no inventa solicitudes cuando el backend no devuelve ninguna", async () => {
    instalarBackendFalso({ "GET /api/v1/payment-plan-requests": pagina([]) });

    expect(await paymentPlanService.list()).toEqual([]);
  });
});
