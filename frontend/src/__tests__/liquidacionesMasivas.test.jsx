import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import App from "../App.jsx";

/**
 * Generación masiva de liquidaciones: el operador configura, previsualiza y recién
 * después confirma. El paso intermedio es el que evita crear un lote equivocado.
 */
async function abrirGeneracionMasiva(user) {
  render(<App />);
  await user.type(screen.getByLabelText(/usuario/i), "mrivas");
  await user.type(screen.getByLabelText(/contraseña/i), "rentas123");
  await user.click(screen.getByRole("button", { name: /ingresar/i }));
  await waitFor(() => expect(screen.getByRole("heading", { name: /hola,/i })).toBeDefined());

  await user.click(screen.getByRole("link", { name: /liquidaciones/i }));
  await user.click(await screen.findByRole("button", { name: /generación masiva/i }));
  return screen.findByRole("dialog");
}

async function completarFormulario(user, dialog, { period = "2027-09" } = {}) {
  await user.selectOptions(within(dialog).getByLabelText(/concepto/i), "TASA_SERVICIOS");
  await user.type(within(dialog).getByLabelText(/período fiscal/i), period);
  await user.type(within(dialog).getByLabelText(/vencimiento/i), "2027-09-15");
  await user.type(within(dialog).getByLabelText(/base imponible/i), "100000");
}

describe("generación masiva de liquidaciones", () => {
  let user;

  beforeEach(() => {
    user = userEvent.setup();
  });

  afterEach(() => {
    cleanup();
    sessionStorage.clear();
    window.history.pushState({}, "", "/");
  });

  it("valida el formulario antes de previsualizar", async () => {
    const dialog = await abrirGeneracionMasiva(user);
    await user.click(within(dialog).getByRole("button", { name: /previsualizar/i }));

    expect(await within(dialog).findByText(/elegí el concepto/i)).toBeDefined();
    expect(within(dialog).getByText(/formato aaaa-mm/i)).toBeDefined();
  });

  it("rechaza un período mal escrito", async () => {
    const dialog = await abrirGeneracionMasiva(user);
    await user.selectOptions(within(dialog).getByLabelText(/concepto/i), "ABL");
    await user.type(within(dialog).getByLabelText(/período fiscal/i), "septiembre");
    await user.click(within(dialog).getByRole("button", { name: /previsualizar/i }));

    expect(await within(dialog).findByText(/formato aaaa-mm/i)).toBeDefined();
  });

  it("muestra la previsualización con la cantidad y el descuento aplicado", async () => {
    const dialog = await abrirGeneracionMasiva(user);
    await completarFormulario(user, dialog);
    await user.click(within(dialog).getByRole("button", { name: /previsualizar/i }));

    expect(await within(dialog).findByText("A generar")).toBeDefined();
    expect(within(dialog).getByText("Total a liquidar")).toBeDefined();
    // El beneficio social de Juan Pérez se ve como descuento en su fila.
    expect(within(dialog).getByText("50%")).toBeDefined();
  });

  it("informa los que quedan afuera y por qué", async () => {
    const dialog = await abrirGeneracionMasiva(user);
    // 2026-08 ya tiene la liquidación 7001 de Juan Pérez.
    await completarFormulario(user, dialog, { period: "2026-08" });
    await user.click(within(dialog).getByRole("button", { name: /previsualizar/i }));

    expect(await within(dialog).findByText(/quedan afuera del lote/i)).toBeDefined();
    expect(within(dialog).getByText(/ya tiene la liquidación #7001/i)).toBeDefined();
  });

  it("advierte sobre bloqueados y fallecidos sin excluirlos", async () => {
    const dialog = await abrirGeneracionMasiva(user);
    await completarFormulario(user, dialog);
    await user.click(within(dialog).getByRole("button", { name: /previsualizar/i }));

    expect(await within(dialog).findByText(/se generan con advertencia/i)).toBeDefined();
    expect(within(dialog).getByText(/bloqueado en m1/i)).toBeDefined();
  });

  it("permite volver atrás para corregir los parámetros", async () => {
    const dialog = await abrirGeneracionMasiva(user);
    await completarFormulario(user, dialog);
    await user.click(within(dialog).getByRole("button", { name: /previsualizar/i }));

    await user.click(await within(dialog).findByRole("button", { name: /volver/i }));

    expect(within(dialog).getByLabelText(/base imponible/i)).toBeDefined();
  });

  it("genera el lote en borrador y lo informa", async () => {
    const dialog = await abrirGeneracionMasiva(user);
    await completarFormulario(user, dialog, { period: "2027-11" });
    await user.click(within(dialog).getByRole("button", { name: /previsualizar/i }));

    await user.click(await within(dialog).findByRole("button", { name: /generar \d+ liquidaciones/i }));

    expect(await within(dialog).findByText(/liquidaciones generadas en borrador/i)).toBeDefined();

    await user.click(within(dialog).getByRole("button", { name: /finalizar/i }));
    expect(await screen.findByText(/lote generado/i)).toBeDefined();
  });
});
