import { beforeEach } from "vitest";
import { DEMO_AUTH_FIXTURES } from "./demoAuthFixtures.js";

const sessions = new Map();

function isDevAuth(url, method, suffix) {
  return String(url).includes(`/api/v1/dev-auth/${suffix}`) && method === (suffix === "me" ? "GET" : "POST");
}

function header(init, name) {
  const headers = init.headers ?? {};
  if (typeof headers.get === "function") return headers.get(name);
  const match = Object.keys(headers).find((key) => key.toLowerCase() === name.toLowerCase());
  return match ? headers[match] : null;
}

function json(status, body) {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

function demoAuthResponse(init) {
  const body = JSON.parse(init.body ?? "{}");
  const username = String(body.username ?? "").trim().toLowerCase();
  const match = DEMO_AUTH_FIXTURES.find(
    (entry) => entry.user.username === username && entry.password === body.password,
  );
  if (!match) return json(401, { message: "Usuario o contraseña incorrectos.", code: "INVALID_CREDENTIALS" });
  const token = `demo.${crypto.randomUUID()}`;
  sessions.set(token, match.user);
  return json(200, { token, user: match.user });
}

function wrapFetch(inner) {
  const wrapped = async (input, init = {}) => {
    const url = typeof input === "string" ? input : (input?.url ?? "");
    const method = String(init.method ?? input?.method ?? "GET").toUpperCase();
    if (isDevAuth(url, method, "login")) return demoAuthResponse(init);
    if (isDevAuth(url, method, "me")) {
      const user = sessions.get(header(init, "X-Demo-Session"));
      if (!user) return json(401, { message: "Sesión DEMO inválida", code: "UNAUTHENTICATED" });
      return json(200, user);
    }
    if (isDevAuth(url, method, "logout")) {
      const token = header(init, "X-Demo-Session");
      if (!sessions.has(token)) return json(401, { message: "Sesión DEMO inválida", code: "UNAUTHENTICATED" });
      sessions.delete(token);
      return new Response(null, { status: 204 });
    }
    if (typeof inner === "function" && !inner.__demoAuth) return inner(input, init);
    throw new TypeError("Failed to fetch");
  };
  wrapped.__demoAuth = true;
  return wrapped;
}

beforeEach(() => {
  sessions.clear();
  sessionStorage.clear();
  if (globalThis.fetch?.__demoAuth) return;
  globalThis.fetch = wrapFetch(typeof globalThis.fetch === "function" ? globalThis.fetch.bind(globalThis) : undefined);
});
