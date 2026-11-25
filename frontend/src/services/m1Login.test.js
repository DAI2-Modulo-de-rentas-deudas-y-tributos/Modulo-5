import { beforeEach, describe, expect, it, vi } from "vitest";

const json = (status, body) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

const SESSION = { token: "t-m1", user: { displayName: "Ana Paz", role: "TAXPAYER", authorities: ["TAXPAYER"], taxpayerId: 7, authSource: "M1" } };

describe("sesión ciudadana con M1", () => {
  beforeEach(() => { vi.resetModules(); vi.unstubAllEnvs(); vi.unstubAllGlobals(); sessionStorage.clear(); });

  it("loginM1 envía sólo las credenciales y mapea al contribuyente", async () => {
    const fetchMock = vi.fn(async () => json(200, SESSION));
    vi.stubGlobal("fetch", fetchMock);
    const { authService } = await import("./rentasService.js");

    const { token, user } = await authService.loginM1({ email: "ana@example.invalid", password: "x" });

    expect(token).toBe("t-m1");
    expect(user).toMatchObject({ fullName: "Ana Paz", role: "CONTRIBUYENTE", roleLabel: "Contribuyente", backendRole: "TAXPAYER", taxpayerId: 7 });
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toMatch(/\/api\/v1\/auth\/m1\/login$/);
    expect(JSON.parse(init.body)).toEqual({ email: "ana@example.invalid", password: "x" });
    expect(new Headers(init.headers).get("Authorization")).toBeNull();
  });

  it("reintenta mientras Ciudadanos arranca y avisa cada intento", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json(503, { code: "M1_UNAVAILABLE", message: "no disponible" }))
      .mockResolvedValueOnce(json(504, { code: "M1_TIMEOUT", message: "lento" }))
      .mockResolvedValueOnce(json(200, SESSION));
    vi.stubGlobal("fetch", fetchMock);
    const { authService } = await import("./rentasService.js");
    const onRetry = vi.fn();

    await authService.loginM1({ email: "ana@example.invalid", password: "x" }, { onRetry, retryDelayMs: 0 });

    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(onRetry.mock.calls).toEqual([[2], [3]]);
  });

  it("corta tras el máximo de intentos y no reintenta credenciales inválidas", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => json(503, { code: "M1_UNAVAILABLE", message: "Ciudadanos no está disponible" })));
    const { authService, M1_LOGIN_MAX_ATTEMPTS } = await import("./rentasService.js");
    await expect(authService.loginM1({ email: "a@example.invalid", password: "x" }, { retryDelayMs: 0 }))
      .rejects.toMatchObject({ code: "M1_UNAVAILABLE" });
    expect(fetch).toHaveBeenCalledTimes(M1_LOGIN_MAX_ATTEMPTS);

    vi.stubGlobal("fetch", vi.fn(async () => json(401, { code: "INVALID_CREDENTIALS", message: "Correo o contraseña incorrectos" })));
    await expect(authService.loginM1({ email: "a@example.invalid", password: "x" }, { retryDelayMs: 0 }))
      .rejects.toMatchObject({ code: "INVALID_CREDENTIALS", status: 401 });
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it("la sesión M1 viaja como Bearer incluso en el modo m1 de PROD", async () => {
    vi.stubEnv("VITE_AUTH_MODE", "m1");
    sessionStorage.setItem("rentas.token", "t-m1");
    sessionStorage.setItem("rentas.authSource", "m1");
    const { authHeaders, M1_LOGIN_ENABLED, STAFF_LOGIN_ENABLED } = await import("./apiClient.js");
    expect(authHeaders()).toEqual({ Authorization: "Bearer t-m1" });
    expect(M1_LOGIN_ENABLED).toBe(true);
    expect(STAFF_LOGIN_ENABLED).toBe(false);
  });

  it("me y logout usan las rutas M1 y un 401 Bearer vence la sesión", async () => {
    sessionStorage.setItem("rentas.token", "t-m1");
    sessionStorage.setItem("rentas.authSource", "m1");
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json(200, SESSION.user))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(json(401, { code: "UNAUTHENTICATED", message: "Sesión inválida o vencida" }));
    vi.stubGlobal("fetch", fetchMock);
    const { authService, taxpayerService } = await import("./rentasService.js");
    const { onSessionExpired } = await import("./sessionEvents.js");
    const expired = vi.fn();
    onSessionExpired(expired);

    expect((await authService.me()).role).toBe("CONTRIBUYENTE");
    await authService.logout("t-m1", "m1");
    await expect(taxpayerService.getById(7)).rejects.toMatchObject({ status: 401 });

    expect(fetchMock.mock.calls[0][0]).toMatch(/\/api\/v1\/auth\/m1\/me$/);
    expect(new Headers(fetchMock.mock.calls[0][1].headers).get("Authorization")).toBe("Bearer t-m1");
    expect(fetchMock.mock.calls[1][0]).toMatch(/\/api\/v1\/auth\/m1\/logout$/);
    expect(new Headers(fetchMock.mock.calls[1][1].headers).get("Authorization")).toBe("Bearer t-m1");
    expect(expired).toHaveBeenCalledWith("t-m1");
  });
});
