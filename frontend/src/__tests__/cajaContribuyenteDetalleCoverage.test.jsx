import { afterEach, expect, it, vi } from "vitest";
import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from "react-router-dom";
import ContribuyenteDetallePage from "../pages/caja/ContribuyenteDetallePage.jsx";
import { cashierService } from "../services/rentasService.js";

function LocationText() {
  const location = useLocation();
  const navigate = useNavigate();
  return <><output data-testid="location">{location.pathname}{location.search}</output><button onClick={() => navigate("/caja/contribuyentes/7")}>Volver a ficha</button></>;
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

it("muestra la ficha de caja y abre cobro para una deuda y una cuota con sus referencias", async () => {
  const user = userEvent.setup();
  vi.spyOn(cashierService, "taxpayerFile").mockResolvedValue({
    taxpayer: { id: 7, name: "Ciudadano de prueba", type: "PERSON", documentType: "DNI", document: "00000000", cuit: "20-00000000-0" },
    totals: { outstanding: 300, overdue: 100, paid: 500 },
    debts: [
      { id: 51, conceptCode: "ABL", dueDate: "2026-11-15", outstandingAmount: 300, status: "OPEN", inPaymentPlan: false },
      { id: 52, conceptCode: "TASA", dueDate: "2026-11-15", outstandingAmount: 0, status: "SETTLED", inPaymentPlan: false },
    ],
    installments: [{ id: 81, planId: 12, number: 1, dueDate: "2026-11-15", outstandingAmount: 100, status: "OPEN", type: "REGULAR" }],
    payments: [{ id: 9, receiptNumber: "REC-9", amountPaid: 500, method: "CASH", paidAt: "2026-10-20T12:00:00Z", status: "CONFIRMED", debtId: 51 }],
    bills: [],
  });
  render(<MemoryRouter initialEntries={["/caja/contribuyentes/7"]}><Routes>
    <Route path="/caja/contribuyentes/:taxpayerId" element={<ContribuyenteDetallePage />} />
    <Route path="*" element={<LocationText />} />
  </Routes></MemoryRouter>);

  expect(await screen.findByRole("heading", { name: "Ciudadano de prueba" })).toBeDefined();
  expect(screen.getByText("Deuda total")).toBeDefined();
  const debtRow = (await screen.findByText("#51")).closest("tr");
  await user.click(within(debtRow).getByRole("button", { name: "Cobrar" }));
  expect(screen.getByTestId("location").textContent).toBe("/caja/cobros?deuda=51");

  await user.click(screen.getByRole("button", { name: "Volver a ficha" }));
  const installmentRow = (await screen.findByText("#1")).closest("tr");
  await user.click(within(installmentRow).getByRole("button", { name: "Cobrar cuota" }));
  expect(screen.getByTestId("location").textContent).toBe("/caja/cobros?cuota=81&plan=12&contribuyente=7");
});
