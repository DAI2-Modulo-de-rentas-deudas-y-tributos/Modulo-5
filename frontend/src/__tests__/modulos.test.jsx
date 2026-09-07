import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import App from "../App.jsx";
import { ingresarComoAgente } from "./helpers/ingresar.js";
import { MODULES } from "../config/modules.js";

/**
 * Recorre la navegación como lo haría un supervisor: cada módulo funcional debe
 * montar y terminar de cargar su listado sin romperse.
 */
async function loginAs(user, username) {
  render(<App />);
  await ingresarComoAgente(user, username, "rentas123");
  await waitFor(() => expect(screen.getByRole("heading", { name: /hola,/i })).toBeDefined());
}

describe("navegación por los módulos funcionales", () => {
  let user;

  beforeEach(() => {
    user = userEvent.setup();
  });

  afterEach(() => {
    cleanup();
    sessionStorage.clear();
    window.history.pushState({}, "", "/");
  });

  it.each(MODULES.map((module) => [module.label, module]))(
    "abre %s desde el menú lateral",
    async (label, module) => {
      await loginAs(user, "jlopez");

      await user.click(screen.getByRole("link", { name: new RegExp(label, "i") }));

      // El último ítem del breadcrumb identifica la página abierta.
      const breadcrumb = await screen.findByRole("navigation", { name: /breadcrumb/i });
      expect(within(breadcrumb).getByText(module.label)).toBeDefined();

      // El listado termina de cargar sin romperse.
      await waitFor(() => expect(screen.queryByText(/cargando información/i)).toBeNull());
    },
  );

  it("registra un pago y lo imputa a la deuda seleccionada", async () => {
    await loginAs(user, "mrivas");
    await user.click(screen.getByRole("link", { name: /pagos/i }));

    await user.click(await screen.findByRole("button", { name: /registrar pago/i }));

    const dialog = await screen.findByRole("dialog");
    await user.selectOptions(
      within(dialog).getByLabelText(/contribuyente/i),
      screen.getAllByRole("option", { name: /Comercial ABC/i })[0],
    );

    const debtSelect = within(dialog).getByLabelText(/deuda a imputar/i);
    await waitFor(() => expect(within(debtSelect).getAllByRole("option").length).toBeGreaterThan(1));
    const firstDebt = within(debtSelect).getAllByRole("option")[1];
    await user.selectOptions(debtSelect, firstDebt);

    await user.type(within(dialog).getByLabelText(/importe/i), "1000");
    await user.selectOptions(within(dialog).getByLabelText(/canal/i), "VENTANILLA");
    await user.click(within(dialog).getByRole("button", { name: /^registrar$/i }));

    expect(await screen.findByText(/pago registrado/i)).toBeDefined();
  });
});
