import { afterEach, expect, it, vi } from "vitest";
import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import RefinanciacionPortalPage from "../pages/portal/RefinanciacionPortalPage.jsx";
import { portalService } from "../services/rentasService.js";

const { authState } = vi.hoisted(() => ({ authState: { user: { role: "TAXPAYER", taxpayerId: 7 } } }));
vi.mock("../context/AuthContext.jsx", () => ({ useAuth: () => authState }));

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

it("simula y solicita refinanciación, muestra el estado resultante y permite volver", async () => {
  const user = userEvent.setup();
  vi.spyOn(portalService, "activePaymentPlans").mockResolvedValue([
    { id: 12, status: "ACTIVE", lifecycle: "CURRENT", installments: 6, totalAmount: 1200, paidAmount: 300, outstandingAmount: 900 },
    { id: 13, status: "CLOSED", lifecycle: "CLOSED", installments: 3, totalAmount: 300, paidAmount: 300, outstandingAmount: 0 },
  ]);
  const simulate = vi.spyOn(portalService, "simulateRefinancing")
    .mockRejectedValueOnce(new Error("No se pudo simular"))
    .mockResolvedValue({ outstandingPrincipal: 900, estimatedInterest: 90, estimatedTotalAmount: 990, installments: 6, total: 990, exceptional: false });
  const request = vi.spyOn(portalService, "requestRefinancing")
    .mockRejectedValueOnce(new Error("Servicio temporalmente no disponible"))
    .mockResolvedValue({ id: 77 });
  vi.spyOn(portalService, "refinancingRequest").mockResolvedValue({
    id: 77, status: "REQUESTED", planId: 12, installments: 6, outstandingAmount: 900,
    totalAmount: 990, requestedAt: "2026-10-27T12:00:00Z", newPaymentPlanId: null, resolvedBy: null,
  });

  render(<MemoryRouter initialEntries={["/portal/refinanciacion"]}><Routes>
    <Route path="/portal/refinanciacion" element={<RefinanciacionPortalPage />} />
    <Route path="/portal/refinanciacion/:requestId" element={<RefinanciacionPortalPage />} />
  </Routes></MemoryRouter>);

  const row = (await screen.findByText("#12")).closest("tr");
  expect(screen.queryByText("#13")).toBeNull();
  await user.click(within(row).getByRole("button", { name: "Solicitar" }));
  const dialog = await screen.findByRole("dialog", { name: "Refinanciar plan #12" });
  await user.click(within(dialog).getByRole("button", { name: "Simular" }));
  expect(await within(dialog).findByText("No se pudo simular")).toBeDefined();
  await user.click(within(dialog).getByRole("button", { name: "Simular" }));
  expect(await within(dialog).findByText("Resumen antes de confirmar")).toBeDefined();
  expect(simulate).toHaveBeenLastCalledWith({ planId: 12, installments: 6 });

  await user.click(within(dialog).getByRole("button", { name: "Confirmar solicitud" }));
  expect(await within(dialog).findByText("Servicio temporalmente no disponible")).toBeDefined();
  await user.click(within(dialog).getByRole("button", { name: "Confirmar solicitud" }));
  expect(request).toHaveBeenCalledWith({ planId: 12, installments: 6 });
  expect(await screen.findByText("Solicitud de refinanciación #77")).toBeDefined();
  expect(await screen.findByText("Pendiente de evaluación")).toBeDefined();
  expect(screen.getByText("El plan original continúa vigente hasta que se apruebe esta solicitud.")).toBeDefined();
  await user.click(screen.getByRole("button", { name: "Volver a mis planes" }));
  expect(await screen.findByRole("heading", { name: "Refinanciación de planes" })).toBeDefined();
});
