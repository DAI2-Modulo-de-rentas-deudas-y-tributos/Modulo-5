import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import App from "../App.jsx";
import { instalarBackendFalso } from "./fixtures/backendFalso.js";

const SESION_M1 = {
  token: "sesion-ciudadana",
  user: { displayName: "Juan Pérez", role: "TAXPAYER", authorities: ["TAXPAYER"], taxpayerId: 123, authSource: "M1" },
};

const problema = (status, code, message) =>
  new Response(JSON.stringify({ status, code, message }), { status, headers: { "content-type": "application/json" } });

const encabezado = (fetchFalso, ruta, nombre) => {
  const llamada = fetchFalso.mock.calls.find(([url]) => String(url).includes(ruta));
  return new Headers(llamada?.[1]?.headers).get(nombre);
};

async function ingresarConCiudadanos(user, email = "juan@example.invalid", password = "clave-fixture") {
  await user.click(screen.getByRole("button", { name: /cuenta de ciudadanos/i }));
  await user.type(screen.getByLabelText(/correo de ciudadanos/i), email);
  await user.type(screen.getByLabelText(/contraseña/i), password);
  await user.click(screen.getByRole("button", { name: /^ingresar$/i }));
}

describe("ingreso con la cuenta de Ciudadanos (M1)", () => {
  let user;

  beforeEach(() => { user = userEvent.setup(); });

  afterEach(() => {
    cleanup();
    sessionStorage.clear();
    window.history.pushState({}, "", "/");
    vi.unstubAllGlobals();
  });

  it("ofrece las dos cuentas y valida el correo sin llamar al backend", async () => {
    const fetchFalso = instalarBackendFalso();
    render(<App />);
    expect(screen.getByRole("button", { name: /cuenta de rentas/i }).getAttribute("aria-pressed")).toBe("true");

    await user.click(screen.getByRole("button", { name: /cuenta de ciudadanos/i }));
    await user.click(screen.getByRole("button", { name: /^ingresar$/i }));

    expect(await screen.findByText(/ingresá tu correo/i)).toBeDefined();
    expect(screen.getByText(/registrate en el portal ciudadanos/i)).toBeDefined();
    expect(fetchFalso.llamadas.some((llamada) => llamada.ruta === "/api/v1/auth/m1/login")).toBe(false);
  });

  it("el vecino entra a su portal y las consultas viajan con la sesión Bearer", async () => {
    const fetchFalso = instalarBackendFalso({ "POST /api/v1/auth/m1/login": SESION_M1 });
    render(<App />);

    await ingresarConCiudadanos(user);

    await waitFor(() => expect(screen.getByRole("heading", { name: /hola, juan/i })).toBeDefined());
    const login = fetchFalso.llamadas.find((llamada) => llamada.ruta === "/api/v1/auth/m1/login");
    expect(login.cuerpo).toEqual({ email: "juan@example.invalid", password: "clave-fixture" });
    expect(encabezado(fetchFalso, "/api/v1/auth/m1/login", "Authorization")).toBeNull();
    expect(sessionStorage.getItem("rentas.authSource")).toBe("m1");
    await waitFor(() => expect(encabezado(fetchFalso, "/api/v1/taxpayers/123", "Authorization")).toBe("Bearer sesion-ciudadana"));
    expect(encabezado(fetchFalso, "/api/v1/taxpayers/123", "X-Demo-Session")).toBeNull();
  });

  it("muestra el motivo cuando la cuenta no está vinculada a Rentas", async () => {
    instalarBackendFalso({
      "POST /api/v1/auth/m1/login": () => problema(403, "M1_CITIZEN_NOT_LINKED", "Tu cuenta de Ciudadanos todavía no está vinculada a Rentas"),
    });
    render(<App />);

    await ingresarConCiudadanos(user);

    expect(await screen.findByText(/todavía no está vinculada a rentas/i)).toBeDefined();
    expect(sessionStorage.getItem("rentas.token")).toBeNull();
  });

  it("restaura la sesión ciudadana con /auth/m1/me y la cierra contra el backend", async () => {
    sessionStorage.setItem("rentas.token", "sesion-guardada");
    sessionStorage.setItem("rentas.authSource", "m1");
    const fetchFalso = instalarBackendFalso({
      "GET /api/v1/auth/m1/me": SESION_M1.user,
      "POST /api/v1/auth/m1/logout": () => new Response(null, { status: 204 }),
    });
    window.history.pushState({}, "", "/portal");
    render(<App />);

    await waitFor(() => expect(screen.getByRole("heading", { name: /hola, juan/i })).toBeDefined());
    expect(encabezado(fetchFalso, "/api/v1/auth/m1/me", "Authorization")).toBe("Bearer sesion-guardada");

    await user.click(screen.getByRole("button", { name: /juan pérez/i }));
    await user.click(await screen.findByRole("menuitem", { name: /cerrar sesión/i }));

    await waitFor(() => expect(encabezado(fetchFalso, "/api/v1/auth/m1/logout", "Authorization")).toBe("Bearer sesion-guardada"));
    expect(sessionStorage.getItem("rentas.authSource")).toBeNull();
  });
});
