import { afterEach, expect, it, vi } from "vitest";
import { cleanup, render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import PagosPage from "../pages/rentas/PagosPage.jsx";
import { paymentService, reconciliationService, taxpayerService } from "../services/rentasService.js";

afterEach(() => { cleanup(); vi.restoreAllMocks(); });

it("distingue pagos imputados, parcialmente imputados y sin imputar según los saldos reales", async () => {
  vi.spyOn(taxpayerService, "search").mockResolvedValue([]);
  vi.spyOn(reconciliationService, "observed").mockResolvedValue([]);
  vi.spyOn(paymentService, "list").mockResolvedValue([
    { id: 1, receiptNumber: "REC-1", taxpayerId: 9, amountPaid: 100, allocatedAmount: 100, unallocatedAmount: 0, status: "REGISTERED" },
    { id: 2, receiptNumber: "REC-2", taxpayerId: 9, amountPaid: 100, allocatedAmount: 40, unallocatedAmount: 60, status: "UNALLOCATED" },
    { id: 3, receiptNumber: "REC-3", taxpayerId: 9, amountPaid: 100, allocatedAmount: 0, unallocatedAmount: 100, status: "UNALLOCATED" },
    { id: 4, receiptNumber: "REC-4", taxpayerId: 9, amountPaid: 100, allocatedAmount: 100, unallocatedAmount: 0, status: "REVERSED" },
  ]);

  render(<MemoryRouter><PagosPage /></MemoryRouter>);

  const allocated = (await screen.findByText("REC-1")).closest("tr");
  const partial = screen.getByText("REC-2").closest("tr");
  const unallocated = screen.getByText("REC-3").closest("tr");
  const reversed = screen.getByText("REC-4").closest("tr");

  expect(within(allocated).getByText("Imputado")).toBeDefined();
  expect(within(allocated).queryByRole("button", { name: "Imputar" })).toBeNull();
  expect(within(partial).getByText("Parcialmente imputado")).toBeDefined();
  expect(within(partial).getByRole("button", { name: "Imputar" })).toBeDefined();
  expect(within(unallocated).getAllByText("Sin imputar")).toHaveLength(2);
  expect(within(unallocated).getByRole("button", { name: "Imputar" })).toBeDefined();
  expect(within(reversed).queryByRole("button", { name: "Imputar" })).toBeNull();
});
