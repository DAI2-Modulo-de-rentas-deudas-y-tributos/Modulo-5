import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import App from "../App.jsx";
import { ingresarComoAgente } from "./helpers/ingresar.js";

/**
 * Configuración de tributos desde la pantalla: el analista propone, el Supervisor
 * aprueba, y ninguna versión se borra al ser reemplazada.
 */
async function entrar(user, usuario) {
  render(<App />);
  await ingresarComoAgente(user, usuario, "rentas123");
  await waitFor(() => expect(screen.getByRole("heading", { name: /hola,/i })).toBeDefined());
  await user.click(screen.getByRole("link", { name: /configuración de tributos/i }));
  await waitFor(() => expect(screen.queryByText(/cargando información/i)).toBeNull());
}

describe("configuración de tributos", () => {
  let user;

  beforeEach(() => {
    user = userEvent.setup();
  });

  afterEach(() => {
    cleanup();
    sessionStorage.clear();
    window.history.pushState({}, "", "/");
  });

  it("aclara que los cambios rigen hacia adelante", async () => {
    await entrar(user, "mrivas");

    expect(await screen.findByText(/los cambios rigen hacia adelante/i)).toBeDefined();
  });

  it("lista los conceptos con su versión vigente", async () => {
    await entrar(user, "mrivas");

    expect(await screen.findByText("TASA_SERVICIOS")).toBeDefined();
    expect(screen.getAllByText(/v\d/).length).toBeGreaterThan(0);
  });

  it("muestra el historial completo de versiones", async () => {
    await entrar(user, "mrivas");
    await user.click((await screen.findAllByRole("button", { name: /versiones/i }))[0]);

    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText(/ninguna versión se borra/i)).toBeDefined();
    expect(within(dialog).getAllByText(/v[123]/).length).toBeGreaterThan(0);
  });

  it("valida la propuesta antes de enviarla", async () => {
    await entrar(user, "mrivas");
    await user.click((await screen.findAllByRole("button", { name: /nueva versión/i }))[0]);

    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: /enviar a aprobación/i }));

    expect(await within(dialog).findByText(/tiene que terminar después de empezar/i)).toBeDefined();
  });

  it("el analista propone y queda esperando al Supervisor", async () => {
    await entrar(user, "mrivas");
    await user.click((await screen.findAllByRole("button", { name: /nueva versión/i }))[0]);

    const dialog = await screen.findByRole("dialog");
    await user.type(within(dialog).getByLabelText(/vigente desde/i), "2029-01-01");
    await user.type(within(dialog).getByLabelText(/vigente hasta/i), "2029-12-31");
    await user.type(
      within(dialog).getByLabelText(/descripción del cambio/i),
      "Actualización de alícuota",
    );
    await user.click(within(dialog).getByRole("button", { name: /enviar a aprobación/i }));

    expect(await screen.findByText(/versión enviada a aprobación/i)).toBeDefined();
    // Figura como título de la bandeja y como estado de la fila.
    expect((await screen.findAllByText(/esperando aprobación/i)).length).toBeGreaterThan(1);
    // El analista no puede aprobarla.
    expect(await screen.findByText(/espera al supervisor/i)).toBeDefined();
  });

  it("el Supervisor ve la comparación y aprueba", async () => {
    await entrar(user, "jlopez");

    await user.click(await screen.findByRole("button", { name: /evaluar/i }));
    const dialog = await screen.findByRole("dialog");

    // La tabla compara lo vigente contra lo propuesto.
    expect(within(dialog).getByText(/vigente/i)).toBeDefined();
    expect(within(dialog).getByText(/propuesto/i)).toBeDefined();
    expect(within(dialog).getByText(/qué pasa al aprobar/i)).toBeDefined();

    await user.click(within(dialog).getByRole("button", { name: /confirmar/i }));

    expect(await screen.findByText(/configuración aprobada/i)).toBeDefined();
    expect(screen.getByText(/la anterior quedó inactiva pero se conserva/i)).toBeDefined();
  });
});
