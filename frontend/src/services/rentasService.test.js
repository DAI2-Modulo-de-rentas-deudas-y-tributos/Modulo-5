import { afterEach, describe, expect, it, vi } from "vitest";
import {
  auditService,
  administrationService,
  cashierService,
  debtAdjustmentService,
  debtService,
  exemptionService,
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
  it("consulta el cálculo versionado del backend sin inventar intereses", async () => {
    const backend = instalarBackendFalso({
      "POST /api/v1/payment-plans/simulations": {
        configurationId: 7, configurationVersion: 2, principal: 120000,
        downPayment: 0, financedPrincipal: 120000, interest: 0,
        total: 120000, installments: 6, regularInstallmentAmount: 20000,
        exceptional: false,
      },
    });

    const simulacion = await portalService.simulatePaymentPlan({ taxpayerId: 123, debtIds: [3001], installments: 6 });

    expect(simulacion).toMatchObject({ configurationVersion: 2, total: 120000, regularInstallmentAmount: 20000 });
    expect(backend.llamadas[0]).toMatchObject({
      ruta: "/api/v1/payment-plans/simulations",
      cuerpo: { taxpayerId: 123, debtIds: [3001], installments: 6 },
    });
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

  it("el cobro de una cuota se imputa por installmentId", async () => {
    const backend = instalarBackendFalso({
      "GET /api/v1/payment-plans/{id}/installments/{id}": {
        id: 77,
        paymentPlanId: 850,
        number: 1,
        type: "REGULAR",
        totalAmount: 12000,
        paidAmount: 0,
        outstandingAmount: 12000,
        dueDate: "2026-10-10",
        status: "PENDING",
        overdue: false,
      },
      "GET /api/v1/taxpayers/{id}": {
        id: 123,
        taxpayerType: "CITIZEN",
        dni: "40111222",
        displayName: "Juan Pérez",
        status: "ACTIVE",
      },
      "POST /api/v1/payments": {
        id: 9002,
        taxpayerId: 123,
        amount: 12000,
        paymentMethod: "CASH",
        unallocatedAmount: 0,
        status: "CONFIRMED",
      },
    });

    const receipt = await cashierService.registerCounterPayment({
      installmentId: 77,
      paymentPlanId: 850,
      taxpayerId: 123,
      amountPaid: 12000,
      method: "CASH",
      registeredBy: "pcabrera",
    });

    const alta = backend.llamadas.find((call) => call.metodo === "POST");
    expect(alta.ruta).toBe("/api/v1/payments");
    expect(alta.cuerpo.allocations).toEqual([{ debtId: null, installmentId: 77, amount: 12000 }]);
    expect(receipt).toMatchObject({ targetType: "INSTALLMENT", installmentId: 77, paymentPlanId: 850 });
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
  it("muestra el nombre del concepto tributario en las deudas del portal", async () => {
    instalarBackendFalso({
      "GET /api/v1/taxpayers/{id}/debts": pagina([
        { id: 3001, taxpayerId: 123, taxConceptId: 2, originalAmount: 120000,
          outstandingBalance: 60000, dueDate: "2027-03-31", status: "PENDING" },
      ]),
      "GET /api/v1/taxpayers/{id}/bills": pagina([]),
      "GET /api/v1/tax-concepts": pagina([
        { id: 2, code: "ABL", name: "Alumbrado, barrido y limpieza", type: "FEE", active: true },
      ]),
    });

    expect(await portalService.debts({ taxpayerId: 123 })).toMatchObject([
      { id: 3001, conceptName: "Alumbrado, barrido y limpieza", outstandingAmount: 60000 },
    ]);
  });

  it("la ficha de caja cruza deudas, pagos y boletas con sus totales", async () => {
    // El backend expone cada frente por separado; juntarlos y totalizar es del cliente.
    instalarBackendFalso({
      "GET /api/v1/taxpayers/{id}": { id: 123, taxpayerType: "CITIZEN", dni: "40111222", displayName: "Juan Pérez", status: "ACTIVE" },
      "GET /api/v1/taxpayers/{id}/debts": pagina([
        { id: 3001, taxpayerId: 123, taxConceptId: 1, status: "PENDING", outstandingBalance: 85000, dueDate: "2026-09-30", overdue: false },
        { id: 3002, taxpayerId: 123, taxConceptId: 3, status: "PENDING", outstandingBalance: 40000, dueDate: "2026-08-10", overdue: true },
      ]),
      "GET /api/v1/payments": pagina([
        { id: 9005, taxpayerId: 123, amount: 25000, paymentMethod: "CASH", unallocatedAmount: 0, status: "CONFIRMED" },
      ]),
      "GET /api/v1/taxpayers/{id}/bills": pagina([]),
      "GET /api/v1/taxpayers/{id}/payment-plans": pagina([
        { id: 850, taxpayerId: 123, status: "ACTIVE", totalPlanAmount: 137500, installmentCount: 6, outstandingPlanAmount: 137500 },
      ]),
      "GET /api/v1/payment-plans/{id}/installments": [
        { id: 77, paymentPlanId: 850, number: 1, type: "REGULAR", totalAmount: 12000, paidAmount: 0, outstandingAmount: 12000, dueDate: "2026-10-10", status: "PENDING", overdue: false },
      ],
    });

    const ficha = await cashierService.taxpayerFile(123);

    expect(ficha.taxpayer.name).toBe("Juan Pérez");
    expect(ficha.debts).toHaveLength(2);
    expect(ficha.installments).toEqual([
      expect.objectContaining({ id: 77, planId: 850, outstandingAmount: 12000 }),
    ]);
    expect(ficha.totals.outstanding).toBe(125000);
    // Sólo la vencida cuenta como exigible.
    expect(ficha.totals.overdue).toBe(40000);
    expect(ficha.totals.paid).toBe(25000);
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
