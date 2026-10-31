import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import TicketsPage from "../pages/rentas/TicketsPage.jsx";
import EventosPage from "../pages/rentas/EventosPage.jsx";
import DeudasPage from "../pages/rentas/DeudasPage.jsx";
import ExencionesPage from "../pages/rentas/ExencionesPage.jsx";
import {
  administrationService,
  debtService,
  eventService,
  exemptionService,
  taxConfigService,
  taxpayerService,
  ticketService,
} from "../services/rentasService.js";
import { resetTaxConceptsCache } from "../hooks/useTaxConcepts.js";

const { authState } = vi.hoisted(() => ({ authState: { user: { role: "PERSONAL", username: "rentas.demo" } } }));
vi.mock("../context/AuthContext.jsx", () => ({ useAuth: () => authState }));

const ticket = {
  ticketId: 42,
  publicId: "TK-42",
  citizenId: 7,
  description: "Revisar una deuda duplicada",
  additionalInformation: "Adjunto el comprobante actualizado",
  attachments: ["https://docs.example.test/comprobante.pdf"],
  receivedAt: "2026-10-20T10:00:00Z",
  priority: "HIGH",
  createdAt: "2026-10-19T10:00:00Z",
  status: "OPEN",
};

const debt = {
  id: 51,
  taxpayerId: 7,
  taxConceptId: 3,
  originType: "LIQUIDATION",
  originalAmount: 1000,
  paidAmount: 0,
  outstandingAmount: 1000,
  dueDate: "2026-09-01",
  generatedAt: "2026-08-01T10:00:00Z",
  status: "OVERDUE",
};

const exemption = {
  requestId: 81,
  citizenId: 7,
  conceptCode: "ABL",
  requestedPercentage: 50,
  requestedFrom: "2026-01-01",
  requestedUntil: "2026-12-31",
  requestedAt: "2026-01-02T10:00:00Z",
  requestedBy: "contribuyente.demo",
  reason: "Beneficio social vigente",
  status: "REQUESTED",
  internalStatus: "PENDING_REVIEW",
  backendStatus: "PENDING",
  attachments: [],
  hasSocialBenefit: false,
};

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

beforeEach(() => {
  authState.user = { role: "PERSONAL", username: "rentas.demo" };
  vi.spyOn(taxpayerService, "search").mockResolvedValue([
    { id: 7, name: "Ciudadano de prueba", document: "20-00000000-0" },
  ]);
  vi.spyOn(taxConfigService, "concepts").mockResolvedValue([
    { id: 3, code: "ABL", name: "Alumbrado, barrido y limpieza" },
  ]);
  resetTaxConceptsCache();
});

describe("bandeja de tickets", () => {
  it("no convierte adjuntos con esquemas inseguros en enlaces ejecutables", async () => {
    const user = userEvent.setup();
    vi.spyOn(ticketService, "list").mockResolvedValue([{ ...ticket, attachments: ["javascript:alert(1)"] }]);
    render(<MemoryRouter><TicketsPage /></MemoryRouter>);

    const row = (await screen.findByText("TK-42")).closest("tr");
    await user.click(within(row).getByRole("button", { name: "Cambiar estado" }));
    const dialog = await screen.findByRole("dialog", { name: "Ticket TK-42" });
    expect(within(dialog).queryByRole("link")).toBeNull();
    expect(within(dialog).getByText("Adjunto con enlace no válido")).toBeDefined();
  });

  it("muestra contexto recibido y registra una actualización con mensaje obligatorio", async () => {
    const user = userEvent.setup();
    vi.spyOn(ticketService, "list").mockResolvedValue([ticket]);
    const update = vi.spyOn(ticketService, "updateStatus").mockResolvedValue({ ...ticket, status: "COMPLETED" });

    render(<MemoryRouter><TicketsPage /></MemoryRouter>);

    const row = (await screen.findByText("TK-42")).closest("tr");
    expect(within(row).getByText("Ciudadano de prueba")).toBeDefined();
    expect(within(row).getByText("1 adjunto(s)")).toBeDefined();
    await user.click(within(row).getByRole("button", { name: "Cambiar estado" }));
    const dialog = await screen.findByRole("dialog", { name: "Ticket TK-42" });
    expect(within(dialog).getByText("Adjunto el comprobante actualizado")).toBeDefined();
    expect(within(dialog).getByRole("link", { name: "comprobante.pdf" })).toBeDefined();

    await user.selectOptions(within(dialog).getByRole("combobox"), "COMPLETED");
    await user.click(within(dialog).getByRole("button", { name: "Informar a Atención Ciudadana" }));
    expect(await within(dialog).findByText("Escribí un mensaje para continuar con la gestión del ticket.")).toBeDefined();
    expect(update).not.toHaveBeenCalled();

    await user.type(within(dialog).getByRole("textbox"), "Se verificó el comprobante");
    await user.click(within(dialog).getByRole("button", { name: "Informar a Atención Ciudadana" }));
    expect(update).toHaveBeenCalledWith({ ticketId: 42, status: "COMPLETED", message: "Se verificó el comprobante" });
    expect(await screen.findByText(/actualización se registró localmente/)).toBeDefined();
  });
});

describe("bitácora de eventos", () => {
  it("resume estados, filtra y muestra tanto éxito como error del reproceso", async () => {
    const user = userEvent.setup();
    vi.spyOn(eventService, "list").mockResolvedValue([
      { eventId: "evt-1", eventType: "paymentRegistered", sourceModule: "RENTAS", destinationModule: "M4", occurredAt: "2026-10-20T10:00:00Z", processedAt: null, attempts: 4, status: "DLQ", direction: "OUT" },
      { eventId: "evt-2", eventType: "debtSettled", sourceModule: "RENTAS", destinationModule: "M8", occurredAt: "2026-10-21T10:00:00Z", processedAt: null, attempts: 2, status: "DLQ", direction: "OUT" },
      { eventId: "evt-3", eventType: "ticketUpdated", sourceModule: "M2", destinationModule: "RENTAS", occurredAt: "2026-10-22T10:00:00Z", processedAt: null, attempts: 1, status: "RETRYING", direction: "IN" },
    ]);
    const retry = vi.spyOn(eventService, "retry").mockImplementation(async (id) => {
      if (id === "evt-2") throw new Error("El evento sigue pendiente");
    });

    render(<MemoryRouter><EventosPage /></MemoryRouter>);

    expect(await screen.findByText("paymentRegistered")).toBeDefined();
    expect(screen.getByText("Eventos listados").closest("article").querySelector(".workspace-stat-value").textContent).toBe("3");
    expect(screen.getAllByText("Reintentando", { exact: true })[0].closest("article").querySelector(".workspace-stat-value").textContent).toBe("1");
    expect(screen.getAllByText("En DLQ", { exact: true })[0].closest("article").querySelector(".workspace-stat-value").textContent).toBe("2");
    expect(screen.getByText(/no confirma la DLQ externa/)).toBeDefined();

    await user.selectOptions(screen.getByLabelText("Dirección"), "IN");
    await waitFor(() => expect(eventService.list).toHaveBeenCalledWith({ direction: "IN", status: "" , eventType: "" }));

    const first = screen.getByText("paymentRegistered").closest("tr");
    await user.click(within(first).getByRole("button", { name: "Reprocesar" }));
    expect(await screen.findByText(/se procesó correctamente/)).toBeDefined();
    expect(retry).toHaveBeenCalledWith("evt-1");

    const second = screen.getByText("debtSettled").closest("tr");
    await user.click(within(second).getByRole("button", { name: "Reprocesar" }));
    expect(await screen.findByText("El evento sigue pendiente")).toBeDefined();
  });
});

describe("bandeja de deudas", () => {
  it("calcula recargos, aplica una vez y procesa vencimientos", async () => {
    const user = userEvent.setup();
    vi.spyOn(debtService, "list").mockResolvedValue([debt, { ...debt, id: 52, status: "PENDING", outstandingAmount: 200 }]);
    vi.spyOn(debtService, "previewLateCharge").mockResolvedValue({ ruleCode: "MORA", calculationDate: "2026-10-27", daysOverdue: 56, principal: 1000, surchargeRate: 3, surchargeAmount: 30, interestRate: 2, interestAmount: 20, totalAdjustment: 50, updatedTotal: 1050, applicationId: null });
    const apply = vi.spyOn(debtService, "applyLateCharge").mockResolvedValue({ applied: true, totalAdjustment: 50, debtId: 51 });
    const dueDates = vi.spyOn(administrationService, "processDueDates").mockResolvedValue({ debtsOverdue: 2, adjustmentsGenerated: 1, installmentsOverdue: 3 });

    render(<MemoryRouter><DeudasPage /></MemoryRouter>);

    expect(await screen.findByText(/1\.200,00/)).toBeDefined();
    expect(screen.getByText("Vencido").closest("article").querySelector(".workspace-stat-value").textContent).toMatch(/1\.000,00/);
    const row = (await screen.findByText("#51")).closest("tr");
    await user.click(within(row).getByRole("button", { name: "Calcular recargo" }));
    const dialog = await screen.findByRole("dialog", { name: "Recargos de deuda #51" });
    expect(within(dialog).getByText("Ajuste total").nextElementSibling.textContent).toMatch(/50,00/);
    await user.click(within(dialog).getByRole("button", { name: "Confirmar aplicación" }));
    expect(apply).toHaveBeenCalledWith(51, "2026-10-27");
    expect(await screen.findByText(/Se aplicaron/)).toBeDefined();

    await user.click(screen.getByRole("button", { name: "Procesar vencimientos" }));
    expect(dueDates).toHaveBeenCalledWith(expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/));
    expect(await screen.findByText(/2 deudas vencidas, 1 ajustes y 3 cuotas actualizadas/)).toBeDefined();
  });

  it("informa errores al previsualizar o aplicar recargos y procesa vencimientos", async () => {
    const user = userEvent.setup();
    vi.spyOn(debtService, "list").mockResolvedValue([debt]);
    vi.spyOn(debtService, "previewLateCharge").mockRejectedValueOnce(new Error("No se pudo calcular"))
      .mockResolvedValueOnce({ ruleCode: "MORA", calculationDate: "2026-10-27", daysOverdue: 56, principal: 1000, surchargeRate: 3, surchargeAmount: 30, interestRate: 2, interestAmount: 20, totalAdjustment: 50, updatedTotal: 1050, applicationId: 4 });
    vi.spyOn(debtService, "applyLateCharge").mockRejectedValue(new Error("El recargo ya cambió"));
    vi.spyOn(administrationService, "processDueDates").mockRejectedValue(new Error("Proceso no disponible"));

    render(<MemoryRouter><DeudasPage /></MemoryRouter>);
    const row = (await screen.findByText("#51")).closest("tr");
    await user.click(within(row).getByRole("button", { name: "Calcular recargo" }));
    await screen.findByText("No se pudo calcular", { selector: "p" });
    expect(screen.getAllByText("No se pudo calcular").length).toBeGreaterThanOrEqual(1);

    await user.click(within(row).getByRole("button", { name: "Calcular recargo" }));
    const dialog = await screen.findByRole("dialog", { name: "Recargos de deuda #51" });
    expect(within(dialog).getByText(/ya fue aplicado/)).toBeDefined();
    await user.click(within(dialog).getAllByRole("button", { name: "Cerrar" })[1]);

    // Simula una previsualización vigente sin applicationId para recorrer el error de aplicación.
    debtService.previewLateCharge.mockResolvedValueOnce({ ruleCode: "MORA", calculationDate: "2026-10-27", daysOverdue: 56, principal: 1000, surchargeRate: 3, surchargeAmount: 30, interestRate: 2, interestAmount: 20, totalAdjustment: 50, updatedTotal: 1050, applicationId: null });
    await user.click(within(row).getByRole("button", { name: "Calcular recargo" }));
    const applyDialog = await screen.findByRole("dialog", { name: "Recargos de deuda #51" });
    await user.click(within(applyDialog).getByRole("button", { name: "Confirmar aplicación" }));
    expect(await screen.findByText("El recargo ya cambió")).toBeDefined();

    await user.click(screen.getByRole("button", { name: "Procesar vencimientos" }));
    expect(await screen.findByText("Proceso no disponible")).toBeDefined();
  });
});

describe("trámite de exenciones", () => {
  it("inicia revisión y luego envía la solicitud a resolución con observaciones internas", async () => {
    const user = userEvent.setup();
    vi.spyOn(exemptionService, "list").mockResolvedValueOnce([{ ...exemption, backendStatus: "PENDING" }]).mockResolvedValue([{ ...exemption, backendStatus: "PENDING_REVIEW" }]);
    const advance = vi.spyOn(exemptionService, "advanceWorkflow").mockResolvedValue({ ...exemption });

    render(<MemoryRouter><ExencionesPage /></MemoryRouter>);
    const row = (await screen.findByText("#81")).closest("tr");
    await user.click(within(row).getByRole("button", { name: "Trámite" }));
    let dialog = await screen.findByRole("dialog", { name: "Trámite de la solicitud #81" });
    expect(within(dialog).getByRole("combobox")).toHaveProperty("value", "START");
    await user.click(within(dialog).getByRole("button", { name: "Confirmar" }));
    expect(advance).toHaveBeenLastCalledWith({ requestId: 81, internalStatus: "PENDING_REVIEW", note: "", actor: "rentas.demo" });
    expect(await screen.findByText("La solicitud #81 está en revisión.")).toBeDefined();

    await user.click(within((await screen.findByText("#81")).closest("tr")).getByRole("button", { name: "Trámite" }));
    dialog = await screen.findByRole("dialog", { name: "Trámite de la solicitud #81" });
    await user.selectOptions(within(dialog).getByRole("combobox"), "RESOLUTION");
    await user.type(within(dialog).getByRole("textbox"), "Revisar vigencia solicitada");
    await user.click(within(dialog).getByRole("button", { name: "Confirmar" }));
    expect(advance).toHaveBeenLastCalledWith({ requestId: 81, internalStatus: "PENDING_RESOLUTION", note: "Revisar vigencia solicitada", actor: "rentas.demo" });
    expect(await screen.findByText("La solicitud #81 quedó lista para resolver.")).toBeDefined();
  });

  it("rechaza una solicitud sólo con motivo y comunica fallos del servicio", async () => {
    const user = userEvent.setup();
    authState.user = { role: "SUPERVISOR", username: "supervisor.demo" };
    vi.spyOn(exemptionService, "list").mockResolvedValue([{ ...exemption, internalStatus: "PENDING_RESOLUTION", backendStatus: "PENDING_RESOLUTION" }]);
    vi.spyOn(exemptionService, "detail").mockResolvedValue({ taxpayerName: "Ciudadano de prueba", conceptCode: "ABL", conceptName: "ABL", requestedAt: exemption.requestedAt, requestedPercentage: 50, requestedFrom: exemption.requestedFrom, requestedUntil: exemption.requestedUntil, reason: exemption.reason, requestedBy: "contribuyente.demo", documents: [], reviewedBy: null, reviewStartedAt: null, resolutionSubmittedBy: null, resolutionSubmittedAt: null, resolutionReason: "Revisión interna", history: [], hasSocialBenefit: false });
    const resolve = vi.spyOn(exemptionService, "resolve").mockRejectedValueOnce(new Error("No se pudo notificar a M8")).mockResolvedValueOnce({ requestId: 81, status: "REJECTED" });

    render(<MemoryRouter><ExencionesPage /></MemoryRouter>);
    const row = (await screen.findByText("#81")).closest("tr");
    await user.click(within(row).getByRole("button", { name: "Resolver" }));
    const dialog = await screen.findByRole("dialog", { name: "Solicitud #81" });
    await user.selectOptions(within(dialog).getByRole("combobox"), "REJECTED");
    await user.click(within(dialog).getByRole("button", { name: "Rechazar solicitud" }));
    expect(await within(dialog).findByText("El motivo del rechazo viaja en el evento: es obligatorio.")).toBeDefined();
    expect(resolve).not.toHaveBeenCalled();
    await user.type(within(dialog).getByRole("textbox"), "La documentación no acredita el requisito");
    await user.click(within(dialog).getByRole("button", { name: "Rechazar solicitud" }));
    expect(await within(dialog).findByText("No se pudo notificar a M8")).toBeDefined();
    await user.click(within(dialog).getByRole("button", { name: "Rechazar solicitud" }));
    expect(resolve).toHaveBeenCalledWith({ requestId: 81, status: "REJECTED", percentage: 50, validFrom: "2026-01-01", validUntil: "2026-12-31", reason: "La documentación no acredita el requisito", resolvedBy: "supervisor.demo" });
    expect(await screen.findByText("Se comunicó el rechazo de la solicitud #81.")).toBeDefined();
  });

  it("deriva la solicitud a revisión y pide datos faltantes antes de llamar al servicio", async () => {
    const user = userEvent.setup();
    vi.spyOn(exemptionService, "list").mockResolvedValue([{ ...exemption, backendStatus: "PENDING_REVIEW" }]);
    const advance = vi.spyOn(exemptionService, "advanceWorkflow").mockResolvedValue({ ...exemption, backendStatus: "DOCUMENTATION_REQUIRED" });

    render(<MemoryRouter><ExencionesPage /></MemoryRouter>);
    const row = (await screen.findByText("#81")).closest("tr");
    await user.click(within(row).getByRole("button", { name: "Trámite" }));
    const dialog = await screen.findByRole("dialog", { name: "Trámite de la solicitud #81" });
    await user.selectOptions(within(dialog).getByRole("combobox"), "REQUIRE");
    await user.click(within(dialog).getByRole("button", { name: "Confirmar" }));
    expect(await within(dialog).findByText("Indicá qué documentación falta.")).toBeDefined();
    expect(advance).not.toHaveBeenCalled();

    await user.type(within(dialog).getByRole("textbox"), "Constancia de ingresos");
    await user.click(within(dialog).getByRole("button", { name: "Confirmar" }));
    expect(advance).toHaveBeenCalledWith({ requestId: 81, internalStatus: "DOCUMENTATION_REQUIRED", note: "Constancia de ingresos", actor: "rentas.demo" });
    expect(await screen.findByText(/Se le pidió documentación al ciudadano/)).toBeDefined();
  });

  it("muestra la evaluación y registra la decisión del supervisor", async () => {
    const user = userEvent.setup();
    authState.user = { role: "SUPERVISOR", username: "supervisor.demo" };
    vi.spyOn(exemptionService, "list").mockResolvedValue([{ ...exemption, internalStatus: "PENDING_RESOLUTION", backendStatus: "PENDING_RESOLUTION", attachments: [{ id: 1, fileName: "constancia.pdf", documentType: "INCOME" }] }]);
    vi.spyOn(exemptionService, "detail").mockResolvedValue({
      taxpayerName: "Ciudadano de prueba", conceptCode: "ABL", conceptName: "Alumbrado, barrido y limpieza", requestedAt: exemption.requestedAt,
      requestedPercentage: 50, requestedFrom: exemption.requestedFrom, requestedUntil: exemption.requestedUntil, reason: exemption.reason,
      requestedBy: "contribuyente.demo", documents: [{ id: 1, fileName: "constancia.pdf", documentType: "INCOME", uploadedBy: "contribuyente.demo", uploadedAt: "2026-01-03T10:00:00Z" }],
      reviewedBy: "rentas.demo", reviewStartedAt: "2026-01-04T10:00:00Z", resolutionSubmittedBy: "rentas.demo", resolutionSubmittedAt: "2026-01-05T10:00:00Z",
      resolutionReason: "Documentación revisada", history: [{ type: "REQUESTED", at: exemption.requestedAt, action: "Solicitud presentada" }], hasSocialBenefit: true,
    });
    const resolve = vi.spyOn(exemptionService, "resolve").mockResolvedValue({ requestId: 81, exemptionId: 91, status: "APPROVED", percentage: 50, validUntil: "2026-12-31" });

    render(<MemoryRouter><ExencionesPage /></MemoryRouter>);
    const row = (await screen.findByText("#81")).closest("tr");
    await user.click(within(row).getByRole("button", { name: "Resolver" }));
    const dialog = await screen.findByRole("dialog", { name: "Solicitud #81" });
    expect(await within(dialog).findByText("Documentación presentada")).toBeDefined();
    expect(within(dialog).getByText("Documentación revisada")).toBeDefined();
    expect(within(dialog).getByText("El ciudadano tiene un beneficio social activo")).toBeDefined();
    await user.click(within(dialog).getByRole("button", { name: "Aprobar exención" }));
    expect(resolve).toHaveBeenCalledWith({ requestId: 81, status: "APPROVED", percentage: 50, validFrom: "2026-01-01", validUntil: "2026-12-31", reason: "", resolvedBy: "supervisor.demo" });
    expect(await screen.findByText(/Exención #91 del 50% vigente hasta/)).toBeDefined();
  });
});
