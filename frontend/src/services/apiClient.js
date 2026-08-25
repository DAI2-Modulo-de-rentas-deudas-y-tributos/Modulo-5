/**
 * Cliente HTTP del frontend de Rentas.
 *
 * La URL del backend llega siempre por variable de entorno (`VITE_API_BASE_URL`),
 * nunca hardcodeada. Mientras el backend no exista, `VITE_USE_MOCKS` mantiene la
 * app navegable contra el dataset local de `mockDb.js`.
 */
const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "";

export const USE_MOCKS =
  import.meta.env.VITE_USE_MOCKS === "true" || API_BASE_URL === "";

export class ApiError extends Error {
  constructor(message, status, details) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.details = details;
  }
}

function authHeaders() {
  const token = sessionStorage.getItem("rentas.token");
  return token ? { Authorization: `Bearer ${token}` } : {};
}

export async function request(path, { method = "GET", body, signal } = {}) {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    method,
    signal,
    headers: {
      "Content-Type": "application/json",
      ...authHeaders(),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });

  const payload = response.status === 204 ? null : await response.json().catch(() => null);

  if (!response.ok) {
    throw new ApiError(
      payload?.message ?? "No se pudo completar la operación.",
      response.status,
      payload,
    );
  }

  return payload;
}

/** Simula la latencia de red para que los estados de carga se vean en modo mock. */
export function delay(ms = 350) {
  // En los tests la latencia simulada sólo agrega segundos al CI.
  if (import.meta.env.MODE === "test") return Promise.resolve();
  return new Promise((resolve) => setTimeout(resolve, ms));
}
