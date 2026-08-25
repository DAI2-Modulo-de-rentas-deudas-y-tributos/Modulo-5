import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import App from "../App.jsx";

/** Smoke test del ingreso: valida el formulario y llega al panel de inicio. */
describe("ingreso al área de trabajo", () => {
  afterEach(() => {
    cleanup();
    sessionStorage.clear();
    window.history.pushState({}, "", "/");
  });

  it("muestra el login cuando no hay sesión", () => {
    render(<App />);
    expect(screen.getByRole("heading", { name: /iniciar sesión/i })).toBeDefined();
  });

  it("marca los campos obligatorios sin llamar al backend", async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getByRole("button", { name: /ingresar/i }));

    expect(await screen.findByText(/ingresá tu usuario/i)).toBeDefined();
    expect(screen.getByText(/ingresá tu contraseña/i)).toBeDefined();
  });

  it("informa credenciales inválidas", async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.type(screen.getByLabelText(/usuario/i), "mrivas");
    await user.type(screen.getByLabelText(/contraseña/i), "incorrecta");
    await user.click(screen.getByRole("button", { name: /ingresar/i }));

    expect(await screen.findByText(/usuario o contraseña incorrectos/i)).toBeDefined();
  });

  it("ingresa y muestra el panel con los módulos del área", async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.type(screen.getByLabelText(/usuario/i), "mrivas");
    await user.type(screen.getByLabelText(/contraseña/i), "rentas123");
    await user.click(screen.getByRole("button", { name: /ingresar/i }));

    await waitFor(() =>
      expect(screen.getByRole("heading", { name: /hola, mariana/i })).toBeDefined(),
    );

    // La navegación lateral lista un ítem por módulo funcional.
    expect(screen.getByRole("link", { name: /liquidaciones/i })).toBeDefined();
    expect(screen.getByRole("link", { name: /pagos/i })).toBeDefined();
    expect(screen.getByRole("link", { name: /exenciones/i })).toBeDefined();
  });

  it("oculta la bitácora de eventos al personal sin rol supervisor", async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.type(screen.getByLabelText(/usuario/i), "mrivas");
    await user.type(screen.getByLabelText(/contraseña/i), "rentas123");
    await user.click(screen.getByRole("button", { name: /ingresar/i }));

    await waitFor(() =>
      expect(screen.getByRole("heading", { name: /hola, mariana/i })).toBeDefined(),
    );
    expect(screen.queryByRole("link", { name: /bitácora/i })).toBeNull();
  });

  it("muestra la bitácora al supervisor", async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.type(screen.getByLabelText(/usuario/i), "jlopez");
    await user.type(screen.getByLabelText(/contraseña/i), "rentas123");
    await user.click(screen.getByRole("button", { name: /ingresar/i }));

    await waitFor(() =>
      expect(screen.getByRole("heading", { name: /hola, julián/i })).toBeDefined(),
    );
    expect(screen.getByRole("link", { name: /bitácora/i })).toBeDefined();
  });
});
