import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import App from "../App.jsx";
import { ingresarComoAgente, ingresarComoContribuyente } from "./helpers/ingresar.js";
import { instalarBackendFalso } from "./fixtures/backendFalso.js";

describe("footer compartido de los portales", () => {
  afterEach(() => {
    cleanup();
    sessionStorage.clear();
    window.history.pushState({}, "", "/");
    vi.unstubAllGlobals();
  });

  it.each([
    ["Auditor", "acastro", "audit123", "Auditor"],
    ["Supervisor", "jlopez", "rentas123", "Agente"],
    ["Rentas", "mrivas", "rentas123", "Agente"],
    ["Cajero", "pcabrera", "caja123", "Agente"],
    ["Contribuyente", "jperez", "ciudadano123", "Contribuyente"],
  ])("no presenta enlaces sin destino para %s", async (_role, username, password, kind) => {
    instalarBackendFalso();
    const user = userEvent.setup();
    render(<App />);
    if (kind === "Contribuyente") await ingresarComoContribuyente(user, username, password);
    else await ingresarComoAgente(user, username, password);
    await waitFor(() => expect(screen.getByRole("contentinfo")).toBeDefined());
    expect(document.querySelectorAll('footer a[href="#"]')).toHaveLength(0);
    expect(screen.getByText(/accesos a otros trámites municipales no están disponibles/i)).toBeDefined();
    expect(screen.getByText("Portal de gestión municipal")).toBeDefined();
  });
});
