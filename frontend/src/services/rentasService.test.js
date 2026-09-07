import { afterEach, describe, expect, it, vi } from "vitest";
import {
  auditService,
  administrationService,
  cashierService,
  debtAdjustmentService,
  debtService,
  exemptionService,
  paymentPlanService,
  paymentService,
  portalService,
} from "./rentasService.js";
import { instalarBackendFalso, pagina } from "../__tests__/fixtures/backendFalso.js";

/**
 * Comportamiento del frontend frente al backend.
 *
 * Las reglas de negocio —imputar un pago, cancelar una deuda, calcular un recargo—
 * las decide y las prueba el backend. Acá se verifica lo que es responsabilidad del
 * cliente: qué request arma, qué valida antes de enviarlo, qué hace con la respuesta
 * y qué operaciones declara no soportar en vez de simularlas.
 */

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("simulación de planes de pago", () => {
  // El cálculo es del cliente: la pantalla muestra alternativas antes de enviar nada.
  it("suma 5% de interés por cada tramo de tres cuotas", () => {
    expect(paymentPlanService.simulate({ totalDebt: 100000, installments: 3 })).toMatchObject({
      interestRate: 0.05,
      totalAmount: 105000,
    });
    expect(paymentPlanService.simulate({ totalDebt: 100000, installments: 6 }).totalAmount).toBe(110000);
  });

  it("descuenta el anticipo de la base financiada y abarata la cuota", () => {
    const sinAnticipo = paymentPlanService.simulate({ totalDebt: 100000, installments: 6 });
    const conAnticipo = paymentPlanService.simulate({ totalDebt: 100000, installments: 6, downPayment: 40000 });

    expect(conAnticipo.financedAmount).toBe(60000);
    // El interés se calcula sobre lo financiado, no sobre la deuda entera.
    expect(conAnticipo.interestAmount).toBe(6000);
    expect(conAnticipo.totalAmount).toBe(106000);
    expect(conAnticipo.installmentAmount).toBeLessThan(sinAnticipo.installmentAmount);
  });

  it("sin anticipo financia la deuda entera", () => {
    const simulacion = paymentPlanService.simulate({ totalDebt: 100000, installments: 6 });

    expect(simulacion.downPayment).toBe(0);
    expect(simulacion.financedAmount).toBe(100000);
    expect(simulacion.totalAmount).toBe(110000);
  });

  it("nunca financia más que la deuda aunque el anticipo se pase", () => {
    const simulacion = paymentPlanService.simulate({ totalDebt: 50000, installments: 3, downPayment: 90000 });

    expect(simulacion.downPayment).toBe(50000);
    expect(simulacion.financedAmount).toBe(0);
  });

  it("el portal simula con el mismo cálculo que la pantalla interna", () => {
    const simulacion = portalService.simulatePaymentPlan({ totalDebt: 120000, installments: 6 });

    expect(simulacion.installments).toBe(6);
    expect(simulacion.totalAmount).toBeGreaterThan(120000);
    expect(simulacion.installmentAmount).toBeCloseTo(simulacion.totalAmount / 6, 2);
  });
});

describe("superficie de los servicios", () => {
  it("auditoría no expone ninguna operación de escritura", () => {
    // El área es de sólo lectura: si aparece un verbo de escritura, es un error de diseño.
    const escritura = /^(register|create|update|resolve|issue|reverse|allocate|retry|delete|generate)/;

    expect(Object.keys(auditService).filter((name) => escritura.test(name))).toEqual([]);
  });

  it("el portal sólo expone consultas y los dos trámites del ciudadano", () => {
    // Registrar pagos, emitir boletas o resolver solicitudes no son atribuciones suyas.
    const prohibidos = /^(register(?!Exemption)|issue|resolve|reverse|allocate|retry)/;

    expect(Object.keys(portalService).filter((m) => prohibidos.test(m))).toEqual([]);
    expect(Object.keys(portalService)).toContain("requestPaymentPlan");
    expect(Object.keys(portalService)).toContain("requestExemption");
  });
});

describe("validación antes de llamar al backend", () => {
  it("el ajuste exige que el importe cambie", async () => {
    const backend = instalarBackendFalso({
      "GET /api/v1/debts/{id}": { id: 3001, outstandingBalance: 85000, dueDate: "2026-09-30", status: "PENDING" },
    });

    await expect(
      debtAdjustmentService.request({ debtId: 3001, newAmount: undefined, reason: "sin cambio", requestedBy: "mrivas" }),
    ).rejects.toMatchObject({ status: 400 });
    // Consulta la deuda para comparar, pero no llega a pedir el ajuste.
    expect(backend.llamadas.some((l) => l.metodo === "POST")).toBe(false);
  });

  it("adjuntar documentación exige al menos un archivo", async () => {
    const backend = instalarBackendFalso();

    await expect(
      exemptionService.attachDocumentation({ requestId: 600, attachments: [], actor: "mrivas" }),
    ).rejects.toMatchObject({ status: 400 });
    expect(backend).not.toHaveBeenCalled();
  });
});

describe("operaciones que el backend no soporta", () => {
  // Se declaran como no disponibles en vez de fingir un resultado.
  it("el reporte outbound de deuda vencida depende del contrato M8", async () => {
    await expect(debtService.reportOverdue(3001)).rejects.toMatchObject({ code: "M8_OUTBOUND_PENDING", status: 501 });
  });

  it("el breakdown genérico de indicadores no está expuesto", async () => {
    await expect(auditService.indicatorBreakdown("collection")).rejects.toMatchObject({
      code: "INDICATOR_BREAKDOWN_UNSUPPORTED",
      status: 501,
    });
  });

  it("ajustar el vencimiento de una deuda no está soportado", async () => {
    instalarBackendFalso({
      "GET /api/v1/debts/{id}": { id: 3001, outstandingBalance: 85000, dueDate: "2026-09-30", status: "PENDING" },
    });

    await expect(
      debtAdjustmentService.request({ debtId: 3001, newDueDate: "2026-12-01", reason: "prórroga", requestedBy: "mrivas" }),
    ).rejects.toMatchObject({ code: "DUE_DATE_ADJUSTMENT_UNSUPPORTED", status: 501 });
  });
});

describe("construcción de los requests", () => {
  it("el cobro en ventanilla manda el medio de pago del backend", async () => {
    const backend = instalarBackendFalso({
      "POST /api/v1/payments": { id: 9001, taxpayerId: 1, amount: 25000, paymentMethod: "CASH", unallocatedAmount: 0, status: "CONFIRMED" },
    });

    await cashierService.registerCounterPayment({ debtId: 3001, amountPaid: 25000, method: "CASH", registeredBy: "pcabrera" });

    const alta = backend.llamadas.find((l) => l.metodo === "POST");
    expect(alta.ruta).toBe("/api/v1/payments");
    expect(alta.cuerpo.paymentMethod).toBe("CASH");
  });

  it("la reversión viaja con su motivo", async () => {
    const backend = instalarBackendFalso({
      "POST /api/v1/payments/{id}/reversal-requests": { id: 1, status: "REQUESTED" },
    });

    await paymentService.reverse({ paymentId: 9001, reason: "Cobro duplicado" });

    const alta = backend.llamadas.find((l) => l.metodo === "POST");
    expect(alta.ruta).toContain("9001");
    expect(JSON.stringify(alta.cuerpo)).toContain("Cobro duplicado");
  });

  it("el pedido de plan del portal viaja con las deudas elegidas", async () => {
    const backend = instalarBackendFalso({
      "POST /api/v1/payment-plan-requests": { id: 800, status: "PENDING", totalDebtAtRequest: 200000, requestedInstallments: 6 },
    });

    await portalService.requestPaymentPlan({ taxpayerId: 123, debtIds: [3001, 3002], installments: 6 });

    const alta = backend.llamadas.find((l) => l.metodo === "POST");
    expect(alta.cuerpo).toMatchObject({ taxpayerId: 123, debtIds: [3001, 3002], installments: 6 });
  });

  it("el procesamiento de vencimientos viaja con su fecha", async () => {
    const backend = instalarBackendFalso({
      "POST /api/v1/administration/process-due-dates": { processed: 4 },
    });

    await administrationService.processDueDates("2026-09-07");

    const alta = backend.llamadas.find((l) => l.metodo === "POST");
    expect(alta.ruta).toBe("/api/v1/administration/process-due-dates");
    expect(alta.cuerpo).toEqual({ processingDate: "2026-09-07" });
  });
});

describe("lectura de la respuesta", () => {
  it("la ficha del contribuyente reúne lo que devuelven los recursos anidados", async () => {
    // La ruta de caja se reescribe al resumen real del contribuyente.
    const backend = instalarBackendFalso({
      "GET /api/v1/taxpayers/{id}/summary": {
        taxpayer: { id: 123, taxpayerType: "CITIZEN", dni: "40111222", displayName: "Juan Pérez", status: "ACTIVE" },
        totalOutstanding: 85000,
      },
    });

    const ficha = await cashierService.taxpayerFile(123);

    expect(backend.llamadas[0].ruta).toBe("/api/v1/taxpayers/123/summary");
    expect(ficha.totalOutstanding).toBe(85000);
  });

  it("no inventa datos cuando el backend devuelve una colección vacía", async () => {
    instalarBackendFalso({ "GET /api/v1/debts": pagina([]) });

    expect(await debtService.list({ taxpayerId: 123 })).toEqual([]);
  });

  it("propaga el error del backend en vez de degradar a datos locales", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("Failed to fetch")));

    await expect(debtService.list({ taxpayerId: 123 })).rejects.toThrow();
  });
});
