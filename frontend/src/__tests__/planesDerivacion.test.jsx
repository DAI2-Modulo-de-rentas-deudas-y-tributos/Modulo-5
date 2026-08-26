import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import App from "../App.jsx";

/**
 * Derivación de una solicitud de plan al Supervisor, desde la pantalla.
 * El analista deriva; sólo el Supervisor resuelve lo derivado.
 */
async function entrarAPlanes(user, usuario) {
  render(<App />);
  await user.type(screen.getByLabelText(/usuario/i), usuario);
  await user.type(screen.getByLabelText(/contraseña/i), "rentas123");
  await user.click(screen.getByRole("button", { name: /ingresar/i }));
  await waitFor(() => expect(screen.getByRole("heading", { name: /hola,/i })).toBeDefined());
  await user.click(screen.getByRole("link", { name: /planes de pago/i }));
  await screen.findByRole("navigation", { name: /breadcrumb/i });
}

describe("derivación de planes al supervisor", () => {
  let user;

  beforeEach(() => {
    user = userEvent.setup();
  });

  afterEach(() => {
    cleanup();
    sessionStorage.clear();
    window.history.pushState({}, "", "/");
  });

  it("aclara que la derivación no sale del módulo", async () => {
    await entrarAPlanes(user, "mrivas");

    expect(await screen.findByText(/la derivación no sale de rentas/i)).toBeDefined();
  });

  it("el analista ve el botón de derivar", async () => {
    await entrarAPlanes(user, "mrivas");

    expect(await screen.findByRole("button", { name: /^derivar$/i })).toBeDefined();
  });

  it("el supervisor no lo ve: él resuelve, no deriva", async () => {
    await entrarAPlanes(user, "jlopez");
    await waitFor(() => expect(screen.queryByText(/cargando información/i)).toBeNull());

    expect(screen.queryByRole("button", { name: /^derivar$/i })).toBeNull();
    expect(screen.getAllByRole("button", { name: /resolver/i }).length).toBeGreaterThan(0);
  });

  it("exige el motivo antes de derivar", async () => {
    await entrarAPlanes(user, "mrivas");
    await user.click(await screen.findByRole("button", { name: /^derivar$/i }));

    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: /derivar al supervisor/i }));

    expect(await within(dialog).findByText(/por qué la derivás/i)).toBeDefined();
  });

  it("deriva y avisa que no se publicó ningún evento", async () => {
    await entrarAPlanes(user, "mrivas");
    await user.click(await screen.findByRole("button", { name: /^derivar$/i }));

    const dialog = await screen.findByRole("dialog");
    await user.type(
      within(dialog).getByLabelText(/motivo de la derivación/i),
      "Pide más cuotas de las habituales",
    );
    await user.click(within(dialog).getByRole("button", { name: /derivar al supervisor/i }));

    expect(await screen.findByText(/solicitud derivada/i)).toBeDefined();
    expect(screen.getByText(/no se publicó ningún evento/i)).toBeDefined();

    // Y el analista deja de poder resolverla: la fila queda esperando al Supervisor.
    expect(await screen.findByText(/esperando al supervisor/i)).toBeDefined();
  });
});
