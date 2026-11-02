import { afterEach, expect, it, vi } from "vitest";
import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import RefinanciacionPage from "../pages/rentas/RefinanciacionPage.jsx";
import { refinancingService, taxpayerService } from "../services/rentasService.js";

const { authState } = vi.hoisted(() => ({ authState: { user: { role: "PERSONAL", username: "rentas.demo" } } }));
vi.mock("../context/AuthContext.jsx", () => ({ useAuth: () => authState }));
vi.mock("../hooks/usePlanConfiguration.js", () => ({
  default: () => ({ installmentChoices: [3, 6], loading: false, error: null }),
}));

const plan = { planId: 12, taxpayerId: 7, lifecycle: "ACTIVE", overdueInstallments: 2, outstandingAmount: 900, eligible: true, reasons: [] };
const request = { requestId: 41, planId: 12, taxpayerId: 7, totalAmount: 960, installments: 3, outstandingAmount: 900, overdueInstallments: 2, requestedBy: "rentas.demo", requestedAt: "2026-10-20", status: "PENDING", originalPlan: { installments: 6, outstandingAmount: 900 } };

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

it("compara alternativas, propone cuotas elegidas y mantiene el saldo anterior visible", async () => {
  const user = userEvent.setup();
  authState.user = { role: "PERSONAL", username: "rentas.demo" };
  vi.spyOn(refinancingService, "eligiblePlans").mockResolvedValue([plan, { ...plan, planId: 13, eligible: false, reasons: ["No admite refinanciación"] }]);
  vi.spyOn(refinancingService, "list").mockResolvedValue([]);
  vi.spyOn(taxpayerService, "search").mockResolvedValue([{ id: 7, name: "Ciudadano de prueba", document: "20-00000000-0" }]);
  vi.spyOn(refinancingService, "simulate").mockImplementation(async ({ installments }) => ({ installments, total: installments === 3 ? 960 : 1020, interest: installments === 3 ? 60 : 120 }));
  const propose = vi.spyOn(refinancingService, "request").mockResolvedValue({ requestId: 41, totalAmount: 1020, installments: 6 });

  render(<MemoryRouter><RefinanciacionPage /></MemoryRouter>);

  const eligible = (await screen.findByText("#12")).closest("tr");
  expect(within(eligible).getByRole("button", { name: "Refinanciar" })).toBeDefined();
  expect(screen.getByText("No admite refinanciación")).toBeDefined();
  await user.click(within(eligible).getByRole("button", { name: "Refinanciar" }));
  const dialog = await screen.findByRole("dialog", { name: "Refinanciar el plan #12" });
  expect(await within(dialog).findByText("Total del nuevo plan")).toBeDefined();
  expect(within(dialog).getByText(/Saldo vivo a refinanciar/).parentElement.textContent).toMatch(/900,00/);
  expect(refinancingService.simulate).toHaveBeenCalledTimes(2);
  await user.click(within(dialog).getByRole("button", { name: /^6 cuotas/ }));
  await user.click(within(dialog).getByRole("button", { name: "Proponer refinanciación" }));

  expect(propose).toHaveBeenCalledWith({ planId: 12, installments: 6, requestedBy: "rentas.demo" });
  expect(await screen.findByText(/El plan original sigue vigente hasta que se resuelva/)).toBeDefined();
});

it("permite derivar al supervisor sólo con contexto y comunica error o éxito", async () => {
  const user = userEvent.setup();
  authState.user = { role: "PERSONAL", username: "rentas.demo" };
  vi.spyOn(refinancingService, "eligiblePlans").mockResolvedValue([]);
  vi.spyOn(refinancingService, "list").mockResolvedValue([request]);
  vi.spyOn(taxpayerService, "search").mockResolvedValue([{ id: 7, name: "Ciudadano de prueba", document: "20-00000000-0" }]);
  const escalate = vi.spyOn(refinancingService, "escalate").mockRejectedValueOnce(new Error("Supervisor temporalmente no disponible"))
    .mockResolvedValueOnce({ requestId: 41, status: "UNDER_REVIEW" });

  render(<MemoryRouter><RefinanciacionPage /></MemoryRouter>);
  const row = (await screen.findByText("#41")).closest("tr");
  await user.click(within(row).getByRole("button", { name: "Evaluar" }));
  const dialog = await screen.findByRole("dialog", { name: "Evaluar la solicitud #41" });
  await user.selectOptions(within(dialog).getByRole("combobox"), "ESCALATE");
  await user.click(within(dialog).getByRole("button", { name: "Confirmar" }));
  expect(await within(dialog).findByText("Indicá por qué la derivás: el Supervisor necesita el contexto.")).toBeDefined();
  expect(escalate).not.toHaveBeenCalled();

  await user.type(within(dialog).getByRole("textbox"), "Revisar excepción de cuotas");
  await user.click(within(dialog).getByRole("button", { name: "Confirmar" }));
  expect(await within(dialog).findByText("Supervisor temporalmente no disponible")).toBeDefined();
  await user.click(within(dialog).getByRole("button", { name: "Confirmar" }));
  expect(escalate).toHaveBeenCalledWith({ requestId: 41, escalatedBy: "rentas.demo", note: "Revisar excepción de cuotas" });
  expect(await screen.findByText("Solicitud derivada")).toBeDefined();
});
