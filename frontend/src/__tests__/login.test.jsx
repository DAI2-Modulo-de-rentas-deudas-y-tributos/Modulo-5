import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import App from "../App.jsx";
import { ingresarComoAgente, ingresarComoContribuyente } from "./helpers/ingresar.js";

/**
 * Ingreso: dos puertas, una por tipo de usuario. Abre la del contribuyente y el agente
 * municipal cruza a la suya. Entrar por la puerta equivocada no abre sesión aunque las
 * credenciales sean correctas: la pantalla avisa y ofrece la que corresponde.
 */
describe("ingreso al área de trabajo", () => {
  let user;

  beforeEach(() => {
    user = userEvent.setup();
  });

  afterEach(() => {
    cleanup();
    sessionStorage.clear();
    window.history.pushState({}, "", "/");
  });

  it("abre en el acceso ciudadano cuando no hay sesión", () => {
    render(<App />);
    expect(screen.getByRole("heading", { name: /entrar al portal/i })).toBeDefined();
    expect(screen.getByText(/estás en el acceso para ciudadanos/i)).toBeDefined();
  });

  it("marca los campos obligatorios sin llamar al backend", async () => {
    render(<App />);

    await user.click(screen.getByRole("button", { name: /ingresar al portal/i }));

    expect(await screen.findByText(/ingresá tu usuario/i)).toBeDefined();
    expect(screen.getByText(/ingresá tu contraseña/i)).toBeDefined();
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

  it("el agente cruza de puerta, elige su área y entra", async () => {
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

  it("no deja al contribuyente entrar por la puerta de agentes y lo lleva a la suya", async () => {
    render(<App />);

    await user.click(screen.getByRole("button", { name: /trabajo en el municipio/i }));
    await user.type(screen.getByLabelText(/usuario/i), "jperez");
    await user.type(screen.getByLabelText(/contraseña/i), "ciudadano123");
    await user.click(screen.getByRole("button", { name: /entrar a personal de rentas/i }));

    expect(await screen.findByText(/estás en la pestaña equivocada/i)).toBeDefined();
    // Las credenciales eran válidas, pero la sesión no se abrió.
    expect(screen.queryByRole("heading", { name: /hola,/i })).toBeNull();

    await user.click(screen.getByRole("button", { name: /llevame a la puerta correcta/i }));
    expect(screen.getByRole("heading", { name: /entrar al portal/i })).toBeDefined();

    // Los datos quedan cargados: termina de entrar con un click.
    await user.click(screen.getByRole("button", { name: /ingresar al portal/i }));
    await waitFor(() => expect(screen.getByRole("heading", { name: /hola, juan/i })).toBeDefined());
  });

  it("no deja a personal de rentas entrar por el área de caja y lo lleva a la suya", async () => {
    render(<App />);

    await user.click(screen.getByRole("button", { name: /trabajo en el municipio/i }));
    await user.click(screen.getByRole("button", { name: "Ventanilla de Caja" }));
    await user.type(screen.getByLabelText(/usuario/i), "mrivas");
    await user.type(screen.getByLabelText(/contraseña/i), "rentas123");
    await user.click(screen.getByRole("button", { name: /entrar a ventanilla de caja/i }));

    expect(await screen.findByText(/estás en la pestaña equivocada/i)).toBeDefined();
    expect(screen.queryByRole("heading", { name: /hola,/i })).toBeNull();

    await user.click(screen.getByRole("button", { name: /llevame a la puerta correcta/i }));
    await user.click(screen.getByRole("button", { name: /entrar a personal de rentas/i }));

    await waitFor(() =>
      expect(screen.getByRole("heading", { name: /hola, mariana/i })).toBeDefined(),
    );
  });
});
