import { afterEach, describe, expect, it, vi } from "vitest";
import { creditBalanceService, debtAdjustmentService } from "./rentasService.js";
import { instalarBackendFalso, pagina } from "../__tests__/fixtures/backendFalso.js";

/**
 * Saldos a favor y ajustes de deuda.
 *
 * Cuánto se puede aplicar, quién autoriza y qué evento se publica lo resuelve el
 * backend. Del cliente es evitar que la pantalla ofrezca algo imposible: las deudas
 * que se listan para aplicar un saldo son las del mismo contribuyente, y un ajuste
 * que no cambia nada no se manda.
 */

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("aplicación de un saldo a favor", () => {
  it("sólo ofrece deudas del contribuyente dueño del saldo", async () => {
    const backend = instalarBackendFalso({
      "GET /api/v1/credit-balances/{id}": { id: 7001, taxpayerId: 123, amount: 30000, status: "AVAILABLE" },
      "GET /api/v1/taxpayers/{id}/debts": pagina([
        { id: 3001, taxpayerId: 123, taxConceptId: 1, status: "PENDING", outstandingBalance: 85000, dueDate: "2026-09-30" },
      ]),
    });

    const deudas = await creditBalanceService.applicableDebts(7001);

    // Resuelve primero de quién es el saldo y recién entonces pide sus deudas.
    expect(backend.llamadas[0].ruta).toBe("/api/v1/credit-balances/7001");
    expect(backend.llamadas[1].ruta).toBe("/api/v1/taxpayers/123/debts");
    expect(deudas).toHaveLength(1);
  });

  it("la aplicación viaja con la deuda y el importe elegidos", async () => {
    const backend = instalarBackendFalso({
      "POST /api/v1/credit-balances/{id}/applications": { id: 1, appliedAmount: 25000 },
    });

    await creditBalanceService.apply({ creditId: 7001, debtId: 3001, amount: 25000, appliedBy: "mrivas" });

    // El adaptador traduce la ruta heredada a la operación real del backend.
    expect(backend.llamadas[0].ruta).toBe("/api/v1/credit-balances/7001/apply");
    expect(backend.llamadas[0].cuerpo).toEqual({ debtId: 3001, amount: 25000 });
  });

  it("no inventa saldos cuando el contribuyente no tiene ninguno", async () => {
    instalarBackendFalso({ "GET /api/v1/credit-balances": pagina([]) });

    expect(await creditBalanceService.list({ taxpayerId: 123 })).toEqual([]);
  });
});

describe("ajuste de deuda", () => {
  const DEUDA = { id: 3001, outstandingBalance: 85000, dueDate: "2026-09-30", status: "PENDING" };

  it("manda una bonificación cuando el importe baja", async () => {
    const backend = instalarBackendFalso({
      "GET /api/v1/debts/{id}": DEUDA,
      "POST /api/v1/adjustments": { id: 1, status: "PENDING" },
    });

    await debtAdjustmentService.request({ debtId: 3001, newAmount: 60000, reason: "Error de cálculo", requestedBy: "mrivas" });

    const alta = backend.llamadas.find((l) => l.metodo === "POST");
    expect(alta.cuerpo).toMatchObject({ debtId: 3001, type: "DISCOUNT", amount: 25000, reason: "Error de cálculo" });
  });

  it("manda un recargo cuando el importe sube", async () => {
    const backend = instalarBackendFalso({
      "GET /api/v1/debts/{id}": DEUDA,
      "POST /api/v1/adjustments": { id: 1, status: "PENDING" },
    });

    await debtAdjustmentService.request({ debtId: 3001, newAmount: 95000, reason: "Base corregida", requestedBy: "mrivas" });

    const alta = backend.llamadas.find((l) => l.metodo === "POST");
    expect(alta.cuerpo).toMatchObject({ type: "SURCHARGE", amount: 10000 });
  });

  it("un ajuste que no cambia el importe no sale del cliente", async () => {
    const backend = instalarBackendFalso({ "GET /api/v1/debts/{id}": DEUDA });

    await expect(
      debtAdjustmentService.request({ debtId: 3001, newAmount: 85000, reason: "sin cambio", requestedBy: "mrivas" }),
    ).rejects.toMatchObject({ status: 400 });
    expect(backend.llamadas.some((l) => l.metodo === "POST")).toBe(false);
  });

  it("autorizar y rechazar son operaciones distintas", async () => {
    const aprobar = instalarBackendFalso({ "POST /api/v1/adjustments/{id}/approve": { id: 1, status: "APPROVED" } });
    await debtAdjustmentService.resolve({ adjustmentId: 1, status: "APPROVED", resolvedBy: "jlopez", resolverRole: "SUPERVISOR" });
    expect(aprobar.llamadas[0].ruta).toBe("/api/v1/adjustments/1/approve");
    vi.unstubAllGlobals();

    const rechazar = instalarBackendFalso({ "POST /api/v1/adjustments/{id}/reject": { id: 1, status: "REJECTED" } });
    await debtAdjustmentService.resolve({ adjustmentId: 1, status: "REJECTED", reason: "No corresponde", resolvedBy: "jlopez" });
    expect(rechazar.llamadas[0].ruta).toBe("/api/v1/adjustments/1/reject");
    expect(rechazar.llamadas[0].cuerpo).toEqual({ reason: "No corresponde" });
  });

  it("ejecutar sólo relee el ajuste: el backend ya lo aplicó al aprobarlo", async () => {
    const backend = instalarBackendFalso({ "GET /api/v1/adjustments/{id}": { id: 1, status: "APPROVED" } });

    await debtAdjustmentService.execute({ adjustmentId: 1, executedBy: "mrivas" });

    expect(backend.llamadas).toEqual([{ metodo: "GET", ruta: "/api/v1/adjustments/1", cuerpo: null }]);
  });
});
