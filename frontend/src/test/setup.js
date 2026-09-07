import { beforeEach } from "vitest";
import { DEMO_AUTH_FIXTURES } from "./demoAuthFixtures.js";

function isDevAuthLogin(url, method) {
  return String(url).includes("/api/v1/dev-auth/login") && method === "POST";
}

function demoAuthResponse(init) {
  const body = JSON.parse(init.body ?? "{}");
  const username = String(body.username ?? "").trim().toLowerCase();
  const match = DEMO_AUTH_FIXTURES.find(
    (entry) => entry.user.username === username && entry.password === body.password,
  );
  if (!match) {
    return new Response(
      JSON.stringify({ message: "Usuario o contraseña incorrectos.", code: "INVALID_CREDENTIALS" }),
      { status: 401, headers: { "content-type": "application/json" } },
    );
  }
  return new Response(JSON.stringify({ token: "dev-session", user: match.user }), {
    status: 200,
    headers: { "content-type": "application/json" },
  });
}

function wrapFetch(inner) {
  const wrapped = async (input, init = {}) => {
    const url = typeof input === "string" ? input : (input?.url ?? "");
    const method = String(init.method ?? input?.method ?? "GET").toUpperCase();
    if (isDevAuthLogin(url, method)) return demoAuthResponse(init);
    if (typeof inner === "function") return inner(input, init);
    throw new TypeError("Failed to fetch");
  };
  wrapped.__demoAuth = true;
  return wrapped;
}

beforeEach(() => {
  if (globalThis.fetch?.__demoAuth) return;
  globalThis.fetch = wrapFetch(typeof globalThis.fetch === "function" ? globalThis.fetch.bind(globalThis) : undefined);
});
