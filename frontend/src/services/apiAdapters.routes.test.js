import { describe, expect, it } from "vitest";
import { adaptApiRequest, adaptApiResponse, pageItems } from "./apiAdapters.js";

const route = (path, options) => adaptApiRequest(path, options);
const query = (path) => Object.fromEntries(new URL(route(path).path, "http://test").searchParams);

describe("adaptApiRequest: rutas legadas hacia el contrato real", () => {
  it("traduce la búsqueda de contribuyentes y de configuraciones", () => {
    expect(query("/api/v1/taxpayers?query=Ana&type=CITIZEN")).toEqual({ size: "100", q: "Ana", type: "CITIZEN" });
    expect(route("/api/v1/tax-config?status=ACTIVE").path).toBe("/api/v1/tax-configurations?size=100&status=ACTIVE");
    expect(route("/api/v1/tax-config/TASA SERV").path).toBe("/api/v1/tax-concepts?q=TASA%20SERV&size=100");
  });

  it("arma el alta de versión con números del formulario y valores por defecto", () => {
    const legacy = route("/api/v1/tax-config/4/versions", {
      method: "POST",
      body: { conceptId: 4, calculationType: "PORCENTAJE", rate: "", value: "10", minimumAmount: "abc", maximumAmount: "50", validFrom: "2026-01-01", validUntil: "" },
    });
    expect(legacy).toMatchObject({ path: "/api/v1/tax-configurations", options: { method: "POST" } });
    expect(legacy.options.body).toEqual({
      taxConceptId: 4, calculationType: "PERCENTAGE", rate: 0, fixedAmount: 10, minimumAmount: 0, maximumAmount: 50,
      partialPaymentAllowed: true, paymentPlanAllowed: true, validFrom: "2026-01-01", validUntil: null,
    });

    const direct = route("/api/v1/tax-configurations", {
      method: "POST",
      body: { taxConceptId: 5, calculationType: "CUSTOM", fixedAmount: 3, rate: null, maximumAmount: undefined, partialPaymentAllowed: false, paymentPlanAllowed: false, validUntil: "2026-12-31" },
    });
    expect(direct.options.body).toMatchObject({ taxConceptId: 5, calculationType: "CUSTOM", rate: 0, fixedAmount: 3, maximumAmount: null, partialPaymentAllowed: false, paymentPlanAllowed: false, validUntil: "2026-12-31" });

    expect(route("/api/v1/tax-configurations", { method: "POST" }).options.body).toMatchObject({ rate: 0, fixedAmount: 0, maximumAmount: null });
  });

  it("envía cada acción de versión con el cuerpo que espera el backend", () => {
    expect(route("/api/v1/tax-config/versions/9/submit", { method: "POST", body: { x: 1 } })).toMatchObject({ path: "/api/v1/tax-configurations/9/submit", options: { body: undefined } });
    expect(route("/api/v1/tax-config/versions/9/reject", { method: "POST", body: { reason: "No" } }).options.body).toEqual({ reason: "No" });
    expect(route("/api/v1/tax-config/versions/9/reject", { method: "POST" }).options.body).toEqual({ reason: "Rechazada" });
    expect(route("/api/v1/tax-config/versions/9/approve", { method: "POST", body: { reason: "Ok" } }).options.body).toEqual({ observation: "Ok" });
    expect(route("/api/v1/tax-config/versions/9/approve", { method: "POST" }).options.body).toEqual({ observation: null });
  });

  it("traduce previsualización, corridas masivas y listados de liquidaciones", () => {
    const preview = route("/api/v1/settlements/preview", { method: "POST", body: { taxpayerId: 1, taxConceptId: 2, baseAmount: 7 } });
    expect(preview).toMatchObject({ path: "/api/v1/liquidations/preview", options: { body: { taxConceptId: 2, taxableBase: 7 } } });
    expect(route("/api/v1/settlements/preview", { method: "POST", body: { taxpayerId: 1, taxableBase: 4 } }).options.body.taxableBase).toBe(4);
    expect(route("/api/v1/settlements/preview", { method: "POST", body: { taxpayerId: 1 } }).options.body.taxableBase).toBe(0);

    const batch = route("/api/v1/settlements/batch", { method: "POST", body: { conceptId: 3, period: "2026-10", items: [{ taxpayerId: 1, taxableBase: 5 }, { id: 2, baseAmount: 6 }, { id: 3 }] } });
    expect(batch).toMatchObject({ path: "/api/v1/liquidation-runs", options: { body: { taxConceptId: 3, items: [{ taxpayerId: 1, taxableBase: 5 }, { taxpayerId: 2, taxableBase: 6 }, { taxpayerId: 3, taxableBase: 0 }] } } });
    expect(route("/api/v1/settlements/batch", { method: "POST", body: { taxConceptId: 3, taxpayers: [{ id: 4 }] } }).options.body.items).toEqual([{ taxpayerId: 4, taxableBase: 0 }]);
    expect(route("/api/v1/settlements/batch", { method: "POST", body: { taxConceptId: 3 } }).options.body.items).toEqual([]);

    expect(route("/api/v1/settlements/12").path).toBe("/api/v1/liquidations/12");
    expect(query("/api/v1/settlements?taxpayerId=1&status=ISSUED&from=2026-01-01")).toMatchObject({ taxpayerId: "1", status: "ISSUED", from: "2026-01-01" });
  });

  it("traduce estado de cuenta, deudas vencidas y ajustes", () => {
    expect(route("/api/v1/taxpayers/7/account-statement").path).toBe("/api/v1/taxpayers/7/summary");
    expect(route("/api/v1/debts/report-overdue").path).toBe("/api/v1/debts?status=OVERDUE&size=100");

    expect(route("/api/v1/debt-adjustments", { method: "POST", body: { debtId: 1, type: "BONIFICACION", amount: 5, reason: "r" } }).options.body).toEqual({ debtId: 1, type: "DISCOUNT", amount: 5, reason: "r" });
    expect(route("/api/v1/debt-adjustments", { method: "POST", body: { debtId: 1, type: "OTRO" } }).options.body.type).toBe("OTRO");
    expect(route("/api/v1/debt-adjustments/4/reject", { method: "POST" })).toMatchObject({ path: "/api/v1/adjustments/4/reject", options: { body: { reason: "Rechazado" } } });
    expect(route("/api/v1/debt-adjustments/4/reject", { method: "POST", body: { reason: "x" } }).options.body).toEqual({ reason: "x" });
    expect(route("/api/v1/debt-adjustments/4/approve", { method: "POST", body: { reason: "ok" } }).options.body).toEqual({ observation: "ok" });
    expect(route("/api/v1/debt-adjustments/4/approve", { method: "POST" }).options.body).toEqual({ observation: null });
    expect(route("/api/v1/debt-adjustments/4/execute", { method: "POST" })).toMatchObject({ path: "/api/v1/adjustments/4", options: { method: "GET" } });
  });

  it("arma boletas y pagos con o sin imputación explícita", () => {
    expect(route("/api/v1/bills", { method: "POST", body: { taxpayerId: 1, debtIds: [2, 3], dueDate: "d" } }).options.body).toEqual({ taxpayerId: 1, debtIds: [2, 3], dueDate: "d" });
    expect(route("/api/v1/bills", { method: "POST", body: { taxpayerId: 1, debtId: 4 } }).options.body.debtIds).toEqual([4]);
    expect(route("/api/v1/bills", { method: "POST", body: { taxpayerId: 1 } }).options.body.debtIds).toEqual([]);

    const allocations = [{ debtId: 2, installmentId: 1, amount: 3 }];
    expect(route("/api/v1/payments", { method: "POST", body: { taxpayerId: 1, billId: 8, amount: 3, paymentMethod: "CARD", allocations } }).options.body)
      .toEqual({ taxpayerId: 1, billId: 8, paymentMethod: "CARD", amount: 3, allocations });
    expect(route("/api/v1/payments", { method: "POST", body: { taxpayerId: 1, amount: 3, method: "CASH" } }).options.body).toMatchObject({ billId: null, allocations: [] });
  });

  it("no traduce un estado de pago que el backend ya entiende", () => {
    expect(query("/api/v1/payments?status=REVERSED&method=CASH")).toMatchObject({ status: "REVERSED", method: "CASH" });
    expect(query("/api/v1/payments?taxpayerId=3")).toEqual({ size: "100", taxpayerId: "3" });
  });

  it("traduce imputaciones, reversiones y saldos a favor", () => {
    expect(route("/api/v1/payments/5/allocate", { method: "POST", body: { amountApplied: 9 } })).toMatchObject({ path: "/api/v1/payments/5/allocations", options: { body: { debtId: null, installmentId: null, amount: 9 } } });
    expect(route("/api/v1/payments/5/allocate", { method: "POST", body: { debtId: 1, installmentId: 2, amount: 3 } }).options.body).toEqual({ debtId: 1, installmentId: 2, amount: 3 });
    expect(route("/api/v1/payments/5/reverse", { method: "POST", body: { reason: "error" } })).toMatchObject({ path: "/api/v1/payments/5/reversal-requests", options: { body: { reason: "error" } } });
    expect(route("/api/v1/payments/5/reverse", { method: "POST" }).options.body).toEqual({ reason: undefined });

    expect(route("/api/v1/credit-balances/3/applicable-debts?taxpayerId=7").path).toBe("/api/v1/taxpayers/7/debts?size=100");
    expect(route("/api/v1/credit-balances/3/applicable-debts").path).toBe("/api/v1/debts?size=100");
    expect(route("/api/v1/credit-balances/3/applications", { method: "POST", body: { debtId: 1, amountApplied: 4 } }).options.body).toEqual({ debtId: 1, amount: 4 });
    expect(route("/api/v1/credit-balances/3/applications", { method: "POST", body: { debtId: 1, amount: 6 } }).options.body).toEqual({ debtId: 1, amount: 6 });
    expect(route("/api/v1/credit-balances/3/applications", { method: "POST" }).options.body).toEqual({ debtId: undefined, amount: undefined });
  });

  it("traduce el flujo de planes de pago y refinanciaciones", () => {
    const plan = { taxpayerId: 1, debtIds: [2], installments: 6 };
    expect(route("/api/v1/payment-plans/simulate", { method: "POST", body: plan })).toMatchObject({ path: "/api/v1/payment-plans/simulations", options: { body: plan } });
    expect(route("/api/v1/payment-plans", { method: "POST", body: plan })).toMatchObject({ path: "/api/v1/payment-plan-requests", options: { body: plan } });
    expect(route("/api/v1/payment-plans/8/escalate", { method: "POST", body: { note: "n" } })).toMatchObject({ path: "/api/v1/payment-plan-requests/8/submit-exception", options: { body: { reason: "n" } } });
    expect(route("/api/v1/payment-plans/8/escalate", { method: "POST", body: { reason: "r" } }).options.body).toEqual({ reason: "r" });
    expect(route("/api/v1/payment-plans/8/approve", { method: "POST", body: { downPayment: 100 } })).toMatchObject({ path: "/api/v1/payment-plan-requests/8/grant", options: { body: { downPaymentAmount: 100 } } });
    expect(route("/api/v1/payment-plans/8/approve", { method: "POST" }).options.body).toEqual({ downPaymentAmount: 0 });
    expect(route("/api/v1/payment-plans/8/reject", { method: "POST" })).toMatchObject({ path: "/api/v1/payment-plan-requests/8/reject", options: { body: { reason: "Rechazado" } } });
    expect(route("/api/v1/payment-plans/8/reject", { method: "POST", body: { reason: "x" } }).options.body).toEqual({ reason: "x" });
    expect(route("/api/v1/payment-plans/8/refinancing", { method: "POST", body: { installments: 3, extra: 1 } })).toMatchObject({ path: "/api/v1/payment-plans/8/refinancing-requests", options: { body: { installments: 3 } } });

    expect(route("/api/v1/refinancings/3/escalate", { method: "POST", body: { reason: "r" } })).toMatchObject({ path: "/api/v1/refinancing-requests/3/submit-exception", options: { body: { reason: "r" } } });
    expect(route("/api/v1/refinancings/3/approve", { method: "POST", body: { a: 1 } })).toMatchObject({ path: "/api/v1/refinancing-requests/3/grant", options: { body: { a: 1 } } });
    expect(route("/api/v1/refinancings/3/reject", { method: "POST" }).options.body).toEqual({ reason: "Rechazado" });
    expect(route("/api/v1/refinancings/3/reject", { method: "POST", body: { reason: "x" } }).options.body).toEqual({ reason: "x" });
  });

  it("traduce el flujo de exenciones", () => {
    expect(route("/api/v1/exemptions/requests/4").path).toBe("/api/v1/exemption-requests/4");
    for (const action of ["start-review", "request-documentation", "documentation", "submit-resolution", "approve", "reject"]) {
      expect(route(`/api/v1/exemptions/4/${action}`, { method: "POST" }).path).toBe(`/api/v1/exemption-requests/4/${action}`);
    }
    expect(route("/api/v1/exemptions", { method: "POST", body: { citizenId: 1, conceptId: 2, reason: "r", requestedPercentage: 50, requestedFrom: "a", requestedUntil: "b" } }))
      .toMatchObject({ path: "/api/v1/exemption-requests", options: { body: { taxpayerId: 1, taxConceptId: 2, percentage: 50, validFrom: "a", validUntil: "b" } } });
    expect(route("/api/v1/exemptions", { method: "POST", body: { taxpayerId: 1, taxConceptId: 2, percentage: 100, validFrom: "a", validUntil: "c" } }).options.body)
      .toMatchObject({ taxpayerId: 1, taxConceptId: 2, percentage: 100, validUntil: "c" });
    expect(route("/api/v1/exemptions", { method: "POST", body: { taxpayerId: 1 } }).options.body.validUntil).toBeNull();
  });

  it.each([
    [{ status: "ASSIGNED" }, "assign", undefined],
    [{ status: "IN_PROGRESS", reason: "trabajando" }, "updates", { message: "trabajando" }],
    [{ status: "IN_PROGRESS" }, "updates", { message: "IN_PROGRESS" }],
    [{ status: "INFORMATION_REQUIRED", reason: "falta" }, "request-information", { message: "falta" }],
    [{ status: "COMPLETED", reason: "listo" }, "complete", { resolution: "listo" }],
    [{ status: "COMPLETED" }, "complete", { resolution: "Completado" }],
    [{ status: "REJECTED", reason: "no" }, "reject", { reason: "no" }],
    [{ status: "REJECTED" }, "reject", { reason: "Rechazado" }],
    [{ status: "OTRO" }, "updates", { message: "OTRO" }],
  ])("traduce el cambio de estado del ticket %o", (body, action, expected) => {
    expect(route("/api/v1/tickets/12/status", { method: "POST", body })).toMatchObject({ path: `/api/v1/tickets/12/${action}`, options: { body: expected } });
  });

  it("traduce eventos de integración", () => {
    expect(route("/api/v1/events/5/retry", { method: "POST" }).path).toBe("/api/v1/integrations/events/5/reprocess");
    expect(route("/api/v1/events/5").path).toBe("/api/v1/integrations/events/5");
    expect(query("/api/v1/events?direction=OUTBOUND&eventType=debtSettled")).toMatchObject({ direction: "OUTBOUND", eventType: "debtSettled" });
  });

  it("traduce cada pantalla de caja", () => {
    expect(query("/api/v1/cashier/search?query=Ana")).toEqual({ size: "100", q: "Ana" });
    expect(route("/api/v1/cashier/charge-context/DEBT/3").path).toBe("/api/v1/debts/3");
    expect(route("/api/v1/cashier/charge-context/BILL/4").path).toBe("/api/v1/bills/4");
    expect(route("/api/v1/cashier/charge-context/TAXPAYER/5").path).toBe("/api/v1/taxpayers/5");
    expect(route("/api/v1/cashier/payments", { method: "POST", body: { taxpayerId: 1, amountPaid: 2, method: "CASH", debtId: 3 } }))
      .toMatchObject({ path: "/api/v1/payments", options: { body: { amount: 2, paymentMethod: "CASH", allocations: [{ debtId: 3, installmentId: null, amount: 2 }] } } });
    expect(route("/api/v1/cashier/taxpayers/7/file").path).toBe("/api/v1/taxpayers/7/summary");
    expect(route("/api/v1/cashier/agents").path).toBe("/api/v1/health");
    const today = query("/api/v1/cashier/daily-summary");
    expect(today.from).toBe(today.to);
    expect(today.from).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });

  it.each([
    ["/api/v1/audit/taxpayers?query=Ana&type=CITIZEN", "/api/v1/taxpayers", { q: "Ana", type: "CITIZEN" }],
    ["/api/v1/audit/concepts?query=TASA", "/api/v1/tax-concepts", { q: "TASA" }],
    ["/api/v1/audit/integrations?sourceModule=M7&status=DLQ", "/api/v1/integrations/events", { sourceModule: "M7", status: "DLQ" }],
    ["/api/v1/audit/trail?username=ana&role=AUDITOR&action=UPDATE", "/api/v1/audit", { userId: "ana", userRole: "AUDITOR", action: "UPDATE" }],
    ["/api/v1/audit/payments?method=CASH&from=2026-01-01", "/api/v1/payments", { method: "CASH", from: "2026-01-01" }],
    ["/api/v1/audit/tickets?priority=HIGH", "/api/v1/tickets", { priority: "HIGH" }],
  ])("filtra la consulta de auditoría %s", (legacy, pathname, params) => {
    const url = new URL(route(legacy).path, "http://test");
    expect(url.pathname).toBe(pathname);
    expect(Object.fromEntries(url.searchParams)).toMatchObject(params);
  });

  it("traduce el detalle de auditoría y deja pasar rutas desconocidas", () => {
    expect(route("/api/v1/audit/concepts/3").path).toBe("/api/v1/tax-concepts/3");
    expect(route("/api/v1/audit/reversals").path).toBe("/api/v1/payment-reversals");
    expect(route("/api/v1/audit/otra-cosa").path).toBe("/api/v1/audit/otra-cosa");
  });

  it.each([
    ["account-summary", "/api/v1/taxpayers/7/summary"],
    ["notices", "/api/v1/taxpayers/7/debts?size=100"],
    ["payments", "/api/v1/taxpayers/7/payments?size=100"],
    ["payment-plans", "/api/v1/taxpayers/7/payment-plan-requests?size=100"],
    ["exemptions", "/api/v1/taxpayers/7/exemption-requests?size=100"],
    ["otro", "/api/v1/portal/7/otro"],
  ])("traduce el recurso del portal %s", (resource, expected) => {
    expect(route(`/api/v1/portal/7/${resource}`).path).toBe(expected);
  });

  it("deja pasar rutas del portal sin contribuyente y rutas sin traducción", () => {
    expect(route("/api/v1/portal/me").path).toBe("/api/v1/portal/me");
    expect(route("/api/v1/health").path).toBe("/api/v1/health");
  });

  it("traduce los filtros de etapa de exenciones y de boletas saldadas", () => {
    expect(query("/api/v1/exemption-requests?internalStatus=DOCUMENTATION_REQUIRED")).toMatchObject({ status: "DOCUMENTATION_REQUIRED" });
    expect(query("/api/v1/exemption-requests?status=REQUESTED&internalStatus=PENDING_RESOLUTION")).toMatchObject({ status: "PENDING_RESOLUTION" });
    expect(query("/api/v1/exemption-requests?status=REQUESTED&internalStatus=DESCONOCIDO")).not.toHaveProperty("status");
    expect(query("/api/v1/payment-plan-requests?tab=GRANTED&internalStatus=PENDING_REVIEW")).toMatchObject({ status: "GRANTED" });
    expect(query("/api/v1/payment-plan-requests?internalStatus=PENDING_REVIEW")).toMatchObject({ status: "PENDING" });
    expect(query("/api/v1/bills/search?query=1&status=SETTLED").status).toBe("PAID");
  });
});

describe("adaptApiResponse: filas del backend hacia las pantallas", () => {
  const one = (actual, row, original = "x") => adaptApiResponse(original, actual, row);

  it("desarma páginas vacías, listas y metadatos sin last", () => {
    expect(pageItems(null)).toEqual([]);
    expect(pageItems(undefined)).toEqual([]);
    const list = [{ id: 1 }];
    expect(pageItems(list)).toBe(list);
    expect(pageItems({ id: 1 })).toEqual({ id: 1 });
    expect(pageItems({ content: [], number: 0, size: 10, totalElements: 0, totalPages: 0 }).page.last).toBe(true);
    expect(one("/api/v1/debts/1", 5)).toBe(5);
    expect(one("/api/v1/debts", [null])).toEqual([null]);
  });

  it("arma avisos con el importe nuevo y descarta los no vencidos o cancelados", () => {
    const notices = adaptApiResponse("/api/v1/portal/1/notices", "/api/v1/taxpayers/1/debts", [
      { id: 1, overdue: true, status: "PENDING", outstandingAmount: 10 },
      { id: 2, overdue: false, status: "PENDING", outstandingAmount: 10 },
      { id: 3, overdue: true, status: "CANCELLED", outstandingAmount: 10 },
    ]);
    expect(notices.map((notice) => notice.id)).toEqual(["deuda-1"]);
  });

  it("resume la cuenta del portal sin inventar datos ausentes", () => {
    expect(adaptApiResponse("/api/v1/portal/1/account-summary", "/api/v1/taxpayers/1/summary", { outstandingDebt: 50, openDebts: 2, activePlans: 1 }))
      .toMatchObject({ totalDebt: 50, overdueDebt: null, counts: { debts: 2, openRequests: 1, overdue: null } });
    expect(adaptApiResponse("/api/v1/portal/1/account-summary", "/api/v1/taxpayers/1/summary", {}))
      .toMatchObject({ totalDebt: null, counts: { debts: null, openRequests: null } });
    expect(adaptApiResponse("/api/v1/cashier/agents", "/api/v1/health", { status: "UP" })).toEqual([]);
  });

  it("muestra indicadores vacíos como no disponibles", () => {
    expect(adaptApiResponse("/api/v1/dashboard/metrics", "/api/v1/indicators/summary", {})).toMatchObject({ deudas: null, pagos: null, totalOverdueAmount: null, unallocatedPaymentAmount: null });
    expect(adaptApiResponse("/api/v1/audit/dashboard", "/api/v1/indicators/summary", {}).paymentsRegistered).toBe(0);
    expect(adaptApiResponse("/api/v1/audit/indicators", "/api/v1/indicators/summary", {})).toMatchObject({ totalCollected: 0, pendingDebt: 0, overdueDebt: 0, delinquencyRate: 0, counts: { totalCollected: 0, pendingDebt: 0, overdueDebt: 0 } });
    expect(adaptApiResponse("/api/v1/audit/indicators", "/api/v1/indicators/summary", {
      debt: { openCount: 5, outstandingAmount: 100 }, delinquency: { overdueDebtCount: 2, overdueAmount: 40, overduePercentage: 12 },
    })).toMatchObject({ pendingDebt: 60, overdueDebt: 40, delinquencyRate: 12, counts: { pendingDebt: 3, overdueDebt: 2 } });
  });

  it("adapta conceptos, configuraciones y liquidaciones con datos completos", () => {
    expect(one("/api/v1/tax-concepts/1", { type: "FINE", active: false, versions: [1] })).toMatchObject({ type: "MULTA", status: "INACTIVE", versions: [1] });
    expect(one("/api/v1/tax-concepts/1", { type: "OTRO", active: true }).type).toBe("OTRO");
    expect(one("/api/v1/tax-configurations/1", { calculationType: "OTRO", taxConceptId: 2 }).calculationType).toBe("OTRO");
    expect(one("/api/v1/liquidations/1", { conceptName: "Tasa", taxpayerName: "Ana", origin: { module: "M4" } })).toMatchObject({ conceptName: "Tasa", taxpayerName: "Ana", origin: { module: "M4" } });
  });

  it("adapta deudas con datos propios y estados cancelados", () => {
    const debt = one("/api/v1/debts/1", { conceptCode: "TS", conceptName: "Tasa", taxpayerName: "Ana", externalObligationId: 9, daysLeft: 3, overdue: true, status: "CANCELLED" });
    expect(debt).toMatchObject({ conceptCode: "TS", conceptName: "Tasa", taxpayerName: "Ana", originId: 9, daysLeft: 3, status: "CANCELLED" });
    expect(one("/api/v1/debts/1", { liquidationId: 4, status: "PENDING" })).toMatchObject({ originId: 4, settlementId: 4, daysLeft: null, status: "PENDING", conceptCode: "#undefined" });
  });

  it("adapta boletas con nombre propio, sin deudas y sin fecha", () => {
    expect(one("/api/v1/bills/1", { id: 1, conceptName: "Boleta X", issueDate: "2026-01-01", daysLeft: 2, status: "ISSUED", number: "123" }))
      .toMatchObject({ conceptName: "Boleta X", issuedAt: "2026-01-01", daysLeft: 2, status: "ISSUED", barcode: "123", debtId: null, documentUrl: "/api/v1/bills/1/document" });
    expect(one("/api/v1/bills/2", { id: 2, status: "PAID" })).toMatchObject({ conceptName: "Boleta municipal", status: "SETTLED", daysLeft: null });
  });

  it("devuelve tal cual imputaciones, pagos electrónicos y simulaciones", () => {
    const row = { id: 1, amount: 2 };
    expect(one("/api/v1/payments/1/allocations", row)).toEqual(row);
    expect(one("/api/v1/electronic-payments/1", row)).toEqual(row);
    expect(one("/api/v1/payment-plans/simulations", row)).toEqual(row);
    expect(one("/api/v1/payment-plans/8/refinancing/simulations", row)).toEqual(row);
  });

  it("adapta pagos revertidos, saldos a favor y ajustes", () => {
    expect(one("/api/v1/payments/1", { taxpayerName: "Ana", status: "REVERSED", origin: "OTRO", unallocatedAmount: 5 })).toMatchObject({ taxpayerName: "Ana", status: "REVERSED", channel: "OTRO" });
    expect(one("/api/v1/credit-balances/1", { originalAmount: 10, availableAmount: 4, createdAt: "d" })).toMatchObject({ amount: 10, remainingAmount: 4, generatedAt: "d" });
    expect(one("/api/v1/adjustments/3", { id: 3 })).toMatchObject({ requestId: 3 });
  });

  it("adapta solicitudes de plan y refinanciación con datos completos", () => {
    expect(one("/api/v1/payment-plan-requests/1", { id: 1, status: "GRANTED", debtIds: [2], debts: [{ id: 2 }], totalDebt: 10, downPayment: 3, paymentPlanId: 7 }))
      .toMatchObject({ debtIds: [2], debts: [{ id: 2 }], totalDebt: 10, downPayment: 3, planId: 7, status: "GRANTED" });
    expect(one("/api/v1/payment-plan-requests/1", { id: 1, status: "REJECTED" }).downPayment).toBe(0);
    expect(one("/api/v1/refinancing-requests/2", { id: 2, status: "PENDING", originalPlanId: 5, requestedInstallments: 4, outstandingPrincipalAtRequest: 30, estimatedTotalAmount: 33 }))
      .toMatchObject({ requestId: 2, planId: 5, installments: 4, outstandingAmount: 30, totalAmount: 33, internalStatus: "PENDING_REVIEW" });
  });

  it("preserva estados de cuotas y planes que no conoce", () => {
    expect(one("/api/v1/payment-plans/1/installments", { status: "OTRO" }).status).toBe("OTRO");
    expect(one("/api/v1/payment-plans/1", { id: 1, status: "OTRO", installmentCount: 6, totalPlanAmount: 60 })).toMatchObject({ lifecycle: "OTRO", installments: 6, totalAmount: 60 });
  });

  it("adapta exenciones en revisión, resueltas y con datos propios", () => {
    expect(one("/api/v1/exemption-requests/1", { id: 1, status: "UNDER_REVIEW" })).toMatchObject({ status: "REQUESTED", internalStatus: "PENDING_REVIEW", hasSocialBenefit: false });
    expect(one("/api/v1/exemption-requests/1", {
      id: 1, requestId: 9, status: "APPROVED", conceptCode: "TS", conceptName: "Tasa", requestedPercentage: 50, requestedFrom: "a", requestedUntil: "b", attachments: [1], hasSocialBenefit: true,
    })).toMatchObject({ requestId: 9, status: "APPROVED", internalStatus: "APPROVED", conceptCode: "TS", conceptName: "Tasa", requestedPercentage: 50, requestedFrom: "a", requestedUntil: "b", attachments: [1], hasSocialBenefit: true });
    expect(one("/api/v1/exemption-requests/1", { id: 1, status: "REJECTED", percentage: 20, validFrom: "c", validUntil: "d" })).toMatchObject({ requestedPercentage: 20, requestedFrom: "c", requestedUntil: "d" });
  });

  it("adapta tickets, eventos y auditoría", () => {
    expect(one("/api/v1/tickets/1", { id: 1, taxpayerId: 2, category: "RENTAS" })).toMatchObject({ ticketId: 1, citizenId: 2, subject: "RENTAS", attachments: [], additionalInformation: null });
    expect(one("/api/v1/tickets/1", { id: 1, attachments: [1], additionalInformation: "info" })).toMatchObject({ attachments: [1], additionalInformation: "info" });
    expect(one("/api/v1/integrations/events/1", { status: "DLQ", targetModule: "M7", retryCount: 3, errorMessage: "boom" })).toMatchObject({ result: "FAILED", destinationModule: "M7", attempts: 3, error: "boom" });
    expect(one("/api/v1/integrations/events/1", { status: "PROCESSED" })).toMatchObject({ result: "SUCCESS", error: null });
    expect(one("/api/v1/audit/entities/Debt/1", { occurredAt: "t", userId: "ana", correlationId: "c" })).toMatchObject({ at: "t", actor: "ana", note: "c" });
    expect(one("/api/v1/audit/entities/Debt/1", { occurredAt: "t" }).note).toBeNull();
  });

  it("lista los valores auditados aunque no sean JSON válido", () => {
    const row = one("/api/v1/audit/5", { previousData: "{\"a\":1}", newData: "texto plano", correlationId: "c-1" });
    expect(row).toMatchObject({ before: { a: 1 }, after: { valor: "texto plano" }, references: ["c-1"] });
    expect(one("/api/v1/audit/5", { previousData: { b: 2 }, newData: null })).toMatchObject({ before: { b: 2 }, after: null, references: [] });
  });

  it("identifica contribuyentes por CUIT y deja pasar recursos sin mapeo", () => {
    expect(one("/api/v1/taxpayers/1", { cuit: "30-1", displayName: "SA" })).toMatchObject({ documentType: "CUIT", document: "30-1", name: "SA" });
    expect(one("/api/v1/otra-cosa/1", { id: 1 })).toEqual({ id: 1 });
  });

  it("filtra listados por etapa, cajero y estado de boleta", () => {
    const requests = adaptApiResponse("/api/v1/exemption-requests?internalStatus=PENDING_REVIEW", "/api/v1/exemption-requests", [
      { id: 1, status: "UNDER_REVIEW" }, { id: 2, status: "PENDING" },
    ]);
    expect(requests.map((row) => row.id)).toEqual([1]);
    const payments = adaptApiResponse("/api/v1/payments?status=REGISTERED", "/api/v1/payments", [
      { id: 1, status: "CONFIRMED", unallocatedAmount: 0 }, { id: 2, status: "CONFIRMED", unallocatedAmount: 5 },
    ]);
    expect(payments.map((row) => row.id)).toEqual([1]);
    const bills = [{ id: 1, status: "PAID" }, { id: 2, status: "ISSUED" }];
    expect(adaptApiResponse("/api/v1/portal/1/bills?status=PAID", "/api/v1/taxpayers/1/bills", bills).map((row) => row.id)).toEqual([1]);
    expect(adaptApiResponse("/api/v1/portal/1/bills?status=ISSUED", "/api/v1/taxpayers/1/bills", bills).map((row) => row.id)).toEqual([2]);
  });

  it("resume la caja del día con reversiones y pagos sin medio", () => {
    const summary = adaptApiResponse("/api/v1/cashier/daily-summary", "/api/v1/payments", [
      { id: 1, status: "REVERSED", amount: 10, unallocatedAmount: 0, receiptNumber: "R1", paidAt: "t1" },
      { id: 2, status: "CONFIRMED", amount: 5, unallocatedAmount: 0, receiptNumber: "R2", paidAt: "t2" },
      { id: 3, status: "CONFIRMED", paymentMethod: "CASH", unallocatedAmount: 0, receiptNumber: "R3" },
    ]);
    expect(summary).toMatchObject({ registeredCount: 2, totalCollected: 5, reversedCount: 1, pendingCount: 0 });
    expect(summary.date).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(summary.byMethod).toEqual([{ method: "SIN_MEDIO", count: 1, total: 5 }, { method: "CASH", count: 1, total: 0 }]);
    expect(summary.activity.map((item) => item.description)).toEqual(["Reversión de R1", "Cobro R2", "Cobro R3"]);
  });
});
