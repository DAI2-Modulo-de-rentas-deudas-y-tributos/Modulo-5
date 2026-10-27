import { afterEach, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import AuditorDashboardPage from "../pages/auditoria/AuditorDashboardPage.jsx";
import IndicadoresPage from "../pages/auditoria/IndicadoresPage.jsx";
import IndicadorDetallePage from "../pages/auditoria/IndicadorDetallePage.jsx";
import { auditService } from "../services/rentasService.js";

const { authState } = vi.hoisted(() => ({ authState: { user: { role: "AUDITOR", roleLabel: "Auditoría", fullName: "Ana Auditoría" } } }));
vi.mock("../context/AuthContext.jsx", () => ({ useAuth: () => authState }));
vi.mock("../hooks/useTaxConcepts.js", () => ({ default: () => ({ options: [{ value: "ABL", label: "Alumbrado" }] }) }));

function LocationText() {
  const location = useLocation();
  return <output data-testid="location">{location.pathname}{location.search}</output>;
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

it("presenta actividad reciente del auditor y permite abrir su registro", async () => {
  const user = userEvent.setup();
  vi.spyOn(auditService, "dashboard").mockResolvedValue([
    { id: 8, occurredAt: "2026-10-20T12:00:00Z", userId: "rentas.demo", userRole: "PERSONAL", action: "PAYMENT_REGISTERED", entityType: "Payment", entityId: 17 },
  ]);
  render(<MemoryRouter initialEntries={["/auditor"]}><Routes>
    <Route path="/auditor" element={<AuditorDashboardPage />} />
    <Route path="*" element={<LocationText />} />
  </Routes></MemoryRouter>);
  expect(await screen.findByText("Hola, Ana.")).toBeDefined();
  const row = (await screen.findByText("rentas.demo")).closest("tr");
  expect(within(row).getByText(/#17/)).toBeDefined();
  await user.click(screen.getByRole("button", { name: "Ver registro completo" }));
  expect(screen.getByTestId("location").textContent).toBe("/auditor/auditoria");
});

it("filtra indicadores y alterna los gráficos por las tablas de composición", async () => {
  const user = userEvent.setup();
  vi.spyOn(auditService, "indicators").mockResolvedValue({
    totalSettled: 1000, totalCollected: 800, pendingDebt: 1200, overdueDebt: 300,
    delinquencyRate: 25, defaultedPlans: 2,
    counts: { totalSettled: 1, totalCollected: 2, pendingDebt: 3 },
    byPeriod: [{ period: "2026-09", count: 2, amount: 800 }],
    byConcept: [{ conceptCode: "ABL", conceptName: "Alumbrado", count: 3, amount: 1200 }],
  });
  render(<MemoryRouter><IndicadoresPage /></MemoryRouter>);

  expect(await screen.findByText("sep 26")).toBeDefined();
  const conceptBar = screen.getAllByText("Alumbrado")[1].closest(".relative");
  await user.hover(conceptBar);
  expect(await screen.findByText("3 deudas")).toBeDefined();
  await user.selectOptions(screen.getByLabelText(/Concepto/), "ABL");
  expect(auditService.indicators).toHaveBeenLastCalledWith(expect.objectContaining({ conceptCode: "ABL" }));
  await user.click(screen.getByRole("button", { name: "Ver como tabla" }));
  expect(await screen.findByRole("columnheader", { name: "Período" })).toBeDefined();
  expect(screen.getByRole("row", { name: /Alumbrado/ })).toBeDefined();
  await user.click(screen.getByRole("button", { name: "Ver gráficos" }));
  expect(screen.getAllByText("Alumbrado").length).toBeGreaterThan(0);
});

it("conserva los filtros del indicador recaudado y abre el pago que lo compone", async () => {
  const user = userEvent.setup();
  const breakdown = vi.spyOn(auditService, "indicatorBreakdown").mockResolvedValue({
    total: 800, count: 1, rows: [{ id: 33, receiptNumber: "REC-33", taxpayerName: "Ciudadano", amountPaid: 800, paidAt: "2026-09-15T12:00:00Z" }],
  });
  render(<MemoryRouter initialEntries={["/auditor/indicadores/totalCollected?from=2026-09-01&conceptCode=ABL"]}><Routes>
    <Route path="/auditor/indicadores/:key" element={<IndicadorDetallePage />} />
    <Route path="*" element={<LocationText />} />
  </Routes></MemoryRouter>);

  await screen.findByText("REC-33");
  expect(breakdown).toHaveBeenCalledWith("totalCollected", expect.objectContaining({ from: "2026-09-01", conceptCode: "ABL", taxpayerId: "" }));
  await user.type(screen.getByPlaceholderText("Contribuyente, DNI o CUIT…"), "Ciudadano");
  await waitFor(() => expect(breakdown).toHaveBeenCalledWith("totalCollected", expect.objectContaining({ taxpayerId: expect.stringMatching(/^C/) })));
  await user.click((await screen.findByText("REC-33")).closest("tr"));
  expect(screen.getByTestId("location").textContent).toBe("/auditor/pagos/33");
});
