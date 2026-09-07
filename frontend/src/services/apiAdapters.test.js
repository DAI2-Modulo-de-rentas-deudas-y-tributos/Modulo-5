import { beforeEach, describe, expect, it, vi } from "vitest";
import { createElement } from "react";
import { cleanup, render, screen } from "@testing-library/react";
import { adaptApiRequest, adaptApiResponse, enumMappings, pageItems } from "./apiAdapters.js";

describe("API adapters", () => {
  it("unwraps Spring Page and retains pagination metadata", () => {
    const rows = pageItems({ content: [{ id: 1 }], number: 2, size: 20, totalElements: 52, totalPages: 3, last: true });
    expect(rows).toEqual([{ id: 1 }]);
    expect(rows.page).toEqual({ number: 2, size: 20, totalElements: 52, totalPages: 3, last: true });
  });

  it("retains the nested Spring PagedModel metadata after mapping rows", () => {
    const rows = adaptApiResponse("/api/v1/debts", "/api/v1/debts", {
      content: [{ id: 1, status: "PAID", outstandingBalance: 0 }],
      page: { number: 0, size: 20, totalElements: 21, totalPages: 2 },
    });

    expect(rows[0].status).toBe("SETTLED");
    expect(rows.page).toEqual({ number: 0, size: 20, totalElements: 21, totalPages: 2, last: false });
  });

  it("maps taxpayer DTO without inventing missing data", () => {
    const value = adaptApiResponse("/api/v1/taxpayers/1", "/api/v1/taxpayers/1", { id: 1, taxpayerType: "CITIZEN", dni: "123", cuit: null, displayName: "Ana", status: "ACTIVE" });
    expect(value).toMatchObject({ id: 1, type: "CITIZEN", documentType: "DNI", document: "123", name: "Ana" });
  });

  it("maps configuration enums bidirectionally", () => {
    expect(enumMappings.CALC_TO_API.PORCENTAJE).toBe("PERCENTAGE");
    const value = adaptApiResponse("/api/v1/tax-config", "/api/v1/tax-configurations", { calculationType: "FIXED", taxConceptId: 4 });
    expect(value).toMatchObject({ calculationType: "FIJO", conceptId: 4 });
  });

  it("derives legacy overdue and settled states from backend debt", () => {
    expect(adaptApiResponse("x", "/api/v1/debts/1", { status: "PAID", overdue: false, outstandingBalance: 0 }).status).toBe("SETTLED");
    expect(adaptApiResponse("x", "/api/v1/debts/2", { status: "PENDING", overdue: true, outstandingBalance: 10 }).status).toBe("OVERDUE");
  });

  it("maps confirmed and unallocated payments", () => {
    const allocated = adaptApiResponse("x", "/api/v1/payments/1", { status: "CONFIRMED", paymentMethod: "CASH", origin: "CASHIER", amount: 100, unallocatedAmount: 0 });
    const credit = adaptApiResponse("x", "/api/v1/payments/2", { status: "CONFIRMED", paymentMethod: "CARD", origin: "CASHIER", amount: 120, unallocatedAmount: 20 });
    expect(allocated).toMatchObject({ status: "REGISTERED", amountPaid: 100, method: "EFECTIVO", channel: "VENTANILLA" });
    expect(credit).toMatchObject({ status: "UNALLOCATED", remainingBalance: 20, method: "TARJETA" });
  });

  it("maps real debt, plan, installment and exemption workflow enums", () => {
    expect(adaptApiResponse("x", "/api/v1/debts/1", { originType: "LIQUIDATION", status: "PAID", outstandingBalance: 0 })).toMatchObject({ originType: "SETTLEMENT", status: "SETTLED" });
    expect(adaptApiResponse("x", "/api/v1/payment-plans/1", { id: 1, status: "ACTIVE" }).lifecycle).toBe("CURRENT");
    expect(adaptApiResponse("x", "/api/v1/payment-plans/1/installments", { id: 1, status: "PARTIALLY_PAID" }).status).toBe("PARTIAL");
    expect(adaptApiResponse("x", "/api/v1/exemption-requests/1", { id: 1, taxConceptId: 2, status: "PENDING" })).toMatchObject({ status: "REQUESTED", internalStatus: "PENDING_REVIEW", attachments: [] });
  });

  it("preserves an unknown exemption status instead of silently presenting it as requested", () => {
    expect(adaptApiResponse("x", "/api/v1/exemption-requests/1", {
      id: 1,
      taxConceptId: 2,
      status: "UNKNOWN_STAGE",
    }).status).toBe("UNKNOWN_STAGE");
  });

  it("adapts nested taxpayer resources by their resource type", () => {
    const debts = adaptApiResponse("/api/v1/portal/7/debts", "/api/v1/taxpayers/7/debts?size=100", {
      content: [{ id: 1, taxpayerId: 7, taxConceptId: 2, dueDate: "2026-09-30", status: "PAID", outstandingBalance: 0 }],
      page: { number: 0, size: 100, totalElements: 1, totalPages: 1 },
    });
    const payments = adaptApiResponse("/api/v1/portal/7/payments", "/api/v1/taxpayers/7/payments?size=100", {
      content: [{ id: 2, taxpayerId: 7, status: "CONFIRMED", paymentMethod: "CASH", amount: 50, unallocatedAmount: 0 }],
      page: { number: 0, size: 100, totalElements: 1, totalPages: 1 },
    });

    expect(debts[0]).toMatchObject({ status: "SETTLED", outstandingAmount: 0, conceptName: "Concepto #2" });
    expect(Number.isInteger(debts[0].daysLeft)).toBe(true);
    expect(payments[0]).toMatchObject({ status: "REGISTERED", amountPaid: 50, method: "EFECTIVO" });
  });

  it("supplies safe collections for real payment plan request DTOs", () => {
    const request = adaptApiResponse("x", "/api/v1/payment-plan-requests/1", {
      id: 1,
      totalDebtAtRequest: 100,
      requestedInstallments: 2,
      estimatedDownPayment: 0,
    });

    expect(request).toMatchObject({ requestId: 1, debtIds: [], debts: [], totalDebt: 100, installments: 2, downPayment: 0 });
  });

  it("builds the cashier dashboard from real payment rows", () => {
    const summary = adaptApiResponse(
      "/api/v1/cashier/daily-summary?registeredBy=pcabrera",
      "/api/v1/payments?size=100",
      { content: [
        { id: 1, registeredBy: "pcabrera", status: "CONFIRMED", paymentMethod: "CASH", amount: 100, unallocatedAmount: 0 },
        { id: 2, registeredBy: "pcabrera", status: "CONFIRMED", paymentMethod: "CARD", amount: 120, unallocatedAmount: 20 },
      ], page: { number: 0, size: 100, totalElements: 2, totalPages: 1 } },
    );

    expect(summary).toMatchObject({ registeredCount: 2, totalCollected: 220, pendingCount: 1, reversedCount: 0 });
    expect(summary.latest).toHaveLength(2);
    expect(summary.activity).toHaveLength(2);
  });

  it("returns complete numeric auditor dashboard and indicator shapes", () => {
    const payload = {
      collection: { paymentCount: 3, confirmedAmount: 260 },
      debt: { openCount: 2, outstandingAmount: 160 },
      delinquency: { overdueDebtCount: 0, overdueAmount: 0, overduePercentage: 0 },
    };
    const dashboard = adaptApiResponse("/api/v1/audit/dashboard", "/api/v1/indicators/summary", payload);
    const indicators = adaptApiResponse("/api/v1/audit/indicators?", "/api/v1/indicators/summary", payload);

    expect(dashboard).toMatchObject({ paymentsRegistered: 3, exemptionsApproved: 0, exemptionsRejected: 0, recentActivity: [] });
    expect(indicators).toMatchObject({ totalCollected: 260, pendingDebt: 160, overdueDebt: 0, delinquencyRate: 0, byPeriod: [], byConcept: [] });
  });

  it("adapts audit rows and safe concept/liquidation presentation fields", () => {
    const audit = adaptApiResponse("/api/v1/audit/trail", "/api/v1/audit?size=100", {
      content: [{ id: 1, userId: "agent", userRole: "ROLE_RENTAS", entityType: "Debt", entityId: "2", occurredAt: "2026-09-01T10:00:00Z" }],
      page: { number: 0, size: 100, totalElements: 1, totalPages: 1 },
    });
    const concept = adaptApiResponse("x", "/api/v1/tax-concepts/1", { id: 1, type: "FEE", active: true });
    const liquidation = adaptApiResponse("x", "/api/v1/liquidations/1", { id: 1, taxpayerId: 7, taxConceptId: 2, finalAmount: 10 });

    expect(audit[0]).toMatchObject({ username: "agent", entity: { type: "Debt", id: "2" }, result: "SUCCESS" });
    expect(concept.versions).toEqual([]);
    expect(liquidation.origin).toEqual({ module: "M5" });
  });

  it("maps settlement and payment requests to real DTOs", () => {
    const liquidation = adaptApiRequest("/api/v1/settlements", { method: "POST", body: { taxpayerId: 1, conceptId: 2, period: "2026-09", amount: 50, dueDate: "2026-09-30" } });
    expect(liquidation).toMatchObject({ path: "/api/v1/liquidations", options: { body: { taxpayerId: 1, taxConceptId: 2, taxableBase: 50 } } });
    const payment = adaptApiRequest("/api/v1/payments", { method: "POST", body: { taxpayerId: 1, debtId: 3, amountPaid: 100, method: "EFECTIVO" } });
    expect(payment.options.body).toMatchObject({ paymentMethod: "CASH", amount: 100, allocations: [{ debtId: 3, amount: 100 }] });
  });

  it.each([
    ["/api/v1/tax-config/pending", "/api/v1/tax-configurations"],
    ["/api/v1/settlements?period=2026-09", "/api/v1/liquidations"],
    ["/api/v1/debt-adjustments?status=APPROVED", "/api/v1/adjustments"],
    ["/api/v1/bills/search?query=10", "/api/v1/bills"],
    ["/api/v1/payments/7/reversal", "/api/v1/payments/7/reversal-requests"],
    ["/api/v1/credit-balances/2/applications", "/api/v1/credit-balances/2/apply"],
    ["/api/v1/payment-plans?status=PENDING", "/api/v1/payment-plan-requests"],
    ["/api/v1/events?status=FAILED", "/api/v1/integrations/events"],
    ["/api/v1/cashier/receipts/5", "/api/v1/payments/5/receipt"],
    ["/api/v1/audit/debts?status=OVERDUE", "/api/v1/debts"],
    ["/api/v1/portal/4/bills", "/api/v1/taxpayers/4/bills"],
    ["/api/v1/dashboard/metrics", "/api/v1/indicators/summary"],
  ])("routes legacy %s only to a real controller path", (legacy, realPrefix) => {
    expect(adaptApiRequest(legacy).path).toMatch(new RegExp(`^${realPrefix.replaceAll("/", "\\/")}`));
  });
});

describe("API client modes", () => {
  beforeEach(() => { vi.resetModules(); vi.unstubAllEnvs(); sessionStorage.clear(); vi.restoreAllMocks(); });

  it("adds dev role headers only for explicit mock-auth integration", async () => {
    vi.stubEnv("VITE_AUTH_MODE", "mock");
    vi.stubEnv("VITE_DEV_IDENTITY_HEADERS", "true");
    sessionStorage.setItem("rentas.user", JSON.stringify({ username: "jlopez", role: "SUPERVISOR" }));
    const { authHeaders } = await import("./apiClient.js");
    expect(authHeaders()).toMatchObject({ "X-Dev-User": "jlopez", "X-Dev-Roles": "RENTAS,SUPERVISOR" });
  });

  it.each([
    ["PERSONAL", "RENTAS"],
    ["SUPERVISOR", "RENTAS,SUPERVISOR"],
    ["CAJERO", "CASHIER"],
    ["AUDITOR", "AUDITOR"],
    ["CONTRIBUYENTE", "TAXPAYER"],
  ])("maps local role %s to backend authorities", async (role, authorities) => {
    vi.stubEnv("VITE_AUTH_MODE", "mock");
    vi.stubEnv("VITE_DEV_IDENTITY_HEADERS", "true");
    sessionStorage.setItem("rentas.user", JSON.stringify({ username: "user", role }));
    const { authHeaders } = await import("./apiClient.js");
    expect(authHeaders()["X-Dev-Roles"]).toBe(authorities);
  });

  it("adds taxpayer ownership header", async () => {
    vi.stubEnv("VITE_AUTH_MODE", "mock");
    vi.stubEnv("VITE_DEV_IDENTITY_HEADERS", "true");
    sessionStorage.setItem("rentas.user", JSON.stringify({ username: "jperez", role: "CONTRIBUYENTE", taxpayerId: 7 }));
    const { authHeaders } = await import("./apiClient.js");
    expect(authHeaders()["X-Dev-Taxpayer-Id"]).toBe("7");
  });

  it("sends no headers in Core mode while the Core/JWT contract is pending", async () => {
    vi.stubEnv("VITE_AUTH_MODE", "core");
    vi.stubEnv("VITE_DEV_IDENTITY_HEADERS", "true");
    sessionStorage.setItem("rentas.user", JSON.stringify({ username: "x", role: "SUPERVISOR" }));
    sessionStorage.setItem("rentas.token", "mock.eA==.token");
    const { authHeaders } = await import("./apiClient.js");
    expect(authHeaders()).toEqual({});
  });

  it("does not authenticate a persisted mock user in Core mode", async () => {
    vi.stubEnv("VITE_AUTH_MODE", "core");
    sessionStorage.setItem("rentas.user", JSON.stringify({ username: "jlopez", role: "SUPERVISOR" }));
    sessionStorage.setItem("rentas.token", "mock.eA==.token");
    const { AuthProvider, useAuth } = await import("../context/AuthContext.jsx");
    function AuthState() {
      return createElement("span", null, useAuth().isAuthenticated ? "authenticated" : "anonymous");
    }

    render(createElement(AuthProvider, null, createElement(AuthState)));
    expect(screen.getByText("anonymous")).toBeDefined();
    expect(sessionStorage.getItem("rentas.user")).not.toBeNull();
    cleanup();
  });

  it("does not add dev headers unless they are explicitly enabled", async () => {
    vi.stubEnv("VITE_AUTH_MODE", "mock");
    vi.stubEnv("VITE_DEV_IDENTITY_HEADERS", "false");
    sessionStorage.setItem("rentas.user", JSON.stringify({ username: "jlopez", role: "SUPERVISOR" }));
    const { authHeaders } = await import("./apiClient.js");
    expect(authHeaders()).toEqual({});
  });

  it("preserves backend error code and trace id", async () => {
    vi.stubEnv("VITE_USE_MOCKS", "false");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: "INVALID", message: "Dato inválido", traceId: "trace-1" }), { status: 400, headers: { "content-type": "application/json" } })));
    const { request } = await import("./apiClient.js");
    await expect(request("/api/v1/health")).rejects.toMatchObject({ status: 400, code: "INVALID", traceId: "trace-1" });
  });

  it("uses fetch, not mockDb, for business data in API mode", async () => {
    vi.stubEnv("VITE_USE_MOCKS", "false");
    vi.stubEnv("VITE_AUTH_MODE", "mock");
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ id: 999, taxpayerType: "CITIZEN", dni: "1", displayName: "Dato API" }), { status: 200, headers: { "content-type": "application/json" } }));
    vi.stubGlobal("fetch", fetchMock);
    const { taxpayerService } = await import("./rentasService.js");
    const taxpayer = await taxpayerService.getById(999);
    expect(fetchMock).toHaveBeenCalledOnce();
    expect(taxpayer.name).toBe("Dato API");
  });

  it("does not fall back to mockDb when an API-mode request has a network error", async () => {
    vi.stubEnv("VITE_USE_MOCKS", "false");
    vi.stubEnv("VITE_AUTH_MODE", "mock");
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("Failed to fetch")));
    const { taxpayerService } = await import("./rentasService.js");
    await expect(taxpayerService.getById(1)).rejects.toThrow("Failed to fetch");
  });

  it("authenticates against the backend when business API mode is active", async () => {
    vi.stubEnv("VITE_USE_MOCKS", "false");
    vi.stubEnv("VITE_AUTH_MODE", "mock");
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ token: "dev-session", user: { id: 9, username: "integration.user", displayName: "Integración", role: "RENTAS", authorities: ["RENTAS"], active: true } }), { status: 200, headers: { "content-type": "application/json" } }));
    vi.stubGlobal("fetch", fetchMock);
    const { authService } = await import("./rentasService.js");
    await expect(authService.login({ username: "integration.user", password: "clave-segura" }))
      .resolves.toMatchObject({ user: { fullName: "Integración", roleLabel: "Personal de Rentas", role: "PERSONAL", backendRole: "RENTAS", devAuthorities: ["RENTAS"] } });
    expect(fetchMock).toHaveBeenCalledOnce();
    expect(fetchMock.mock.calls[0][0]).toContain("/api/v1/dev-auth/login");
  });

  it("fails explicitly instead of using mock users in Core mode", async () => {
    vi.stubEnv("VITE_AUTH_MODE", "core");
    const { authService } = await import("./rentasService.js");
    await expect(authService.login({ username: "mrivas", password: "rentas123" }))
      .rejects.toMatchObject({ code: "CORE_AUTH_PENDING", status: 503 });
  });

  it("uses numeric zeroes for dashboard counts absent from the backend summary", () => {
    const result = adaptApiResponse("/api/v1/dashboard/metrics", "/api/v1/indicators/summary", {
      debt: { openCount: 2 },
      collection: { paymentCount: 3 },
    });

    expect(result.planes).toBe(0);
    expect(result.exenciones).toBe(0);
    expect(result.planes + result.exenciones).toBe(0);
  });
});
