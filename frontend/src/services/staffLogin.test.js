import { beforeEach, describe, expect, it, vi } from "vitest";

const json = (status, body) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

const USER = { id: 1, username: "ana.rentas", displayName: "Ana Rentas", role: "SUPERVISOR", authorities: ["RENTAS", "SUPERVISOR"], authSource: "STAFF" };

describe("cuenta propia del personal en PROD (VITE_AUTH_MODE=m1)", () => {
  beforeEach(() => {
    vi.resetModules(); vi.unstubAllEnvs(); vi.unstubAllGlobals(); sessionStorage.clear();
    vi.stubEnv("VITE_AUTH_MODE", "m1");
  });

  it("ofrece las dos cuentas e ingresa al personal por /auth/staff/login", async () => {
    const fetchMock = vi.fn(async () => json(200, { token: "t-staff", user: USER }));
    vi.stubGlobal("fetch", fetchMock);
    const { STAFF_LOGIN_ENABLED, M1_LOGIN_ENABLED } = await import("./apiClient.js");
    const { authService } = await import("./rentasService.js");

    const { token, user } = await authService.login({ username: "ana.rentas", password: "x" });

    expect(STAFF_LOGIN_ENABLED && M1_LOGIN_ENABLED).toBe(true);
    expect(token).toBe("t-staff");
    expect(user).toMatchObject({ fullName: "Ana Rentas", role: "SUPERVISOR", backendRole: "SUPERVISOR" });
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toMatch(/\/api\/v1\/auth\/staff\/login$/);
    expect(new Headers(init.headers).get("Authorization")).toBeNull();
  });

  it("restaura y cierra la sesión del personal con Bearer", async () => {
    sessionStorage.setItem("rentas.token", "t-staff");
    sessionStorage.setItem("rentas.authSource", "staff");
    const fetchMock = vi.fn(async (url) => (String(url).endsWith("/me") ? json(200, USER) : new Response(null, { status: 204 })));
    vi.stubGlobal("fetch", fetchMock);
    const { authService } = await import("./rentasService.js");

    expect(await authService.me()).toMatchObject({ fullName: "Ana Rentas", role: "SUPERVISOR" });
    await authService.logout("t-staff", "staff");

    const [meUrl, meInit] = fetchMock.mock.calls[0];
    expect(meUrl).toMatch(/\/api\/v1\/auth\/staff\/me$/);
    expect(new Headers(meInit.headers).get("Authorization")).toBe("Bearer t-staff");
    const [logoutUrl, logoutInit] = fetchMock.mock.calls[1];
    expect(logoutUrl).toMatch(/\/api\/v1\/auth\/staff\/logout$/);
    expect(new Headers(logoutInit.headers).get("Authorization")).toBe("Bearer t-staff");
  });
});
