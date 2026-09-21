import { afterEach, describe, expect, it, vi } from "vitest";
import {
  auditService,
  externalObligationService,
  liquidationRunService,
  paymentReversalService,
  planExpirationService,
  portalService,
} from "./rentasService.js";
import { instalarBackendFalso, pagina } from "../__tests__/fixtures/backendFalso.js";

afterEach(() => vi.unstubAllGlobals());

describe("lote 2 conectado al backend", () => {
  it("ejecuta una reversión autorizada en el endpoint específico", async () => {
    const backend = instalarBackendFalso({
      "POST /api/v1/payment-reversals/{id}/execute": { id: 12, paymentId: 90, status: "EXECUTED" },
    });
    const result = await paymentReversalService.execute(12);
    expect(result.status).toBe("EXECUTED");
    expect(backend.llamadas.at(-1)).toMatchObject({ metodo: "POST", ruta: "/api/v1/payment-reversals/12/execute" });
  });

  it("consulta incumplidos y solicita la caducidad sin modificar el plan localmente", async () => {
    const backend = instalarBackendFalso({
      "GET /api/v1/payment-plans/defaulted": pagina([{ id: 44, taxpayerId: 1, status: "ACTIVE", outstandingPlanAmount: 5000 }]),
      "POST /api/v1/payment-plans/{id}/expiration-requests": { id: 70, paymentPlanId: 44, status: "PENDING_APPROVAL", reason: "Tres cuotas vencidas" },
    });
    const [plan] = await planExpirationService.defaulted();
    expect(plan.outstandingAmount).toBe(5000);
    const request = await planExpirationService.request({ planId: 44, reason: "Tres cuotas vencidas" });
    expect(request.status).toBe("PENDING_APPROVAL");
    expect(backend.llamadas.at(-1).cuerpo).toEqual({ reason: "Tres cuotas vencidas" });
  });

  it("autoriza o rechaza caducidades mediante endpoints del Supervisor", async () => {
    const backend = instalarBackendFalso({
      "POST /api/v1/payment-plan-expirations/{id}/approve": { id: 70, status: "APPROVED" },
      "POST /api/v1/payment-plan-expirations/{id}/reject": { id: 71, status: "REJECTED" },
    });
    await planExpirationService.resolve({ id: 70, status: "APPROVED", reason: "Verificado" });
    await planExpirationService.resolve({ id: 71, status: "REJECTED", reason: "Dentro de tolerancia" });
    expect(backend.llamadas.slice(-2).map((call) => call.ruta)).toEqual([
      "/api/v1/payment-plan-expirations/70/approve",
      "/api/v1/payment-plan-expirations/71/reject",
    ]);
  });

  it("resuelve y ejecuta corridas masivas en el backend", async () => {
    const backend = instalarBackendFalso({
      "POST /api/v1/liquidation-runs/{id}/approve": { id: 8, status: "APPROVED" },
      "POST /api/v1/liquidation-runs/{id}/execute": { id: 8, status: "EXECUTED" },
    });
    await liquidationRunService.resolve({ id: 8, status: "APPROVED", reason: "Lote correcto" });
    const executed = await liquidationRunService.execute(8);
    expect(executed.status).toBe("EXECUTED");
    expect(backend.llamadas.slice(-2).map((call) => call.ruta)).toEqual([
      "/api/v1/liquidation-runs/8/approve",
      "/api/v1/liquidation-runs/8/execute",
    ]);
  });

  it("consulta y reintenta obligaciones externas con error", async () => {
    const backend = instalarBackendFalso({
      "GET /api/v1/external-obligations": pagina([{ id: 5, externalReferenceId: "M4-889", status: "ERROR", retryCount: 2 }]),
      "POST /api/v1/external-obligations/{id}/retry": { id: 5, externalReferenceId: "M4-889", status: "PROCESSED", retryCount: 3 },
    });
    const [obligation] = await externalObligationService.list({ status: "ERROR" });
    expect(obligation.externalReferenceId).toBe("M4-889");
    expect((await externalObligationService.retry(5)).status).toBe("PROCESSED");
    expect(backend.llamadas.at(-1).ruta).toBe("/api/v1/external-obligations/5/retry");
  });

  it("consulta beneficios y saldos propios del contribuyente", async () => {
    instalarBackendFalso({
      "GET /api/v1/taxpayers/{id}/benefits": [{ id: 2, taxpayerId: 123, status: "ACTIVE", discountPercentage: 50 }],
      "GET /api/v1/taxpayers/{id}/credit-balances": [{ id: 3, taxpayerId: 123, originalAmount: 1000, availableAmount: 600 }],
    });
    expect(await portalService.benefits({ taxpayerId: 123 })).toHaveLength(1);
    expect((await portalService.creditBalances({ taxpayerId: 123 }))[0].availableAmount).toBe(600);
  });

  it("previsualiza, registra y obtiene comprobante del pago electrónico", async () => {
    const backend = instalarBackendFalso({
      "POST /api/v1/electronic-payments/preview": { debtId: 30, requestedAmount: 900, payableAmount: 900, approved: true, message: "Pago disponible" },
      "POST /api/v1/electronic-payments": { id: 4, paymentId: 91, taxpayerId: 123, debtId: 30, amount: 900, status: "APPROVED", gatewayReference: "SIM-1" },
      "GET /api/v1/payments/{id}/receipt": { paymentId: 91, receiptNumber: "REC-91", amount: 900 },
    });
    const preview = await portalService.previewElectronicPayment({ debtId: 30, paymentMethod: "CARD", amount: 900 });
    expect(preview.approved).toBe(true);
    const payment = await portalService.createElectronicPayment({ debtId: 30, paymentMethod: "CARD", amount: 900, idempotencyKey: "one" });
    expect(payment.gatewayReference).toBe("SIM-1");
    expect((await portalService.paymentReceipt(payment.paymentId)).receiptNumber).toBe("REC-91");
    expect(backend.llamadas.filter((call) => call.ruta.includes("electronic-payments"))).toHaveLength(2);
  });

  it("simula, solicita y consulta una refinanciación del contribuyente", async () => {
    const backend = instalarBackendFalso({
      "POST /api/v1/payment-plans/{id}/refinancing/simulations": { principal: 6000, interest: 600, total: 6600, installments: 6, regularInstallmentAmount: 1100 },
      "POST /api/v1/payment-plans/{id}/refinancing-requests": { id: 99, originalPlanId: 44, requestedInstallments: 6, status: "PENDING", outstandingPrincipalAtRequest: 6000 },
      "GET /api/v1/refinancing-requests/{id}": { id: 99, originalPlanId: 44, requestedInstallments: 6, status: "PENDING", outstandingPrincipalAtRequest: 6000 },
    });
    expect((await portalService.simulateRefinancing({ planId: 44, installments: 6 })).total).toBe(6600);
    const created = await portalService.requestRefinancing({ planId: 44, installments: 6 });
    expect(created.status).toBe("REQUESTED");
    expect((await portalService.refinancingRequest(created.id)).planId).toBe(44);
    expect(backend.llamadas.at(-1).ruta).toBe("/api/v1/refinancing-requests/99");
  });

  it("compone el detalle auditable del pago con imputaciones e historial", async () => {
    instalarBackendFalso({
      "GET /api/v1/payments/{id}": { id: 91, taxpayerId: 123, amount: 900, allocatedAmount: 900, unallocatedAmount: 0, paymentMethod: "CARD", status: "CONFIRMED" },
      "GET /api/v1/payments/{id}/allocations": [{ id: 7, paymentId: 91, debtId: 30, amount: 900, status: "ACTIVE" }],
      "GET /api/v1/audit/entities/Payment/{id}": [{ id: 1, action: "PAYMENT_REGISTERED", userId: "citizen", occurredAt: "2026-09-20T10:00:00Z" }],
      "GET /api/v1/payment-reversals": pagina([]),
      "GET /api/v1/credit-balances": pagina([]),
      "GET /api/v1/debts/{id}": { id: 30, taxConceptId: 2, status: "PARTIALLY_PAID", outstandingBalance: 100 },
    });
    const detail = await auditService.paymentDetail(91);
    expect(detail.allocations[0]).toMatchObject({ debtId: 30, conceptName: "Concepto #2" });
    expect(detail.history[0]).toMatchObject({ action: "PAYMENT_REGISTERED", actor: "citizen" });
  });
});
