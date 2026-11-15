import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, request } from "./apiClient.js";

afterEach(() => vi.unstubAllGlobals());

describe("Problem Details API errors", () => {
  it("keeps the legacy functional message and correlation extensions", async () => {
    const payload = {
      type: "about:blank", title: "Conflict", status: 409,
      detail: "La deuda ya está pagada", message: "La deuda ya está pagada",
      code: "DEBT_NOT_PAYABLE", traceId: "trace-409",
    };
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
      ok: false, status: 409,
      headers: { get: (name) => name.toLowerCase() === "content-type" ? "application/problem+json" : null },
      json: async () => payload,
    }));

    await expect(request("/api/v1/debts/1/payments")).rejects.toMatchObject({
      name: "ApiError", status: 409, message: "La deuda ya está pagada",
      code: "DEBT_NOT_PAYABLE", traceId: "trace-409", details: payload,
    });
  });

  it("uses RFC detail when a compatible problem has no legacy message", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
      ok: false, status: 422,
      headers: { get: (name) => name.toLowerCase() === "content-type" ? "application/problem+json" : null },
      json: async () => ({ type: "about:blank", title: "Unprocessable Entity", status: 422, detail: "Importe inválido" }),
    }));

    const result = request("/api/v1/payments");
    await expect(result).rejects.toBeInstanceOf(ApiError);
    await expect(result).rejects.toMatchObject({ message: "Importe inválido", status: 422 });
  });
});

describe("encabezados de una respuesta exitosa", () => {
  const path = "/api/v1/integrations/outbox/evento-1";
  const event = { id: "evento-1", status: "FAILED" };
  const etag = '"outbox-v2"';

  it("devuelve los datos y encabezados con includeHeaders", async () => {
    const response = Response.json(event, { headers: { ETag: etag } });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response));

    const result = await request(path, { includeHeaders: true });

    expect(result.data).toEqual(event);
    expect(result.headers.get("ETag")).toBe(etag);
  });

  it("devuelve solo los datos por defecto", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json(event, { headers: { ETag: etag } })));

    await expect(request(path)).resolves.toEqual(event);
  });
});
