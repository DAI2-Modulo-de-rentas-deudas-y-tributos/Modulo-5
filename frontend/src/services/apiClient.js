/**
 * Cliente HTTP del frontend de Rentas.
 *
 * La URL del backend llega siempre por variable de entorno (`VITE_API_BASE_URL`),
 * nunca hardcodeada. Toda la información proviene del backend: no hay dataset local.
 */
import { adaptApiRequest, adaptApiResponse } from "./apiAdapters.js";
import { notifySessionExpired } from "./sessionEvents.js";

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? "").trim().replace(/\/+$/, "");

export const AUTH_MODE = import.meta.env.VITE_AUTH_MODE ?? "mock";

const CODE_MESSAGES = {
  DEBT_NOT_PAYABLE: "La deuda no admite pagos",
  DEBT_NOT_ADJUSTABLE: "La deuda no admite ajustes",
  DEBT_NOT_CHARGEABLE: "La deuda no admite recargos",
  INVALID_CREDENTIALS: "Usuario o contraseña incorrectos.",
};

const STATUS_MESSAGES = {
  400: "Revisá los datos enviados e intentá nuevamente.",
  401: "Tu sesión venció. Ingresá nuevamente para continuar.",
  403: "No tenés permisos para realizar esta operación.",
  404: "No se encontró la información solicitada.",
  409: "La operación entra en conflicto con el estado actual. Actualizá la información antes de reintentar.",
  422: "Revisá los datos: la operación no cumple las condiciones requeridas.",
  429: "Se realizaron demasiadas solicitudes. Esperá un momento antes de reintentar.",
};

const nonEmptyText = (value) => typeof value === "string" && value.trim() ? value.trim() : null;

export class ApiError extends Error {
  constructor(message, status, details, code, traceId) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.details = details;
    this.code = nonEmptyText(code) ?? nonEmptyText(details?.code);
    this.traceId = nonEmptyText(traceId) ?? nonEmptyText(details?.traceId);
  }
}

function responseError(response, payload, requiresAuth) {
  const code = nonEmptyText(payload?.code);
  const codeMessage = Object.hasOwn(CODE_MESSAGES, code) ? CODE_MESSAGES[code] : null;
  const message = nonEmptyText(payload?.message) ?? nonEmptyText(payload?.detail) ?? codeMessage
    ?? (response.status === 401 && !requiresAuth ? "No se pudo autenticar la solicitud." : STATUS_MESSAGES[response.status])
    ?? (response.status >= 500 ? "El servidor no pudo completar la operación. Intentá nuevamente más tarde."
      : "No se pudo completar la operación.");

  return new ApiError(message, response.status, payload && typeof payload === "object" ? payload : null, code,
    nonEmptyText(payload?.traceId) ?? response.headers.get("x-correlation-id"));
}

export function authHeaders() {
  if (AUTH_MODE !== "mock") return {};
  const token = sessionStorage.getItem("rentas.token");
  if (!token) return {};
  return { "X-Demo-Session": token };
}

const pendingWrites = new Map();

/** Colapsa sólo escrituras idénticas en curso; una clave de idempotencia distinta conserva su intención. */
export function request(path, options = {}) {
  const method = (options.method ?? "GET").toUpperCase();
  if (!["POST", "PUT", "PATCH", "DELETE"].includes(method) || options.signal || path.startsWith("/api/v1/dev-auth/")) {
    return performRequest(path, options);
  }
  const key = JSON.stringify([method, path, options.body, authHeaders(), options.headers]);
  if (pendingWrites.has(key)) return pendingWrites.get(key);
  const operation = performRequest(path, options).finally(() => pendingWrites.delete(key));
  pendingWrites.set(key, operation);
  return operation;
}

async function performRequest(path, { method = "GET", body, signal, responseType, headers, requiresAuth = true } = {}) {
  const adapted = adaptApiRequest(path, { method, body, signal });
  const requestHeaders = {
    ...(responseType === "blob" ? { Accept: "application/pdf" } : {}),
    ...(adapted.options.body === undefined ? {} : { "Content-Type": "application/json" }),
    ...(requiresAuth ? authHeaders() : {}),
    ...headers,
  };
  const sessionToken = new Headers(requestHeaders).get("X-Demo-Session");
  const response = await fetch(`${API_BASE_URL}${adapted.path}`, {
    method: adapted.options.method,
    signal: adapted.options.signal,
    headers: requestHeaders,
    body: adapted.options.body === undefined ? undefined : JSON.stringify(adapted.options.body),
  });

  // Se procesa antes del cuerpo: un 401 también puede llegar sin JSON o al descargar un PDF.
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
    } catch (caught) {
      if (caught.name === "AbortError") throw caught;
      if (response.ok) {
        throw new ApiError("La API devolvió un JSON inválido.", response.status, null, "INVALID_API_RESPONSE",
          response.headers.get("x-correlation-id"));
      }
    }
  }

  if (!response.ok) {
    throw responseError(response, payload, requiresAuth);
  }

  return adaptApiResponse(path, adapted.path, payload);
}
