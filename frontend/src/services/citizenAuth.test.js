import { afterEach, expect, it, vi } from "vitest";

afterEach(() => {
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
  vi.resetModules();
  sessionStorage.clear();
});

it("uses the citizen cookie endpoints and sends CSRF only on mutations", async () => {
  vi.stubEnv("VITE_AUTH_MODE", "citizen");
  vi.stubEnv("VITE_API_BASE_URL", "");
  sessionStorage.setItem("rentas.token", "unrelated-demo-token");
  const profile = { displayName: "Contribuyente", role: "TAXPAYER", taxpayerId: 42 };
  const fetch = vi.fn(async (path) => path.endsWith("/logout")
    ? new Response(null, { status: 204 })
    : new Response(JSON.stringify(profile), { headers: { "content-type": "application/json" } }));
  vi.stubGlobal("fetch", fetch);
  const { authService } = await import("./rentasService.js");

  const session = await authService.login({ username: "qa@example.invalid", password: "synthetic" });
  expect(session.token).toBeUndefined();
  expect(session.user).toMatchObject({ role: "CONTRIBUYENTE", taxpayerId: 42 });
  expect(await authService.me()).toMatchObject({ role: "CONTRIBUYENTE", taxpayerId: 42 });
  await authService.logout();

  expect(fetch.mock.calls.map(([path]) => path)).toEqual([
    "/api/v1/citizen-auth/login", "/api/v1/citizen-auth/me", "/api/v1/citizen-auth/logout",
  ]);
  for (const [path, options] of fetch.mock.calls) {
    const headers = new Headers(options.headers);
    expect(options.credentials).toBe("same-origin");
    expect(headers.get("X-Demo-Session")).toBeNull();
    expect(headers.get("X-Rentas-CSRF")).toBe(path.endsWith("/me") ? null : "1");
  }
});

it("does not send citizen credentials to a cross-origin API", async () => {
  vi.stubEnv("VITE_AUTH_MODE", "citizen");
  vi.stubEnv("VITE_API_BASE_URL", "https://other.example.invalid");
  const fetch = vi.fn();
  vi.stubGlobal("fetch", fetch);
  const { authService } = await import("./rentasService.js");

  await expect(authService.login({ username: "qa@example.invalid", password: "synthetic" }))
    .rejects.toMatchObject({ code: "CITIZEN_ORIGIN_REQUIRED" });
  expect(fetch).not.toHaveBeenCalled();
});
