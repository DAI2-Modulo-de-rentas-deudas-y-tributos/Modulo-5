import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import DeudaDetallePage from "../pages/auditoria/DeudaDetallePage.jsx";
import ExencionDetallePage from "../pages/auditoria/ExencionDetallePage.jsx";
import IntegracionDetallePage from "../pages/auditoria/IntegracionDetallePage.jsx";
import LiquidacionDetallePage from "../pages/auditoria/LiquidacionDetallePage.jsx";
import PlanDetallePage from "../pages/auditoria/PlanDetallePage.jsx";
import PagoDetallePage from "../pages/auditoria/PagoDetallePage.jsx";
import ReversionDetallePage from "../pages/auditoria/ReversionDetallePage.jsx";
import TicketDetallePage from "../pages/auditoria/TicketDetallePage.jsx";
import { auditService } from "../services/rentasService.js";

function CurrentPath() {
  return <output data-testid="current-path">{useLocation().pathname}</output>;
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("detalles de Auditoría", () => {
  it("muestra pagos, plan e historial de una deuda con datos del servicio", async () => {
    const user = userEvent.setup();
    vi.spyOn(auditService, "debtDetail").mockResolvedValue({
      id: 71,
      taxpayerName: "María Ejemplo",
      conceptName: "Tasa de servicios",
      status: "OPEN",
      reportedToM8: false,
      generatedAt: "2026-10-01T10:00:00-03:00",
      dueDate: "2026-11-01",
      originType: "LIQUIDATION",
      originId: "LQ-42",
      settlement: { id: 42 },
      originalAmount: 1000,
      paidAmount: 250,
      outstandingAmount: 750,
      payments: [
        {
          id: 90,
          receiptNumber: "REC-90",
          amountPaid: 250,
          method: "CASH",
          paidAt: "2026-10-10T12:00:00-03:00",
          status: "ACTIVE",
        },
      ],
      plan: { requestId: 55, planId: 66, installments: 3, status: "GRANTED", lifecycle: "ACTIVE" },
      history: [
        {
          at: "2026-10-10T12:00:00-03:00",
          status: "OPEN",
          action: "Pago parcial imputado",
          actor: "cajero.demo",
          note: "Saldo restante: 750",
        },
      ],
    });

    render(
      <MemoryRouter initialEntries={["/auditor/deudas/71"]}>
        <Routes>
          <Route path="/auditor/deudas/:debtId" element={<DeudaDetallePage />} />
          <Route path="/auditor/planes/:requestId" element={<p>Detalle del plan</p>} />
        </Routes>
      </MemoryRouter>,
    );

    expect(await screen.findByRole("heading", { name: "Deuda #71" })).toBeTruthy();
    expect(auditService.debtDetail).toHaveBeenCalledWith("71");
    expect(screen.getByText("María Ejemplo · Tasa de servicios")).toBeTruthy();
    expect(screen.getByText("REC-90")).toBeTruthy();
    expect(screen.getByText((text, node) =>
      node?.tagName === "SPAN" && text.replace(/\s/g, "").includes("250,00"),
    )).toBeTruthy();
    const planCard = screen.getByRole("heading", { name: "Plan de pago asociado" }).closest("section");
    expect(planCard.textContent).toContain("Solicitud #55");
    expect(planCard.textContent).toContain("plan #66");
    expect(planCard.textContent).toContain("3 cuotas");
    expect(screen.getByText("Pago parcial imputado")).toBeTruthy();
    expect(screen.getByText("Saldo restante: 750")).toBeTruthy();

    await user.click(screen.getByRole("button", { name: "Ver plan" }));
    expect(await screen.findByText("Detalle del plan")).toBeTruthy();
  });

  it("muestra evento en reintento, payload JSON y navega al resultado relacionado", async () => {
    const user = userEvent.setup();
    vi.spyOn(auditService, "integrationDetail").mockResolvedValue({
      eventId: "evt-123",
      eventType: "paymentRegistered",
      sourceModule: "M5",
      destinationModule: "M7",
      occurredAt: "2026-10-10T12:00:00-03:00",
      processedAt: "2026-10-10T12:00:01-03:00",
      status: "RETRYING",
      attempts: 2,
      error: "El broker no confirmó la publicación.",
      payload: JSON.stringify({ paymentId: 90, amount: 250, sourceModule: "M5" }),
      result: { debtId: 71 },
      processing: [
        {
          at: "2026-10-10T12:00:01-03:00",
          status: "RETRYING",
          action: "Se programó un reintento",
          note: "El evento permanece en Outbox.",
        },
      ],
    });

    render(
      <MemoryRouter initialEntries={["/auditor/integraciones/evt-123"]}>
        <Routes>
          <Route
            path="/auditor/integraciones/:eventId"
            element={<><IntegracionDetallePage /><CurrentPath /></>}
          />
          <Route path="/auditor/deudas/:debtId" element={<CurrentPath />} />
        </Routes>
      </MemoryRouter>,
    );

    expect(await screen.findByRole("heading", { name: "Pago registrado" })).toBeTruthy();
    expect(auditService.integrationDetail).toHaveBeenCalledWith("evt-123");
    expect(screen.getByText(/el evento quedó en reintento/i)).toBeTruthy();
    expect(screen.getByText("El broker no confirmó la publicación.")).toBeTruthy();
    expect(screen.getByText("2")).toBeTruthy();
    expect(screen.getByText("Se programó un reintento")).toBeTruthy();

    await user.click(screen.getByText("Detalles técnicos"));
    expect(screen.getByText((_content, node) =>
      node?.tagName === "PRE" && node.textContent.includes('"paymentId": 90') && node.textContent.includes('"amount": 250'),
    )).toBeTruthy();

    await user.click(screen.getByRole("button", { name: "Deuda #71" }));
    expect((await screen.findByTestId("current-path")).textContent).toBe("/auditor/deudas/71");
  });

  it("muestra una exención aprobada, documentación e historial", async () => {
    vi.spyOn(auditService, "exemptionDetail").mockResolvedValue({
      requestId: 55,
      taxpayerName: "Ana Ejemplo",
      conceptName: "Tasa de servicios",
      status: "APPROVED",
      requestedAt: "2026-08-10",
      requestedBy: "ana.demo",
      reason: "Beneficio social",
      requestedPercentage: 90,
      percentage: 70,
      requestedFrom: "2026-09-01",
      requestedUntil: "2027-08-31",
      validFrom: "2026-10-01",
      validUntil: "2027-03-31",
      reviewedBy: "rentas.demo",
      resolutionReason: "Documentación evaluada",
      resolutionMessage: "Se aprobó parcialmente",
      resolvedBy: "supervisor.demo",
      result: { resolvedAt: "2026-09-01T10:00:00-03:00" },
      documents: [{ id: 7, fileName: "constancia.pdf", documentType: "SOCIAL_REPORT", uploadedBy: "ana.demo", uploadedAt: "2026-08-11T10:00:00-03:00" }],
      history: [{ at: "2026-09-01T10:00:00-03:00", status: "APPROVED", action: "Solicitud aprobada", actor: "supervisor.demo" }],
    });

    render(
      <MemoryRouter initialEntries={["/auditor/exenciones/55"]}>
        <Routes><Route path="/auditor/exenciones/:requestId" element={<ExencionDetallePage />} /></Routes>
      </MemoryRouter>,
    );

    expect(await screen.findByRole("heading", { name: "Exención #55" })).toBeTruthy();
    expect(auditService.exemptionDetail).toHaveBeenCalledWith("55");
    expect(screen.getByText("Se aprobó por menos de lo solicitado")).toBeTruthy();
    expect(screen.getByText("Solicitado 90%, aprobado 70%. El motivo está en las observaciones.")).toBeTruthy();
    expect(screen.getByText("Beneficio social")).toBeTruthy();
    expect(screen.getByText("constancia.pdf")).toBeTruthy();
    expect(screen.getByText("Se aprobó parcialmente")).toBeTruthy();
    expect(screen.getByText("Solicitud aprobada")).toBeTruthy();
  });

  it("muestra el cálculo y origen de liquidación y abre la deuda relacionada", async () => {
    const user = userEvent.setup();
    vi.spyOn(auditService, "settlementDetail").mockResolvedValue({
      id: 42,
      taxpayerName: "Ana Ejemplo",
      conceptName: "Patente automotor",
      period: "2026-10",
      status: "ISSUED",
      createdAt: "2026-10-01",
      dueDate: "2026-11-01",
      baseAmount: 1000,
      discountPercentage: 10,
      surcharges: 25,
      interests: 15,
      amount: 940,
      origin: { module: "M7", event: "infractionConfirmed", externalRef: "INF-900" },
      debt: { id: 71, outstandingAmount: 750, status: "OPEN" },
      history: [{ at: "2026-10-01T10:00:00-03:00", status: "ISSUED", action: "Liquidación emitida" }],
    });

    render(
      <MemoryRouter initialEntries={["/auditor/liquidaciones/42"]}>
        <Routes>
          <Route path="/auditor/liquidaciones/:settlementId" element={<><LiquidacionDetallePage /><CurrentPath /></>} />
          <Route path="/auditor/deudas/:debtId" element={<CurrentPath />} />
        </Routes>
      </MemoryRouter>,
    );

    expect(await screen.findByRole("heading", { name: "Liquidación #42" })).toBeTruthy();
    expect(auditService.settlementDetail).toHaveBeenCalledWith("42");
    expect(screen.getByText("Ana Ejemplo · Patente automotor")).toBeTruthy();
    expect(screen.getByText("2026-10")).toBeTruthy();
    expect(screen.getByText("Descuentos (10%)")).toBeTruthy();
    expect(screen.getByText("infractionConfirmed")).toBeTruthy();
    expect(screen.getByText("INF-900")).toBeTruthy();
    expect(screen.getByText("Liquidación emitida")).toBeTruthy();

    await user.click(screen.getByRole("button", { name: "Ver deuda" }));
    expect((await screen.findByTestId("current-path")).textContent).toBe("/auditor/deudas/71");
  });

  it("muestra financiación, cuotas y deudas del plan y navega a la deuda", async () => {
    const user = userEvent.setup();
    vi.spyOn(auditService, "planDetail").mockResolvedValue({
      requestId: 55,
      planId: 66,
      taxpayerName: "Ana Ejemplo",
      requestedAt: "2026-08-10",
      resolvedAt: "2026-08-12",
      resolvedBy: "rentas.demo",
      status: "GRANTED",
      lifecycle: "ACTIVE",
      installments: 3,
      totalDebt: 900,
      downPayment: 100,
      financedAmount: 800,
      interestAmount: 40,
      totalAmount: 840,
      outstandingAmount: 560,
      schedule: [{ number: 1, dueDate: "2026-09-10", amount: 280, status: "PAID" }, { number: 2, dueDate: "2026-10-10", amount: 280, status: "PENDING" }],
      debts: [{ id: 71, conceptName: "ABL", originalAmount: 900, status: "OPEN" }],
      history: [{ at: "2026-08-12T10:00:00-03:00", status: "GRANTED", action: "Plan otorgado" }],
    });

    render(
      <MemoryRouter initialEntries={["/auditor/planes/55"]}>
        <Routes>
          <Route path="/auditor/planes/:requestId" element={<PlanDetallePage />} />
          <Route path="/auditor/deudas/:debtId" element={<CurrentPath />} />
        </Routes>
      </MemoryRouter>,
    );

    expect(await screen.findByRole("heading", { name: "Plan #66" })).toBeTruthy();
    expect(auditService.planDetail).toHaveBeenCalledWith("55");
    expect(screen.getByText("Financiación")).toBeTruthy();
    expect(screen.getAllByText((text, node) =>
      node?.tagName === "TD" && text.replace(/\s/g, "").includes("280,00"),
    )).toHaveLength(2);
    expect(screen.getByText("Plan otorgado")).toBeTruthy();
    expect(screen.getByText("ABL")).toBeTruthy();

    await user.click(screen.getByText("#71"));
    expect((await screen.findByTestId("current-path")).textContent).toBe("/auditor/deudas/71");
  });

  it("muestra monto, estados y motivo de reversión y abre el pago", async () => {
    const user = userEvent.setup();
    vi.spyOn(auditService, "reversalDetail").mockResolvedValue({
      id: 88,
      paymentId: 90,
      taxpayerName: "Ana Ejemplo",
      reversedAmount: 250,
      reversedAt: "2026-10-10",
      requestedBy: "rentas.demo",
      approvedBy: "supervisor.demo",
      reason: "Pago duplicado",
      paymentStatusChange: { from: "CONFIRMED", to: "REVERSED" },
      debtId: 71,
      debtStatusChange: { from: "PARTIALLY_PAID", to: "OPEN" },
      eventPublished: "paymentReversed",
    });

    render(
      <MemoryRouter initialEntries={["/auditor/reversiones/88"]}>
        <Routes>
          <Route path="/auditor/reversiones/:reversalId" element={<ReversionDetallePage />} />
          <Route path="/auditor/pagos/:paymentId" element={<CurrentPath />} />
          <Route path="/auditor/deudas/:debtId" element={<CurrentPath />} />
        </Routes>
      </MemoryRouter>,
    );

    expect(await screen.findByRole("heading", { name: "Reversión #88" })).toBeTruthy();
    expect(auditService.reversalDetail).toHaveBeenCalledWith("88");
    expect(screen.getByText("Ana Ejemplo · pago #90")).toBeTruthy();
    expect(screen.getByText("Pago duplicado")).toBeTruthy();
    expect(screen.getByText("paymentReversed")).toBeTruthy();
    expect(screen.getByText("Estado del pago").parentElement.textContent).toContain("Reversado");
    expect(screen.getByText("Estado de la deuda #71").parentElement.textContent).toContain("Abierto");
    expect(screen.getByRole("button", { name: "Ver deuda" })).toBeTruthy();

    await user.click(screen.getByRole("button", { name: "Ver pago" }));
    expect((await screen.findByTestId("current-path")).textContent).toBe("/auditor/pagos/90");
  });

  it("muestra un pago reversado, su imputación y abre deuda y reversión", async () => {
    const user = userEvent.setup();
    vi.spyOn(auditService, "paymentDetail").mockResolvedValue({
      id: 90,
      taxpayerName: "Ana Ejemplo",
      receiptNumber: "REC-90",
      paidAt: "2026-10-10T12:00:00-03:00",
      amountPaid: 250,
      method: "CASH",
      channel: "CASHIER",
      registeredBy: "cajero.demo",
      status: "REVERSED",
      wasOverdue: true,
      allocations: [{ debtId: 71, conceptName: "ABL", amount: 250 }],
      creditBalance: null,
      reversal: { id: 88, reason: "Pago duplicado" },
      history: [{ at: "2026-10-10T12:01:00-03:00", action: "Reversión ejecutada", actor: "rentas.demo" }],
    });
    const renderPayment = () => render(
      <MemoryRouter initialEntries={["/auditor/pagos/90"]}>
        <Routes>
          <Route path="/auditor/pagos/:paymentId" element={<PagoDetallePage />} />
          <Route path="/auditor/deudas/:debtId" element={<CurrentPath />} />
          <Route path="/auditor/reversiones/:reversalId" element={<CurrentPath />} />
        </Routes>
      </MemoryRouter>,
    );

    renderPayment();
    expect(await screen.findByRole("heading", { name: "Pago #90" })).toBeTruthy();
    expect(auditService.paymentDetail).toHaveBeenCalledWith("90");
    expect(screen.getByText("Pago reversado")).toBeTruthy();
    expect(screen.getByText("REC-90")).toBeTruthy();
    expect(screen.getByText("Vencida")).toBeTruthy();
    expect(screen.getByText("ABL")).toBeTruthy();
    expect(screen.getByText("Reversión #88 — Pago duplicado")).toBeTruthy();
    expect(screen.getByText("Reversión ejecutada")).toBeTruthy();

    await user.click(screen.getByText("#71"));
    expect((await screen.findByTestId("current-path")).textContent).toBe("/auditor/deudas/71");

    cleanup();
    renderPayment();
    await screen.findByRole("heading", { name: "Pago #90" });
    await user.click(screen.getByRole("button", { name: "Ver reversión" }));
    expect((await screen.findByTestId("current-path")).textContent).toBe("/auditor/reversiones/88");
  });

  it("muestra un ticket escalado y permite abrir el pago y la deuda vinculados", async () => {
    const user = userEvent.setup();
    vi.spyOn(auditService, "ticketDetail").mockResolvedValue({
      ticketId: "TCK-27",
      taxpayerName: "Ana Ejemplo",
      subject: "Consulta por pago",
      priority: "HIGH",
      createdAt: "2026-10-09",
      externalStatus: "IN_PROGRESS",
      status: "WAITING_FOR_INFORMATION",
      assignedTo: "rentas.demo",
      escalated: true,
      description: "El comprobante no refleja la reversión.",
      additionalInformation: "Pago 90, deuda 71.",
      reference: { type: "PAYMENT" },
      payment: { id: 90 },
      debt: { id: 71 },
      history: [{ at: "2026-10-09T10:00:00-03:00", action: "Ticket escalado", actor: "rentas.demo" }],
    });
    const renderTicket = () => render(
      <MemoryRouter initialEntries={["/auditor/tickets/TCK-27"]}>
        <Routes>
          <Route path="/auditor/tickets/:ticketId" element={<TicketDetallePage />} />
          <Route path="/auditor/pagos/:paymentId" element={<CurrentPath />} />
          <Route path="/auditor/deudas/:debtId" element={<CurrentPath />} />
        </Routes>
      </MemoryRouter>,
    );

    renderTicket();
    expect(await screen.findByRole("heading", { name: "Ticket #TCK-27" })).toBeTruthy();
    expect(auditService.ticketDetail).toHaveBeenCalledWith("TCK-27");
    expect(screen.getByText("Reclamo escalado")).toBeTruthy();
    expect(screen.getByText((_text, node) => node?.tagName === "P" && node.textContent.includes("El comprobante no refleja la reversión."))).toBeTruthy();
    expect(screen.getByText("Información adicional: Pago 90, deuda 71.")).toBeTruthy();
    expect(screen.getByText("Ticket escalado")).toBeTruthy();
    expect(screen.getByText("Sí")).toBeTruthy();

    await user.click(screen.getByRole("button", { name: "Ver pago" }));
    expect((await screen.findByTestId("current-path")).textContent).toBe("/auditor/pagos/90");

    cleanup();
    renderTicket();
    await screen.findByRole("heading", { name: "Ticket #TCK-27" });
    await user.click(screen.getByRole("button", { name: "Ver deuda" }));
    expect((await screen.findByTestId("current-path")).textContent).toBe("/auditor/deudas/71");
  });

  it.each([
    ["exención", ExencionDetallePage, "exemptionDetail", "requestId", "55", "/auditor/exenciones/55", "No pudimos abrir la exención"],
    ["liquidación", LiquidacionDetallePage, "settlementDetail", "settlementId", "42", "/auditor/liquidaciones/42", "No pudimos abrir la liquidación"],
    ["plan", PlanDetallePage, "planDetail", "requestId", "66", "/auditor/planes/66", "No pudimos abrir el plan"],
    ["reversión", ReversionDetallePage, "reversalDetail", "reversalId", "88", "/auditor/reversiones/88", "No pudimos abrir la reversión"],
  ])("informa el error del servicio al cargar el detalle de %s", async (_name, Page, method, param, id, path, alertTitle) => {
    vi.spyOn(auditService, method).mockRejectedValue(new Error("Servicio temporalmente no disponible"));

    render(
      <MemoryRouter initialEntries={[path]}>
        <Routes><Route path={path.replace(id, `:${param}`)} element={<Page />} /></Routes>
      </MemoryRouter>,
    );

    expect(await screen.findByText("Servicio temporalmente no disponible")).toBeTruthy();
    expect(screen.getByText(alertTitle)).toBeTruthy();
    expect(auditService[method]).toHaveBeenCalledWith(id);
  });
});
