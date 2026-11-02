import { afterEach, expect, it, vi } from "vitest";
import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import LiquidacionesPage from "../pages/rentas/LiquidacionesPage.jsx";
import { settlementService } from "../services/rentasService.js";

vi.mock("../hooks/useTaxpayerIndex.js", () => ({ default: () => ({ nameOf: (id) => `Contribuyente #${id}`, options: [{ value: "7", label: "Ciudadano de prueba — 20-00000000-0" }] }) }));
vi.mock("../hooks/useTaxConcepts.js", () => ({ default: () => ({ options: [{ value: "ABL", label: "Alumbrado, barrido y limpieza" }], labelOf: (code) => code === "ABL" ? "Alumbrado, barrido y limpieza" : code }) }));

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

it("valida los datos mínimos antes de solicitar una liquidación", async () => {
  const user = userEvent.setup();
  vi.spyOn(settlementService, "list").mockResolvedValue([]);
  const generate = vi.spyOn(settlementService, "generate");
  render(<MemoryRouter><LiquidacionesPage /></MemoryRouter>);

  await user.click(await screen.findByRole("button", { name: "Nueva liquidación" }));
  const dialog = await screen.findByRole("dialog", { name: "Nueva liquidación" });
  await user.click(within(dialog).getByRole("button", { name: "Generar liquidación y deuda" }));
  expect(await within(dialog).findByText("Seleccioná el contribuyente.")).toBeDefined();
  expect(within(dialog).getByText("Seleccioná el concepto.")).toBeDefined();
  expect(within(dialog).getByText("Usá el formato AAAA-MM.")).toBeDefined();
  expect(within(dialog).getByText("Ingresá un importe mayor a cero.")).toBeDefined();
  expect(within(dialog).getByText("Indicá el vencimiento.")).toBeDefined();
  expect(generate).not.toHaveBeenCalled();
});

it("emite una liquidación con formato fiscal válido y permite recuperarse de un error del servicio", async () => {
  const user = userEvent.setup();
  vi.spyOn(settlementService, "list").mockResolvedValue([]);
  const generate = vi.spyOn(settlementService, "generate")
    .mockRejectedValueOnce(new Error("Ya existe la liquidación del período"))
    .mockResolvedValueOnce({ id: 88, amount: 1250 });
  render(<MemoryRouter><LiquidacionesPage /></MemoryRouter>);

  await user.click(await screen.findByRole("button", { name: "Nueva liquidación" }));
  const dialog = await screen.findByRole("dialog", { name: "Nueva liquidación" });
  await user.selectOptions(within(dialog).getByLabelText(/Contribuyente/), "7");
  await user.selectOptions(within(dialog).getByLabelText(/Concepto/), "ABL");
  await user.type(within(dialog).getByLabelText(/Período fiscal/), "2026-10");
  await user.type(within(dialog).getByLabelText(/Vencimiento/), "2026-11-15");
  await user.type(within(dialog).getByLabelText(/Base imponible/), "1250");

  await user.click(within(dialog).getByRole("button", { name: "Generar liquidación y deuda" }));
  expect(await within(dialog).findByText("Ya existe la liquidación del período")).toBeDefined();
  await user.click(within(dialog).getByRole("button", { name: "Generar liquidación y deuda" }));
  expect(generate).toHaveBeenCalledWith({ taxpayerId: "7", conceptCode: "ABL", period: "2026-10", baseAmount: "1250", dueDate: "2026-11-15" });
  expect(await screen.findByText(/#88 por/)).toBeDefined();
});
