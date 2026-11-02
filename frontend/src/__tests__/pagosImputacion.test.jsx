import { afterEach, expect, it, vi } from "vitest";
import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import PagosPage from "../pages/rentas/PagosPage.jsx";
import { debtService, paymentService, reconciliationService, taxpayerService } from "../services/rentasService.js";

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

it("muestra la deuda devuelta por el servicio al completar la imputación", async () => {
  const user = userEvent.setup();
  vi.spyOn(taxpayerService, "search").mockResolvedValue([]);
  vi.spyOn(reconciliationService, "observed").mockResolvedValue([]);
  vi.spyOn(debtService, "list").mockResolvedValue([{ id: 42, taxpayerId: 9, conceptCode: "ABL", outstandingAmount: 150 }]);
  vi.spyOn(paymentService, "allocate").mockResolvedValue({ debtId: 42 });
  vi.spyOn(paymentService, "list").mockResolvedValue([
    { id: 1, receiptNumber: "REC-1", taxpayerId: 9, amountPaid: 100, allocatedAmount: 0, unallocatedAmount: 100, status: "UNALLOCATED" },
  ]);

  render(<MemoryRouter><PagosPage /></MemoryRouter>);
  await user.click(await screen.findByRole("button", { name: "Imputar" }));
  const dialog = await screen.findByRole("dialog", { name: "Imputar REC-1" });
  await user.selectOptions(await within(dialog).findByRole("combobox"), "42");
  await user.click(within(dialog).getByRole("button", { name: "Imputar" }));

  expect(await screen.findByText("Se aplicó a la deuda #42.")).toBeDefined();
});

it("importa un lote de conciliación y presenta el resultado informado por el backend", async () => {
  const user = userEvent.setup();
  vi.spyOn(taxpayerService, "search").mockResolvedValue([]);
  vi.spyOn(paymentService, "list").mockResolvedValue([]);
  vi.spyOn(reconciliationService, "observed").mockResolvedValue([]);
  const importBatch = vi.spyOn(reconciliationService, "importBatch").mockResolvedValue({ reconciledItems: 2, observedItems: 1, notFoundItems: 0 });

  render(<MemoryRouter><PagosPage /></MemoryRouter>);
  await user.click(await screen.findByRole("button", { name: "Importar lote" }));
  const dialog = await screen.findByRole("dialog", { name: "Importar conciliación" });
  await user.click(within(dialog).getByRole("button", { name: "Importar" }));
  expect(await within(dialog).findByText("Indicá la referencia y al menos un movimiento válido.")).toBeDefined();
  expect(importBatch).not.toHaveBeenCalled();

  await user.type(within(dialog).getAllByRole("textbox")[0], "BANK-2026-10");
  await user.click(within(dialog).getByRole("button", { name: "Importar" }));
  expect(importBatch).toHaveBeenCalledWith("BANK-2026-10", [{ externalReference: "TX-001", taxpayerDocument: "00000000", amount: 1000, paidAt: "2026-09-04T12:00:00-03:00" }]);
  expect(await screen.findByText("2 conciliados, 1 observados y 0 no encontrados.")).toBeDefined();
});

it("valida los datos para resolver una conciliación y registra el pago elegido", async () => {
  const user = userEvent.setup();
  vi.spyOn(taxpayerService, "search").mockResolvedValue([]);
  vi.spyOn(paymentService, "list").mockResolvedValue([]);
  vi.spyOn(reconciliationService, "observed").mockResolvedValue([{ id: 5, externalReference: "TX-5", taxpayerDocument: "00000000", amount: 75, status: "OBSERVED" }]);
  const resolve = vi.spyOn(reconciliationService, "resolve").mockResolvedValue({});

  render(<MemoryRouter><PagosPage /></MemoryRouter>);
  await user.click(await screen.findByRole("button", { name: "Resolver" }));
  const dialog = await screen.findByRole("dialog", { name: "Resolver TX-5" });
  await user.click(within(dialog).getByRole("button", { name: "Conciliar" }));
  expect(await within(dialog).findByText("Indicá el pago y el motivo de la resolución.")).toBeDefined();
  expect(resolve).not.toHaveBeenCalled();

  await user.type(within(dialog).getByRole("spinbutton"), "45");
  await user.type(within(dialog).getByRole("textbox"), "Comprobante verificado");
  await user.click(within(dialog).getByRole("button", { name: "Conciliar" }));
  expect(resolve).toHaveBeenCalledWith(5, 45, "Comprobante verificado");
  expect(await screen.findByText("La conciliación manual quedó persistida y auditada.")).toBeDefined();
});

it("exige motivo al solicitar reversión y conserva el feedback si el servicio falla", async () => {
  const user = userEvent.setup();
  vi.spyOn(taxpayerService, "search").mockResolvedValue([]);
  vi.spyOn(reconciliationService, "observed").mockResolvedValue([]);
  vi.spyOn(paymentService, "list").mockResolvedValue([
    { id: 7, receiptNumber: "REC-7", taxpayerId: 9, amountPaid: 100, allocatedAmount: 100, unallocatedAmount: 0, status: "REGISTERED" },
  ]);
  const reverse = vi.spyOn(paymentService, "reverse").mockRejectedValue(new Error("Solicitud duplicada"));

  render(<MemoryRouter><PagosPage /></MemoryRouter>);
  await user.click(await screen.findByRole("button", { name: "Reversar" }));
  const dialog = await screen.findByRole("dialog", { name: "Reversar REC-7" });
  await user.click(within(dialog).getByRole("button", { name: "Confirmar reversión" }));
  expect(await within(dialog).findByText("El motivo de la solicitud de reversión es obligatorio.")).toBeDefined();
  expect(reverse).not.toHaveBeenCalled();

  await user.type(within(dialog).getByRole("textbox"), "Pago duplicado");
  await user.click(within(dialog).getByRole("button", { name: "Confirmar reversión" }));
  expect(reverse).toHaveBeenCalledWith({ paymentId: 7, reason: "Pago duplicado" });
  expect(await within(dialog).findByText("Solicitud duplicada")).toBeDefined();
});
