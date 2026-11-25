/**
 * Cliente HTTP del frontend de Rentas.
 *
 * La URL del backend llega siempre por variable de entorno (`VITE_API_BASE_URL`),
 * nunca hardcodeada. Toda la información proviene del backend: no hay dataset local.
 */
import { adaptApiRequest, adaptApiResponse } from "./apiAdapters.js";
import { notifySessionExpired } from "./sessionEvents.js";

/** Quita las barras finales sin regex: `/\/+$/` tiene backtracking super-lineal. */
function withoutTrailingSlashes(value) {
  let end = value.length;
  while (end > 0 && value[end - 1] === "/") end -= 1;
  return value.slice(0, end);
}

const API_BASE_URL = withoutTrailingSlashes((import.meta.env.VITE_API_BASE_URL ?? "").trim());

// DEMO autentica con usuarios y sesiones reales del backend. "mock" es un alias heredado.
// "m1" (PROD) ofrece el ingreso de vecinos con Ciudadanos (ADR 0012) y el del personal con su
// cuenta propia de Rentas (ADR 0013); "demo" usa la cuenta DEMO para el personal.
const configuredAuthMode = (import.meta.env.VITE_AUTH_MODE ?? "demo").trim().toLowerCase();
export const AUTH_MODE = configuredAuthMode === "mock" ? "demo" : configuredAuthMode;
export const STAFF_LOGIN_ENABLED = AUTH_MODE === "demo" || AUTH_MODE === "m1";
export const M1_LOGIN_ENABLED = AUTH_MODE === "demo" || AUTH_MODE === "m1";

/** Origen de la sesión guardada: "m1" (vecino) y "staff" (personal) viajan como Bearer; cualquier otro valor es DEMO. */
const BEARER_SOURCES = new Set(["m1", "staff"]);
export const SESSION_SOURCE_KEY = "rentas.authSource";

export class ApiError extends Error {
  constructor(message, status, details, code, traceId) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.details = details;
    this.code = code ?? details?.code ?? null;
    this.traceId = traceId ?? details?.traceId ?? null;
  }
}

export function authHeaders() {
  const token = sessionStorage.getItem("rentas.token");
  if (!token) return {};
  if (BEARER_SOURCES.has(sessionStorage.getItem(SESSION_SOURCE_KEY))) return { Authorization: `Bearer ${token}` };
  return AUTH_MODE === "demo" ? { "X-Demo-Session": token } : {};
}

function sentSessionToken(headers) {
  const sent = new Headers(headers);
  const authorization = sent.get("Authorization") ?? "";
  return sent.get("X-Demo-Session") ?? (authorization.startsWith("Bearer ") ? authorization.slice(7) : null);
}

export async function request(path, { method = "GET", body, signal, responseType, headers, requiresAuth = true, includeHeaders = false } = {}) {
  const adapted = adaptApiRequest(path, { method, body, signal });
  const requestHeaders = {
    ...(responseType === "blob" ? { Accept: "application/pdf" } : {}),
    ...(adapted.options.body === undefined ? {} : { "Content-Type": "application/json" }),
    ...(requiresAuth ? authHeaders() : {}),
    ...headers,
  };
  const sessionToken = sentSessionToken(requestHeaders);
  const response = await fetch(`${API_BASE_URL}${adapted.path}`, {
    method: adapted.options.method,
    signal: adapted.options.signal,
    headers: requestHeaders,
    body: adapted.options.body === undefined ? undefined : JSON.stringify(adapted.options.body),
  });

  if (response.status === 401 && requiresAuth && sessionToken) notifySessionExpired(sessionToken);

  const contentType = response.headers.get("content-type") ?? "";
  if (response.ok && responseType === "blob") {
    if (contentType.split(";")[0].trim().toLowerCase() !== "application/pdf") {
      throw new ApiError("El servidor no devolvió un PDF válido.", response.status);
    }
    const blob = await response.blob();
    const signature = new Uint8Array(await blob.slice(0, 5).arrayBuffer());
    if (String.fromCharCode(...signature) !== "%PDF-") {
      throw new ApiError("El documento PDF está vacío o dañado.", response.status);
    }
    const disposition = response.headers.get("content-disposition") ?? "";
    const filename = disposition.match(/filename="([^"\r\n]+)"/i)?.[1] ?? "boleta.pdf";
    return { blob, filename: filename.replace(/[\\/]/g, "_") };
  }
  const isJson = /\bapplication\/(?:[\w.-]+\+)?json\b/i.test(contentType);
  let payload = null;
  if (response.status !== 204) {
    if (response.ok && !isJson) {
      throw new ApiError(
        "La API devolvió una respuesta inesperada. Revisá la URL del backend y el proxy de conexión.",
        response.status, null, "INVALID_API_RESPONSE", response.headers.get("x-correlation-id"),
      );
    }
    try {
      payload = isJson ? await response.json() : await response.text();
    } catch {
      if (response.ok) {
        throw new ApiError("La API devolvió un JSON inválido.", response.status, null, "INVALID_API_RESPONSE",
          response.headers.get("x-correlation-id"));
      }
    }
  }

  if (!response.ok) {
    throw new ApiError(
      payload?.message ?? payload?.detail ?? "No se pudo completar la operación.",
      response.status,
      payload,
      payload?.code,
      payload?.traceId ?? response.headers.get("x-correlation-id"),
    );
  }

  const data = adaptApiResponse(path, adapted.path, payload);
  return includeHeaders ? { data, headers: response.headers } : data;
}
