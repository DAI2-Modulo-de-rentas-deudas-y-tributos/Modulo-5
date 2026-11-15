import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import App from "../App.jsx";
import { ingresarComoAgente, ingresarComoContribuyente } from "./helpers/ingresar.js";
import { instalarBackendFalso } from "./fixtures/backendFalso.js";

/** Ingreso único: el perfil autenticado determina el espacio de destino. */
describe("ingreso al área de trabajo", () => {
  let user;

  beforeEach(() => {
    user = userEvent.setup();
    instalarBackendFalso();
  });

  afterEach(() => {
    cleanup();
    sessionStorage.clear();
    window.history.pushState({}, "", "/");
    vi.unstubAllGlobals();
  });

  it("abre en el acceso unificado cuando no hay sesión", () => {
    render(<App />);
    expect(screen.getByRole("heading", { name: /iniciar sesión/i })).toBeDefined();
    expect(screen.getByText(/tu perfil determina los permisos/i)).toBeDefined();
    expect(screen.queryByText(/acceso ciudadano/i)).toBeNull();
    expect(screen.queryByText(/acceso municipal/i)).toBeNull();
  });

  it("marca los campos obligatorios sin llamar al backend", async () => {
    render(<App />);

    await user.click(screen.getByRole("button", { name: /^ingresar$/i }));

    expect(await screen.findByText(/ingresá tu usuario/i)).toBeDefined();
    expect(screen.getByText(/ingresá tu contraseña/i)).toBeDefined();
    expect(screen.getByLabelText(/usuario/i).getAttribute("aria-invalid")).toBe("true");
    expect(screen.getByLabelText(/contraseña/i).getAttribute("aria-describedby")).toBe("password-error");
  });

  it("permite mostrar y ocultar la contraseña", async () => {
    render(<App />);
    await user.type(screen.getByLabelText(/usuario/i), "jperez");
    await user.type(screen.getByLabelText(/contraseña/i), "ciudadano123");
    expect(screen.getByLabelText(/contraseña/i).type).toBe("password");
    await user.click(screen.getByRole("button", { name: "Mostrar" }));
    expect(screen.getByLabelText(/contraseña/i).type).toBe("text");
    await user.click(screen.getByRole("button", { name: "Ocultar" }));
    expect(screen.getByLabelText(/contraseña/i).type).toBe("password");
  });

  it("informa credenciales inválidas", async () => {
    render(<App />);

    await ingresarComoContribuyente(user, "jperez", "incorrecta");

    expect(await screen.findByText(/usuario o contraseña incorrectos/i)).toBeDefined();
  });

  it("el contribuyente entra a su portal", async () => {
    render(<App />);

    await ingresarComoContribuyente(user);

    await waitFor(() => expect(screen.getByRole("heading", { name: /hola, juan/i })).toBeDefined());
  });

  it("deriva al agente a su área después de autenticarlo", async () => {
    render(<App />);

    await ingresarComoAgente(user, "mrivas");

    await waitFor(() =>
      expect(screen.getByRole("heading", { name: /hola, mariana/i })).toBeDefined(),
    );

    // La navegación lateral lista un ítem por módulo funcional.
    expect(screen.getByRole("link", { name: /liquidaciones/i })).toBeDefined();
    expect(screen.getByRole("link", { name: /pagos/i })).toBeDefined();
    expect(screen.getByRole("link", { name: /exenciones/i })).toBeDefined();
  });

  it("oculta la bitácora de eventos al personal sin rol supervisor", async () => {
    render(<App />);

    await ingresarComoAgente(user, "mrivas");

    await waitFor(() =>
      expect(screen.getByRole("heading", { name: /hola, mariana/i })).toBeDefined(),
    );
    expect(screen.queryByRole("link", { name: /bitácora/i })).toBeNull();
  });

  it("muestra la bitácora al supervisor", async () => {
    render(<App />);

    await ingresarComoAgente(user, "jlopez");

    await waitFor(() =>
      expect(screen.getByRole("heading", { name: /hola, julián/i })).toBeDefined(),
    );
    expect(screen.getByRole("link", { name: /bitácora/i })).toBeDefined();
  });

  it("no pide elegir tipo de usuario ni área de trabajo", () => {
    render(<App />);
    expect(screen.queryByRole("button", { name: /trabajo en el municipio/i })).toBeNull();
    expect(screen.queryByRole("group", { name: /área de trabajo/i })).toBeNull();
  });
});
