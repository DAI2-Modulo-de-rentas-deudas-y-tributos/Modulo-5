import { afterEach, expect, it, vi } from "vitest";
import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import BoletasPage from "../pages/rentas/BoletasPage.jsx";
import { billService, debtService, taxpayerService } from "../services/rentasService.js";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

it("emite una boleta sólo para deuda con saldo y actualiza la bandeja", async () => {
  const user = userEvent.setup();
  const bill = { id: 22, taxpayerId: 7, conceptCode: "ABL", debtId: 51, amount: 750, dueDate: "2026-11-10", issuedAt: "2026-10-27T10:00:00Z", status: "ISSUED" };
  vi.spyOn(billService, "list").mockResolvedValue([bill]);
  vi.spyOn(debtService, "list").mockResolvedValue([
    { id: 51, taxpayerId: 7, conceptCode: "ABL", outstandingAmount: 750, dueDate: "2026-11-10" },
    { id: 52, taxpayerId: 7, conceptCode: "ABL", outstandingAmount: 0, dueDate: "2026-10-10" },
  ]);
  vi.spyOn(taxpayerService, "search").mockResolvedValue([{ id: 7, name: "Ciudadano de prueba", document: "20-00000000-0" }]);
  const issue = vi.spyOn(billService, "issue").mockResolvedValue(bill);

  render(<MemoryRouter><BoletasPage /></MemoryRouter>);

  const row = (await screen.findByText("#22")).closest("tr");
  expect(within(row).getByText("ABL")).toBeDefined();
  expect(within(row).getByText("#51")).toBeDefined();
  await user.selectOptions(screen.getByLabelText("Estado"), "ISSUED");
  await user.click(screen.getByRole("button", { name: "Emitir boleta" }));
  const dialog = await screen.findByRole("dialog", { name: "Emitir boleta" });
  const debtSelect = within(dialog).getByRole("combobox");
  expect(debtSelect.textContent).toContain("#51");
  expect(debtSelect.textContent).not.toContain("#52");

  await user.click(within(dialog).getByRole("button", { name: "Emitir" }));
  expect(await within(dialog).findByText("Seleccioná la deuda a facturar.")).toBeDefined();
  expect(issue).not.toHaveBeenCalled();
  await user.selectOptions(debtSelect, "51");
  expect(within(dialog).getByText("Concepto:").parentElement.textContent).toContain("ABL");
  await user.click(within(dialog).getByRole("button", { name: "Emitir" }));

  expect(issue).toHaveBeenCalledWith({ debtId: "51" });
  expect(await screen.findByText(/Boleta #22 por/)).toBeDefined();
  expect(billService.list).toHaveBeenCalledTimes(3);
});

it("informa errores al cargar o emitir sin perder el formulario", async () => {
  const user = userEvent.setup();
  vi.spyOn(billService, "list").mockRejectedValue(new Error("No se pudieron cargar boletas"));
  vi.spyOn(debtService, "list").mockResolvedValue([{ id: 51, taxpayerId: 7, conceptCode: "ABL", outstandingAmount: 750, dueDate: "2026-11-10" }]);
  vi.spyOn(taxpayerService, "search").mockResolvedValue([]);
  const issue = vi.spyOn(billService, "issue").mockRejectedValue(new Error("La deuda ya tiene boleta"));

  render(<MemoryRouter><BoletasPage /></MemoryRouter>);
  expect(await screen.findByText("No se pudieron cargar boletas")).toBeDefined();
  await user.click(screen.getByRole("button", { name: "Emitir boleta" }));
  const dialog = await screen.findByRole("dialog", { name: "Emitir boleta" });
  await user.selectOptions(within(dialog).getByRole("combobox"), "51");
  await user.click(within(dialog).getByRole("button", { name: "Emitir" }));
  expect(await within(dialog).findByText("La deuda ya tiene boleta")).toBeDefined();
  expect(issue).toHaveBeenCalledTimes(1);
});
