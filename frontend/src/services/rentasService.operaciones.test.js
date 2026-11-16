import { beforeEach, describe, expect, it, vi } from "vitest";

/**
 * Operaciones de rentasService que ninguna pantalla probada ejercita todavía.
 *
 * `request` se reemplaza por un router de rutas → respuestas: lo que se verifica
 * es qué operación real del backend invoca cada servicio y cómo compone el
 * resultado, no el transporte HTTP (eso lo cubren apiClient.test y apiAdapters).
 */
const { request, routes } = vi.hoisted(() => {
  const routes = new Map();
  const request = vi.fn(async (path) => {
    const pathname = path.split("?")[0];
    const handler = routes.get(pathname) ?? routes.get(path);
    if (handler === undefined) return [];
    return typeof handler === "function" ? handler(path) : handler;
  });
  return { request, routes };
});

vi.mock("./apiClient.js", async (importOriginal) => ({ ...(await importOriginal()), request }));

const {
  settlementService, debtService, reconciliationService, billService, paymentService, paymentReversalService,
  planExpirationService, refinancingService, exemptionService, ticketService, eventService,
  externalObligationService, liquidationRunService, cashierService, auditService, portalService,
} = await import("./rentasService.js");

const fail = (status) => () => { throw Object.assign(new Error(`HTTP ${status}`), { status }); };
const calls = () => request.mock.calls.map(([path, options]) => [path, options?.method ?? "GET", options?.body]);

beforeEach(() => {
  routes.clear();
  request.mockClear();
  routes.set("/api/v1/tax-concepts", [{ id: 4, code: "TS" }]);
  routes.set("/api/v1/taxpayers/7", { id: 7, name: "Ana" });
});

describe("operaciones simples hacia el backend", () => {
  it("emite liquidaciones y boletas resolviendo el concepto y la deuda", async () => {
    await settlementService.generate({ taxpayerId: 7, conceptCode: "TS", period: "2026-10", baseAmount: 10, dueDate: "d" });
    expect(calls()).toContainEqual(["/api/v1/settlements", "POST", { taxpayerId: 7, conceptId: 4, period: "2026-10", baseAmount: 10, dueDate: "d" }]);

    routes.set("/api/v1/debts/3", { id: 3, taxpayerId: 7, dueDate: "v" });
    await billService.issue({ debtId: "3" });
    expect(calls()).toContainEqual(["/api/v1/bills", "POST", { taxpayerId: 7, debtIds: [3], dueDate: "v" }]);
  });

  it("llama cada operación auxiliar con su ruta y cuerpo", async () => {
    await debtService.accountStatement(7);
    await reconciliationService.importBatch("L-1", [{ a: 1 }]);
    await reconciliationService.resolve(5, 9, "ok");
    await eventService.retry(11);
    await externalObligationService.errors();
    await liquidationRunService.detail(2);
    await liquidationRunService.resolve({ id: 2, status: "REJECTED", reason: "no" });
    await liquidationRunService.resolve({ id: 2, status: "APPROVED" });
    await auditService.settlementDetail(1);
    await auditService.reversals();
    await auditService.reversalDetail(2);
    await auditService.planDetail(3);
    await auditService.ticketDetail(4);
    await portalService.notices(7);
    await portalService.planInstallments(8);
    await portalService.electronicPayment(9);

    expect(calls()).toEqual([
      ["/api/v1/taxpayers/7/account-statement", "GET", undefined],
      ["/api/v1/payment-reconciliations/batches", "POST", { batchReference: "L-1", items: [{ a: 1 }] }],
      ["/api/v1/payment-reconciliations/items/5/resolve", "POST", { paymentId: 9, reason: "ok" }],
      ["/api/v1/events/11/retry", "POST", undefined],
      ["/api/v1/external-obligations/errors?size=100&sort=receivedAt,desc", "GET", undefined],
      ["/api/v1/liquidation-runs/2", "GET", undefined],
      ["/api/v1/liquidation-runs/2/reject", "POST", { reason: "no" }],
      ["/api/v1/liquidation-runs/2/approve", "POST", { observation: null }],
      ["/api/v1/audit/settlements/1", "GET", undefined],
      ["/api/v1/audit/reversals?paymentId=&username=&from=&to=", "GET", undefined],
      ["/api/v1/audit/reversals/2", "GET", undefined],
      ["/api/v1/audit/payment-plans/3", "GET", undefined],
      ["/api/v1/audit/tickets/4", "GET", undefined],
      ["/api/v1/portal/7/notices", "GET", undefined],
      ["/api/v1/payment-plans/8/installments", "GET", undefined],
      ["/api/v1/electronic-payments/9", "GET", undefined],
    ]);
  });

  it("imputa un pago por su saldo sin imputar", async () => {
    routes.set("/api/v1/payments/5", { id: 5, unallocatedAmount: 30 });
    await paymentService.allocate({ paymentId: 5, debtId: 2 });
    expect(calls()).toContainEqual(["/api/v1/payments/5/allocations", "POST", { debtId: 2, amount: 30 }]);
  });

  it("rechaza un estado de ticket que el backend no soporta", async () => {
    await expect(ticketService.updateStatus({ ticketId: 1, status: "OTRO" })).rejects.toThrow("Estado de ticket no soportado.");
  });
});

describe("composiciones con lecturas que pueden fallar", () => {
  it("propaga un error de imputaciones que no es de permisos", async () => {
    routes.set("/api/v1/payment-reversals/1", { id: 1, paymentId: 5 });
    routes.set("/api/v1/payments/5", { id: 5, taxpayerId: 7 });
    routes.set("/api/v1/payments/5/allocations", fail(500));
    await expect(paymentReversalService.detail(1)).rejects.toMatchObject({ status: 500 });
  });

  it("arma el detalle de caducidad aunque la configuración del plan no responda", async () => {
    routes.set("/api/v1/payment-plan-expirations/3", { id: 3, paymentPlanId: 8 });
    routes.set("/api/v1/payment-plans/8", { id: 8, taxpayerId: 7, configurationId: 2 });
    routes.set("/api/v1/payment-plans/8/installments", [{ id: 1 }]);
    routes.set("/api/v1/payment-plan-configurations/2", fail(404));
    const detail = await planExpirationService.detail(3);
    expect(detail).toMatchObject({ id: 3, plan: { id: 8, installments: [{ id: 1 }], taxpayer: { id: 7 }, configuration: null } });
  });

  it("deriva y resuelve refinanciaciones como Personal y como Supervisor", async () => {
    await refinancingService.escalate({ requestId: 4, note: "excepción" });
    expect(await refinancingService.resolve({ requestId: 4, status: "REJECTED", reason: "no" })).toMatchObject({ reason: "no" });
    await refinancingService.resolve({ requestId: 4, status: "REJECTED", resolverRole: "SUPERVISOR", reason: "no" });

    routes.set("/api/v1/refinancing-requests/4/grant", { planId: 8, newPaymentPlanId: 9 });
    routes.set("/api/v1/payment-plans/8", { id: 8 });
    routes.set("/api/v1/payment-plans/9", { id: 9 });
    expect(await refinancingService.resolve({ requestId: 4, status: "GRANTED", resolverRole: "SUPERVISOR" }))
      .toMatchObject({ previousPlan: { id: 8 }, newPlan: { id: 9 } });

    expect(calls().filter(([, method]) => method === "POST").map(([path, , body]) => [path, body])).toEqual([
      ["/api/v1/refinancing-requests/4/submit-exception", { reason: "excepción" }],
      ["/api/v1/refinancing-requests/4/reject", { reason: "no" }],
      ["/api/v1/refinancing-requests/4/reject-exception", { reason: "no" }],
      ["/api/v1/refinancing-requests/4/approve-exception", { observation: null }],
      ["/api/v1/refinancing-requests/4/grant", undefined],
    ]);
  });

  it("lista refinanciaciones aunque el plan original no se pueda leer", async () => {
    routes.set("/api/v1/refinancing-requests", [{ id: 1, planId: 8 }]);
    routes.set("/api/v1/payment-plans/8", fail(403));
    expect(await refinancingService.list()).toEqual([{ id: 1, planId: 8, originalPlan: null }]);
  });

  it("valida los documentos de la exención y resuelve aprobadas y rechazadas", async () => {
    await expect(exemptionService.requestExemption({ citizenId: 7, conceptCode: "TS", attachments: [{ externalDocumentId: "x" }] }))
      .rejects.toMatchObject({ status: 400 });

    routes.set("/api/v1/exemption-requests/6/approve", { id: 70 });
    routes.set("/api/v1/exemption-requests/6/reject", { id: 6, status: "REJECTED" });
    expect(await exemptionService.resolve({ requestId: 6, status: "APPROVED", percentage: 50 })).toMatchObject({ exemptionId: 70, status: "APPROVED" });
    expect(await exemptionService.resolve({ requestId: 6, status: "REJECTED", reason: "no" })).toEqual({ id: 6, status: "REJECTED" });
    expect(calls()).toContainEqual(["/api/v1/exemption-requests/6/approve", "POST", { percentage: 50, validFrom: undefined, validUntil: undefined, observation: null }]);
  });

  it("compone el detalle de auditoría de deudas, pagos y obligaciones externas aunque falten lecturas", async () => {
    routes.set("/api/v1/debts/3", { id: 3 });
    routes.set("/api/v1/payment-allocations", fail(403));
    routes.set("/api/v1/audit/entities/Debt/3", fail(500));
    expect(await auditService.debtDetail(3)).toMatchObject({ id: 3, payments: [], history: [] });

    routes.set("/api/v1/payments/5", { id: 5, taxpayerId: 7 });
    routes.set("/api/v1/payments/5/allocations", [{ id: 1, installmentId: 2, allocatedAt: "2026-01-02" }, { id: 2, debtId: 3, reversedAt: "2026-01-03" }]);
    routes.set("/api/v1/audit/entities/Payment/5", fail(500));
    routes.set("/api/v1/payment-reversals", fail(500));
    routes.set("/api/v1/credit-balances", fail(500));
    const payment = await auditService.paymentDetail(5);
    expect(payment).toMatchObject({ reversal: null, creditBalance: null });
    expect(payment.history.map((item) => item.action)).toEqual(["Imputación #1 registrada", "Imputación #2 revertida"]);
    expect(payment.allocations.map((item) => item.conceptName)).toEqual(["Cuota de plan", undefined]);

    routes.set("/api/v1/external-obligations/9", { id: 9 });
    routes.set("/api/v1/audit/entities/ExternalObligation/9", fail(500));
    routes.set("/api/v1/debts", [{ id: 3 }]);
    expect(await auditService.externalObligationDetail(9)).toEqual({ id: 9, history: [], debt: { id: 3 } });
  });

  it("arma los planes del portal aunque no se puedan leer sus deudas", async () => {
    routes.set("/api/v1/taxpayers/7/payment-plan-requests", [{ requestId: 1, planId: 8 }, { requestId: 2, downPayment: 5, totalAmount: 50 }]);
    routes.set("/api/v1/payment-plan-requests/1/debts", fail(500));
    routes.set("/api/v1/payment-plan-requests/2/debts", [{ id: 3 }]);
    routes.set("/api/v1/payment-plans/8", { id: 8, lifecycle: "CURRENT", downPaymentAmount: "10", totalPlanAmount: "100" });
    routes.set("/api/v1/payment-plans/8/installments", [{ id: 1, totalAmount: "25" }]);
    const plans = await portalService.paymentPlans({ taxpayerId: 7 });
    expect(plans[0]).toMatchObject({ debts: [], lifecycle: "CURRENT", downPayment: 10, totalAmount: 100, schedule: [{ id: 1, amount: 25 }] });
    expect(plans[1]).toMatchObject({ debtIds: [3], lifecycle: null, downPayment: 5, totalAmount: 50, schedule: [] });
  });
});

describe("ventanilla de caja", () => {
  it("busca por número de deuda y tolera que la deuda no exista", async () => {
    routes.set("/api/v1/taxpayers", [{ id: 7, name: "Ana", documentType: "DNI", document: "1" }]);
    routes.set("/api/v1/bills/search", [{ id: 2, barcode: "B", taxpayerId: 7 }]);
    routes.set("/api/v1/debts/12", { id: 12, taxpayerId: 7, conceptCode: "TS", outstandingAmount: 5 });
    expect((await cashierService.search({ query: "12" })).map((row) => row.kind)).toEqual(["TAXPAYER", "BILL", "DEBT"]);

    routes.set("/api/v1/debts/12", fail(404));
    expect((await cashierService.search({ query: "12" })).map((row) => row.kind)).toEqual(["TAXPAYER", "BILL"]);

    routes.set("/api/v1/debts/12", fail(500));
    await expect(cashierService.search({ query: "12" })).rejects.toMatchObject({ status: 500 });
  });

  it("arma el contexto de cobro de una deuda puntual", async () => {
    routes.set("/api/v1/debts/3", { id: 3, taxpayerId: 7, outstandingAmount: 10, status: "OVERDUE" });
    expect(await cashierService.chargeContext({ kind: "DEBT", id: 3 }))
      .toMatchObject({ taxpayer: { id: 7 }, selectedDebtId: 3, totals: { pendingCount: 1, outstanding: 10, overdue: 10 } });
  });

  it("arma el contexto de cobro de una cuota y valida que pertenezca al plan", async () => {
    await expect(cashierService.chargeContext({ kind: "INSTALLMENT", id: 1 })).rejects.toMatchObject({ status: 400 });

    routes.set("/api/v1/payment-plans/8/installments/1", { id: 1, paymentPlanId: 9 });
    await expect(cashierService.chargeContext({ kind: "INSTALLMENT", id: 1, planId: 8, taxpayerId: 7 })).rejects.toMatchObject({ status: 409 });

    routes.set("/api/v1/payment-plans/8/installments/1", { id: 1, paymentPlanId: 8, outstandingAmount: "15", status: "OVERDUE" });
    expect(await cashierService.chargeContext({ kind: "INSTALLMENT", id: 1, planId: 8, taxpayerId: "7" }))
      .toMatchObject({ selectedInstallmentId: 1, selectedDebtId: null, totals: { pendingCount: 1, outstanding: 15, overdue: 15 }, installments: [{ id: 1, planId: 8 }] });

    await expect(cashierService.chargeContext({ kind: "OTRO", id: 1 })).rejects.toMatchObject({ status: 400 });
  });

  it("no registra el cobro de una cuota sin plan ni contribuyente", async () => {
    await expect(cashierService.registerCounterPayment({ installmentId: 1, amountPaid: 5, method: "CASH" })).rejects.toMatchObject({ status: 400 });
    expect(request).not.toHaveBeenCalled();
  });

  it("confirma el cobro aunque no pueda releer el saldo actualizado", async () => {
    let reads = 0;
    routes.set("/api/v1/debts/3", () => {
      reads += 1;
      if (reads > 1) throw new Error("caído");
      return { id: 3, taxpayerId: 7, conceptCode: "TS", status: "PENDING" };
    });
    routes.set("/api/v1/cashier/payments", { id: 50, paidAt: "t" });
    const receipt = await cashierService.registerCounterPayment({ debtId: 3, amountPaid: 5, method: "CASH" });
    expect(receipt).toMatchObject({ targetType: "DEBT", debtId: 3, balanceUnavailable: true, wasOverdue: false });
  });
});
