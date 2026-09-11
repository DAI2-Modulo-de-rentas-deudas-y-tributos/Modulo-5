/**
 * Cliente HTTP del frontend de Rentas.
 *
 * La URL del backend llega siempre por variable de entorno (`VITE_API_BASE_URL`),
 * nunca hardcodeada. Toda la información proviene del backend: no hay dataset local.
 */
import { adaptApiRequest, adaptApiResponse } from "./apiAdapters.js";

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? "").trim().replace(/\/+$/, "");

export const AUTH_MODE = import.meta.env.VITE_AUTH_MODE ?? "mock";

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
  if (AUTH_MODE !== "mock") return {};
  const token = sessionStorage.getItem("rentas.token");
  if (!token) return {};
  return { "X-Demo-Session": token };
}

export async function request(path, { method = "GET", body, signal, responseType, headers } = {}) {
  const adapted = adaptApiRequest(path, { method, body, signal });
  const response = await fetch(`${API_BASE_URL}${adapted.path}`, {
    method: adapted.options.method,
    signal: adapted.options.signal,
    headers: {
      ...(responseType === "blob" ? { Accept: "application/pdf" } : {}),
      ...(adapted.options.body === undefined ? {} : { "Content-Type": "application/json" }),
      ...authHeaders(),
      ...headers,
    },
    body: adapted.options.body === undefined ? undefined : JSON.stringify(adapted.options.body),
  });

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
      payload?.message ?? "No se pudo completar la operación.",
      response.status,
      payload,
      payload?.code,
      payload?.traceId ?? response.headers.get("x-correlation-id"),
    );
  }

  return adaptApiResponse(path, adapted.path, payload);
}
