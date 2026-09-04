/**
 * Cliente HTTP del frontend de Rentas.
 *
 * La URL del backend llega siempre por variable de entorno (`VITE_API_BASE_URL`),
 * nunca hardcodeada. Mientras el backend no exista, `VITE_USE_MOCKS` mantiene la
 * app navegable contra el dataset local de `mockDb.js`.
 */
import { adaptApiRequest, adaptApiResponse } from "./apiAdapters.js";

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "";

export const USE_MOCKS = import.meta.env.VITE_USE_MOCKS !== "false";
export const AUTH_MODE = import.meta.env.VITE_AUTH_MODE ?? "mock";
export const DEV_IDENTITY_HEADERS = import.meta.env.VITE_DEV_IDENTITY_HEADERS === "true";

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

const ROLE_MAP = { PERSONAL: ["RENTAS"], SUPERVISOR: ["RENTAS", "SUPERVISOR"], CAJERO: ["CASHIER"], AUDITOR: ["AUDITOR"], CONTRIBUYENTE: ["TAXPAYER"] };

export function authHeaders() {
  if (AUTH_MODE === "mock" && DEV_IDENTITY_HEADERS) {
    const user = JSON.parse(sessionStorage.getItem("rentas.user") ?? "null");
    if (!user) return {};
    return {
      "X-Dev-User": user.username ?? String(user.id ?? "mock-user"),
      "X-Dev-Roles": (ROLE_MAP[user.role] ?? []).join(","),
      ...(user.taxpayerId ? { "X-Dev-Taxpayer-Id": String(user.taxpayerId) } : {}),
    };
  }
  // Core/JWT todavía no tiene contrato: no reutilizar tokens de la sesión mock.
  return {};
}

export async function request(path, { method = "GET", body, signal, responseType } = {}) {
  const adapted = adaptApiRequest(path, { method, body, signal });
  const response = await fetch(`${API_BASE_URL}${adapted.path}`, {
    method: adapted.options.method,
    signal: adapted.options.signal,
    headers: {
      ...(responseType === "blob" ? { Accept: "application/pdf" } : {}),
      ...(adapted.options.body === undefined ? {} : { "Content-Type": "application/json" }),
      ...authHeaders(),
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
  const payload = response.status === 204 ? null : contentType.includes("application/json")
    ? await response.json().catch(() => null)
    : await response.text().catch(() => null);

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

/** Simula la latencia de red para que los estados de carga se vean en modo mock. */
export function delay(ms = 350) {
  // En los tests la latencia simulada sólo agrega segundos al CI.
  if (import.meta.env.MODE === "test") return Promise.resolve();
  return new Promise((resolve) => setTimeout(resolve, ms));
}
