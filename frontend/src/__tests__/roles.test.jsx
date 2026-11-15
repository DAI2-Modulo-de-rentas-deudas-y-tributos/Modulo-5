import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import App from "../App.jsx";
import { ingresarComoAgente } from "./helpers/ingresar.js";
import { instalarBackendFalso } from "./fixtures/backendFalso.js";

const roles = [
  ["PERSONAL", "mrivas", "rentas123", /^contribuyentes$/i, /^bitácora de eventos$/i],
  ["SUPERVISOR", "jlopez", "rentas123", /^bitácora de eventos$/i, /^mis deudas$/i],
  ["CAJERO", "pcabrera", "caja123", /^cobros$/i, /^bitácora de eventos$/i],
  ["AUDITOR", "acastro", "audit123", /^integraciones$/i, /^cobros$/i],
  ["CONTRIBUYENTE", "jperez", "ciudadano123", /^mis deudas$/i, /^contribuyentes$/i],
];

describe("navegación y permisos por rol", () => {
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

  it.each(roles)("muestra el espacio correspondiente a %s", async (_role, username, password, visible, hidden) => {
    render(<App />);
    await ingresarComoAgente(user, username, password);

    expect(await screen.findByRole("link", { name: visible })).toBeDefined();
    expect(screen.queryByRole("link", { name: hidden })).toBeNull();
  });

  it("devuelve a Caja a un usuario sin permiso que abre una ruta de Supervisor", async () => {
    window.history.pushState({}, "", "/rentas/eventos");
    render(<App />);
    await ingresarComoAgente(user, "pcabrera", "caja123");

    expect(await screen.findByRole("link", { name: /^cobros$/i })).toBeDefined();
    await waitFor(() => expect(screen.queryByRole("link", { name: /^bitácora de eventos$/i })).toBeNull());
  });
});
