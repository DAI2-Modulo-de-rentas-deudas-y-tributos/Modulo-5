import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import App from "../App.jsx";
import { ingresarComoAgente, ingresarComoContribuyente } from "./helpers/ingresar.js";
import { instalarBackendFalso, pagina } from "./fixtures/backendFalso.js";

const approvedReversal = {
  id: 720,
  paymentId: 9005,
  reason: "Pago duplicado verificado",
  status: "APPROVED",
  requestedBy: "pcabrera",
  requestedAt: "2026-09-19T10:00:00-03:00",
  resolvedBy: "jlopez",
  resolvedAt: "2026-09-20T09:00:00-03:00",
};

describe("lote 2 de tareas frontend", () => {
  let user;

  beforeEach(() => { user = userEvent.setup(); });
  afterEach(() => {
    cleanup();
    sessionStorage.clear();
    window.history.pushState({}, "", "/");
    vi.unstubAllGlobals();
  });

  it("Personal confirma y ejecuta una reversión ya autorizada", async () => {
    const backend = instalarBackendFalso({
      "GET /api/v1/payment-reversals": pagina([approvedReversal]),
      "GET /api/v1/payment-reversals/{id}": approvedReversal,
      "GET /api/v1/payments/{id}/allocations": [],
      "POST /api/v1/payment-reversals/{id}/execute": { ...approvedReversal, status: "EXECUTED", executedBy: "mrivas" },
    });
    render(<App />);
    await ingresarComoAgente(user, "mrivas");
    await user.click(await screen.findByRole("link", { name: /reversiones de pago/i }));
    await user.click(await screen.findByRole("button", { name: /^ejecutar$/i }));
    const dialog = await screen.findByRole("dialog", { name: /ejecutar reversión/i });
    expect(await within(dialog).findByText(/pago duplicado verificado/i)).toBeDefined();
    await user.click(within(dialog).getByRole("button", { name: /confirmar ejecución/i }));
    expect(await screen.findByText(/se ejecutó correctamente/i)).toBeDefined();
    expect(backend.llamadas.some((call) => call.metodo === "POST" && call.ruta === "/api/v1/payment-reversals/720/execute")).toBe(true);
  });

  it("Personal revisa cuotas vencidas y solicita la caducidad", async () => {
    const plan = { id: 44, taxpayerId: 123, status: "ACTIVE", installmentCount: 3, totalPlanAmount: 6000, paidAmount: 1000, outstandingPlanAmount: 5000 };
    const backend = instalarBackendFalso({
      "GET /api/v1/payment-plans/defaulted": pagina([plan]),
      "GET /api/v1/payment-plans/{id}": plan,
      "GET /api/v1/payment-plans/{id}/installments": [{ id: 1, paymentPlanId: 44, number: 1, totalAmount: 2000, paidAmount: 0, outstandingAmount: 2000, dueDate: "2026-07-01", status: "OVERDUE", overdue: true }],
      "POST /api/v1/payment-plans/{id}/expiration-requests": { id: 80, paymentPlanId: 44, reason: "Superó la tolerancia", status: "PENDING_APPROVAL" },
    });
    render(<App />);
    await ingresarComoAgente(user, "mrivas");
    await user.click(await screen.findByRole("link", { name: /caducidad de planes/i }));
    await user.click(await screen.findByRole("button", { name: /solicitar caducidad/i }));
    const dialog = await screen.findByRole("dialog", { name: /solicitar caducidad/i });
    expect(await within(dialog).findByText("Vencida")).toBeDefined();
    await user.type(within(dialog).getByLabelText(/motivo de la solicitud/i), "Superó la tolerancia");
    await user.click(within(dialog).getByRole("button", { name: /enviar solicitud/i }));
    expect(await screen.findByText(/quedó pendiente de autorización/i)).toBeDefined();
    expect(backend.llamadas.some((call) => call.metodo === "POST" && call.ruta === "/api/v1/payment-plans/44/expiration-requests")).toBe(true);
  });

  it("el Supervisor ve el detalle de una corrida y la aprueba", async () => {
    const run = { id: 8, taxConceptId: 1, period: "2026-09", dueDate: "2026-10-10", status: "PENDING_APPROVAL", totalItems: 2, validItems: 1, errorItems: 1, estimatedTotalAmount: 5000, createdBy: "mrivas", createdAt: "2026-09-20T10:00:00-03:00" };
    const backend = instalarBackendFalso({
      "GET /api/v1/liquidation-runs": pagina([run]),
      "GET /api/v1/liquidation-runs/{id}": { run, items: [{ id: 1, taxpayerId: 123, taxableBase: 5000, previewAmount: 5000, status: "VALID" }, { id: 2, taxpayerId: 456, taxableBase: 0, previewAmount: 0, status: "ERROR", errorMessage: "Base inválida" }] },
      "POST /api/v1/liquidation-runs/{id}/approve": { ...run, status: "APPROVED", resolvedBy: "jlopez" },
    });
    render(<App />);
    await ingresarComoAgente(user, "jlopez");
    await user.click(await screen.findByRole("link", { name: /corridas masivas/i }));
    await user.click(await screen.findByRole("button", { name: /resolver/i }));
    const dialog = await screen.findByRole("dialog", { name: /corrida masiva #8/i });
    expect(await within(dialog).findByText("Base inválida")).toBeDefined();
    await user.click(within(dialog).getByRole("button", { name: /aprobar corrida/i }));
    expect(await screen.findByText(/estado APPROVED/i)).toBeDefined();
    expect(backend.llamadas.some((call) => call.metodo === "POST" && call.ruta === "/api/v1/liquidation-runs/8/approve")).toBe(true);
  });

  it("Personal abre la causa de una obligación externa y la reintenta", async () => {
    const obligation = { id: 5, sourceModule: "M4", externalType: "COMMERCIAL_FINE", externalReferenceId: "M4-889", sourceEventId: "event-1", externalTaxpayerId: "40111222", taxpayerId: 123, taxConceptId: 2, amount: 15000, dueDate: "2026-10-10", status: "ERROR", errorMessage: "Concepto no vinculado", retryCount: 2, receivedAt: "2026-09-20T10:00:00-03:00" };
    const backend = instalarBackendFalso({
      "GET /api/v1/external-obligations": pagina([obligation]),
      "GET /api/v1/external-obligations/{id}": obligation,
      "POST /api/v1/external-obligations/{id}/retry": { ...obligation, status: "PROCESSED", retryCount: 3 },
    });
    render(<App />);
    await ingresarComoAgente(user, "mrivas");
    await user.click(await screen.findByRole("link", { name: /obligaciones externas/i }));
    await user.click(await screen.findByRole("button", { name: /ver traza/i }));
    const dialog = await screen.findByRole("dialog", { name: /obligación M4-889/i });
    expect(await within(dialog).findByText("Concepto no vinculado")).toBeDefined();
    await user.click(within(dialog).getByRole("button", { name: /reintentar procesamiento/i }));
    expect(await screen.findByText(/quedó en estado PROCESSED/i)).toBeDefined();
    expect(backend.llamadas.some((call) => call.metodo === "POST" && call.ruta === "/api/v1/external-obligations/5/retry")).toBe(true);
  });

  it("el Contribuyente previsualiza, confirma y recibe comprobante del pago", async () => {
    instalarBackendFalso({
      "POST /api/v1/electronic-payments/preview": { debtId: 3001, requestedAmount: 85000, payableAmount: 85000, approved: true, message: "Pago disponible" },
      "POST /api/v1/electronic-payments": { id: 4, paymentId: 9005, taxpayerId: 123, debtId: 3001, amount: 85000, status: "APPROVED", gatewayReference: "SIM-OK", createdAt: "2026-09-20T11:00:00-03:00" },
      "GET /api/v1/payments/{id}/receipt": { paymentId: 9005, receiptNumber: "REC-2026-9005", amount: 85000, paidAt: "2026-09-20T11:00:00-03:00" },
    });
    render(<App />);
    await ingresarComoContribuyente(user);
    await user.click(await screen.findByRole("link", { name: /^pago electrónico$/i }));
    await user.click((await screen.findAllByRole("button", { name: /elegir/i }))[0]);
    await user.click(screen.getByRole("button", { name: /revisar pago/i }));
    expect(await screen.findByText("Pago disponible")).toBeDefined();
    await user.click(screen.getByRole("button", { name: /confirmar y pagar/i }));
    expect(await screen.findByText(/pago aprobado/i)).toBeDefined();
    expect(screen.getByText("REC-2026-9005")).toBeDefined();
  });

  it("el Contribuyente ve sus beneficios y el saldo utilizado", async () => {
    instalarBackendFalso({
      "GET /api/v1/taxpayers/{id}/benefits": [{ id: 2, externalBenefitId: "M8-2", taxpayerId: 123, benefitType: "SOCIAL_RATE", status: "ACTIVE", discountPercentage: 50, validFrom: "2026-01-01", validUntil: "2026-12-31" }],
      "GET /api/v1/taxpayers/{id}/credit-balances": [{ id: 3, taxpayerId: 123, sourcePaymentId: 9005, originalAmount: 10000, availableAmount: 6000, status: "PARTIALLY_USED" }],
    });
    render(<App />);
    await ingresarComoContribuyente(user);
    await user.click(await screen.findByRole("link", { name: /^beneficios tributarios$/i }));
    expect(await screen.findByText("M8-2")).toBeDefined();
    await user.click(screen.getByRole("link", { name: /^mis pagos$/i }));
    expect(await screen.findByText("$ 4.000,00")).toBeDefined();
    expect(screen.getByText("$ 6.000,00")).toBeDefined();
  });

  it("el dashboard del Supervisor abre las bandejas desde sus indicadores", async () => {
    instalarBackendFalso();
    render(<App />);
    await ingresarComoAgente(user, "jlopez");
    const heading = await screen.findByRole("heading", { name: /pendientes de supervisión/i });
    const section = heading.closest("section");
    await user.click(within(section).getByText("Caducidades"));
    const breadcrumb = await screen.findByRole("navigation", { name: /breadcrumb/i });
    expect(within(breadcrumb).getByText(/caducidad de planes/i)).toBeDefined();
  });
});
