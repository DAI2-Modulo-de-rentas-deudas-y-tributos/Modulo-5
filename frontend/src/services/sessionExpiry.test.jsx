import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AuthProvider, useAuth } from "../context/AuthContext.jsx";
import { authService } from "./rentasService.js";
import { request } from "./apiClient.js";

let pendingRequest;
function SessionActions() {
  const auth = useAuth();
  return <>
    <output>{auth.user?.role ?? "sin sesión"}</output>
    {auth.sessionNotice && <output>{auth.sessionNotice}</output>}
    <button onClick={() => { pendingRequest = request("/api/v1/taxpayers").catch(() => {}); }}>Pedir con sesión actual</button>
    <button onClick={() => auth.login({ username: "nuevo", password: "clave" })}>Abrir sesión nueva</button>
    <button onClick={() => authService.login({ username: "ingreso", password: "clave" }).catch(() => {})}>Probar ingreso</button>
  </>;
}

const expiredResponse = () => new Response(JSON.stringify({ code: "SESSION_EXPIRED", message: "Sesión vencida" }), {
  status: 401, headers: { "content-type": "application/json" },
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  sessionStorage.clear();
  pendingRequest = null;
});

describe("vencimiento de sesión en respuestas API", () => {
  it("cierra la sesión activa por 401 y conserva la clave de reintento de pago", async () => {
    sessionStorage.setItem("rentas.token", "token-viejo");
    sessionStorage.setItem("rentas.electronicPaymentIntent", JSON.stringify({ idempotencyKey: "key-fixture" }));
    vi.spyOn(authService, "me").mockResolvedValue({ role: "SUPERVISOR" });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(expiredResponse()));
    render(<AuthProvider><SessionActions /></AuthProvider>);
    await screen.findByText("SUPERVISOR");

    fireEvent.click(screen.getByRole("button", { name: "Pedir con sesión actual" }));
    await screen.findByText("Tu sesión venció. Ingresá nuevamente para continuar.");
    await act(async () => { await pendingRequest; });
    expect(sessionStorage.getItem("rentas.token")).toBeNull();
    expect(sessionStorage.getItem("rentas.electronicPaymentIntent")).toContain("key-fixture");
  });

  it("ignora el 401 de una petición antigua si ya se abrió una sesión nueva", async () => {
    sessionStorage.setItem("rentas.token", "token-viejo");
    vi.spyOn(authService, "me").mockResolvedValue({ role: "SUPERVISOR" });
    let resolveResponse;
    vi.stubGlobal("fetch", vi.fn(() => new Promise((resolve) => { resolveResponse = resolve; })));
    render(<AuthProvider><SessionActions /></AuthProvider>);
    await screen.findByText("SUPERVISOR");

    fireEvent.click(screen.getByRole("button", { name: "Pedir con sesión actual" }));
    await waitFor(() => expect(resolveResponse).toBeTypeOf("function"));
    vi.spyOn(authService, "login").mockResolvedValue({ token: "token-nuevo", user: { role: "TAXPAYER" } });
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Abrir sesión nueva" })); });
    await screen.findByText("TAXPAYER");
    await act(async () => { resolveResponse(expiredResponse()); await pendingRequest; });

    expect(sessionStorage.getItem("rentas.token")).toBe("token-nuevo");
    expect(screen.queryByText("Tu sesión venció. Ingresá nuevamente para continuar.")).toBeNull();
    expect(screen.getByText("TAXPAYER")).toBeTruthy();
  });

  it("no trata el 401 del endpoint de login como vencimiento de una sesión activa", async () => {
    sessionStorage.setItem("rentas.token", "token-vigente");
    vi.spyOn(authService, "me").mockResolvedValue({ role: "SUPERVISOR" });
    const fetchMock = vi.fn().mockResolvedValue(expiredResponse());
    vi.stubGlobal("fetch", fetchMock);
    render(<AuthProvider><SessionActions /></AuthProvider>);
    await screen.findByText("SUPERVISOR");

    fireEvent.click(screen.getByRole("button", { name: "Probar ingreso" }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    expect(fetchMock.mock.calls[0][1].headers["X-Demo-Session"]).toBeUndefined();
    expect(sessionStorage.getItem("rentas.token")).toBe("token-vigente");
    expect(screen.queryByText("Tu sesión venció. Ingresá nuevamente para continuar.")).toBeNull();
  });
});
