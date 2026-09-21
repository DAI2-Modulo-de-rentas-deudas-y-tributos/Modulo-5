import { afterEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import { AuthProvider, useAuth } from "../context/AuthContext.jsx";
import { authService } from "../services/rentasService.js";

let auth;
function Estado() {
  auth = useAuth();
  return <span>{auth.initializing ? "cargando" : auth.user?.role ?? "sin sesión"}</span>;
}
afterEach(() => { cleanup(); vi.restoreAllMocks(); sessionStorage.clear(); });

describe("ciclo de sesión verificado por backend", () => {
  it("ignora un perfil adulterado del storage y restaura únicamente /me", async () => {
    sessionStorage.setItem("rentas.token", "token-fixture");
    sessionStorage.setItem("rentas.user", JSON.stringify({ role: "SUPERVISOR" }));
    const me = vi.spyOn(authService, "me").mockResolvedValue({ username: "qa", role: "CONTRIBUYENTE", taxpayerId: 9 });
    render(<AuthProvider><Estado /></AuthProvider>);
    expect(await screen.findByText("CONTRIBUYENTE")).toBeDefined();
    expect(me).toHaveBeenCalledOnce();
    expect(JSON.parse(sessionStorage.getItem("rentas.user")).role).toBe("CONTRIBUYENTE");
  });

  it.each([401, 403])("limpia token y perfil si /me responde %s", async (status) => {
    sessionStorage.setItem("rentas.token", "token-fixture");
    sessionStorage.setItem("rentas.user", JSON.stringify({ role: "SUPERVISOR" }));
    vi.spyOn(authService, "me").mockRejectedValue({ status });
    render(<AuthProvider><Estado /></AuthProvider>);
    await screen.findByText("sin sesión");
    expect(sessionStorage.getItem("rentas.token")).toBeNull();
    expect(sessionStorage.getItem("rentas.user")).toBeNull();
  });

  it("un /me tardío no restaura una sesión después del logout", async () => {
    sessionStorage.setItem("rentas.token", "token-fixture");
    let resolver;
    vi.spyOn(authService, "me").mockImplementation(() => new Promise((resolve) => { resolver = resolve; }));
    const logout = vi.spyOn(authService, "logout").mockResolvedValue();
    render(<AuthProvider><Estado /></AuthProvider>);
    await act(async () => { await auth.logout(); });
    await act(async () => { resolver({ role: "SUPERVISOR" }); });
    expect(logout).toHaveBeenCalledOnce();
    expect(screen.getByText("sin sesión")).toBeDefined();
    expect(sessionStorage.getItem("rentas.user")).toBeNull();
  });

  it("revoca el token recién emitido cuando el usuario eligió otra puerta", async () => {
    vi.spyOn(authService, "login").mockResolvedValue({ token: "token-rechazado", user: { role: "CONTRIBUYENTE" } });
    const logout = vi.spyOn(authService, "logout").mockResolvedValue();
    render(<AuthProvider><Estado /></AuthProvider>);
    await act(async () => { expect(await auth.login({}, { accept: () => false })).toMatchObject({ accepted: false }); });
    expect(logout).toHaveBeenCalledWith("token-rechazado");
    expect(sessionStorage.getItem("rentas.token")).toBeNull();
    await waitFor(() => expect(auth.isAuthenticated).toBe(false));
  });
});
