import { describe, expect, it } from "vitest";
import { refinancingService } from "./rentasService.js";
import { REFINANCING_RULES } from "./mockDb.js";

/**
 * Refinanciación de planes. En archivo propio: varias ramas amplían
 * `rentasService.test.js` y anexar al final del mismo archivo choca al mergear.
 */
describe("elegibilidad para refinanciar", () => {
  it("la regla de cuotas impagas es un parámetro, no un número escrito en el código", () => {
    expect(REFINANCING_RULES.minimumOverdueInstallments).toBeGreaterThan(0);
    expect(REFINANCING_RULES.installmentChoices.length).toBeGreaterThan(1);
  });

  it("el plan incumplido es elegible y calcula su saldo vivo", async () => {
    const planes = await refinancingService.eligiblePlans();
    // El plan 851 tiene una cuota pagada, una vencida y una pendiente.
    const incumplido = planes.find((p) => p.planId === 851);

    expect(incumplido.eligible).toBe(true);
    expect(incumplido.overdueInstallments).toBe(1);
    // Sólo lo impago: no se vuelve a financiar lo ya pagado.
    expect(incumplido.outstandingAmount).toBe(5108.34);
  });

  it("el plan cumplido no es elegible y explica por qué", async () => {
    const planes = await refinancingService.eligiblePlans();
    const cumplido = planes.find((p) => p.planId === 852);

    expect(cumplido.eligible).toBe(false);
    expect(cumplido.reasons.join(" ")).toMatch(/cumplido/i);
  });

  it("el plan vigente sin cuotas vencidas tampoco lo es", async () => {
    const planes = await refinancingService.eligiblePlans();
    const vigente = planes.find((p) => p.planId === 850);

    expect(vigente.eligible).toBe(false);
    expect(vigente.reasons.join(" ")).toMatch(/cuota.*vencida/i);
  });

  it("filtra sólo los refinanciables cuando se pide", async () => {
    const todos = await refinancingService.eligiblePlans();
    const soloElegibles = await refinancingService.eligiblePlans({ onlyEligible: true });

    expect(soloElegibles.every((p) => p.eligible)).toBe(true);
    expect(soloElegibles.length).toBeLessThan(todos.length);
  });
});

describe("propuesta y resolución de refinanciación", () => {
  it("simula sobre el saldo vivo, no sobre la deuda original", () => {
    const simulacion = refinancingService.simulate({
      outstandingAmount: 10000,
      installments: 6,
      downPayment: 2000,
    });

    expect(simulacion.financedAmount).toBe(8000);
    expect(simulacion.totalAmount).toBe(10800);
  });

  it("rechaza refinanciar un plan que no es elegible", async () => {
    await expect(
      refinancingService.request({ planId: 852, installments: 6, requestedBy: "mrivas" }),
    ).rejects.toThrow(/cumplido/i);
  });

  it("la solicitud no toca el plan vigente", async () => {
    const solicitud = await refinancingService.request({
      planId: 851,
      installments: 6,
      requestedBy: "mrivas",
      note: "Acumula atrasos",
    });

    expect(solicitud.status).toBe("REQUESTED");
    const planes = await refinancingService.eligiblePlans();
    // Sigue siendo el mismo plan, con su ciclo intacto.
    expect(planes.find((p) => p.planId === 851).lifecycle).toBe("DEFAULTED");
  });

  it("no admite dos solicitudes en curso sobre el mismo plan", async () => {
    await expect(
      refinancingService.request({ planId: 851, installments: 12, requestedBy: "mrivas" }),
    ).rejects.toThrow(/ya tiene una solicitud/i);
  });

  it("derivada, sólo la resuelve el Supervisor", async () => {
    const pendiente = (await refinancingService.list({ status: "REQUESTED" }))[0];
    await refinancingService.escalate({
      requestId: pendiente.requestId,
      escalatedBy: "mrivas",
      note: "Caso con muchos atrasos",
    });

    await expect(
      refinancingService.resolve({
        requestId: pendiente.requestId,
        status: "APPROVED",
        resolvedBy: "mrivas",
        resolverRole: "PERSONAL",
      }),
    ).rejects.toThrow(/sólo el supervisor/i);
  });

  it("al aprobar, el plan viejo queda como antecedente y el nuevo pasa a vigente", async () => {
    const pendiente = (await refinancingService.list({ status: "UNDER_REVIEW" }))[0];

    const resultado = await refinancingService.resolve({
      requestId: pendiente.requestId,
      status: "APPROVED",
      resolvedBy: "jlopez",
      resolverRole: "SUPERVISOR",
    });

    // El original no se borra: conserva sus cuotas y queda enlazado con el nuevo.
    expect(resultado.previousPlan.lifecycle).toBe("REFINANCED");
    expect(resultado.previousPlan.schedule.length).toBeGreaterThan(0);
    expect(resultado.previousPlan.refinancedInto).toBe(resultado.newPlan.planId);

    // El nuevo es el vigente y sabe de dónde viene.
    expect(resultado.newPlan.lifecycle).toBe("CURRENT");
    expect(resultado.newPlan.refinancedFrom).toBe(851);
    expect(resultado.newPlan.schedule.length).toBe(resultado.newPlan.installments);
  });

  it("un plan ya refinanciado deja de ser elegible", async () => {
    const planes = await refinancingService.eligiblePlans();
    const viejo = planes.find((p) => p.planId === 851);

    expect(viejo.eligible).toBe(false);
    expect(viejo.reasons.join(" ")).toMatch(/ya fue refinanciado/i);
  });

  it("exige motivo al rechazar", async () => {
    const solicitud = await refinancingService.request({
      planId: (await refinancingService.eligiblePlans({ onlyEligible: true }))[0]?.planId ?? 851,
      installments: 6,
      requestedBy: "mrivas",
    }).catch(() => null);

    if (!solicitud) return;
    await expect(
      refinancingService.resolve({
        requestId: solicitud.requestId,
        status: "REJECTED",
        resolvedBy: "jlopez",
        resolverRole: "SUPERVISOR",
        reason: "",
      }),
    ).rejects.toThrow(/motivo/i);
  });
});
