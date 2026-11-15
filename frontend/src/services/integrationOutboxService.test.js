import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "./apiClient.js";
import { integrationOutboxService } from "./integrationOutboxService.js";

const event = {
  id: "evento/1 ?#",
  status: "FAILED",
  retryCount: 2,
  lastAttemptAt: "2026-11-15T10:00:00Z",
  targetModule: "CORE",
};
const detailPath = "/api/v1/integrations/outbox/evento%2F1%20%3F%23";
const etag = '"outbox-v2"';

afterEach(() => vi.unstubAllGlobals());

describe("reintentos del servicio de eventos salientes", () => {
  it("consulta el id codificado y devuelve el ETag y la clave cuando el detalle coincide", async () => {
    const fetchDetail = vi.fn().mockResolvedValue(Response.json(event, { headers: { ETag: etag } }));
    vi.stubGlobal("fetch", fetchDetail);
    const uuid = vi.spyOn(crypto, "randomUUID");

    const attempt = await integrationOutboxService.prepareRetry(event);

    expect(attempt).toEqual({ etag, idempotencyKey: uuid.mock.results[0].value });
    expect(uuid).toHaveBeenCalledTimes(1);
    expect(fetchDetail).toHaveBeenCalledExactlyOnceWith(expect.stringContaining(detailPath), expect.objectContaining({
      method: "GET",
    }));
  });

  it.each([
    ["status", "PENDING"],
    ["retryCount", 3],
    ["lastAttemptAt", "2026-11-15T10:30:00Z"],
    ["targetModule", "PLATFORM"],
  ])("rechaza el detalle con ApiError 412 cuando cambia %s", async (field, updatedValue) => {
    const detail = { ...event, [field]: updatedValue };
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json(detail, { headers: { ETag: etag } })));

    const attempt = integrationOutboxService.prepareRetry(event);
    await expect(attempt).rejects.toBeInstanceOf(ApiError);
    await expect(attempt).rejects.toMatchObject({ status: 412 });
  });

  it("rechaza el detalle si el servidor no devuelve ETag", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json(event)));

    await expect(integrationOutboxService.prepareRetry(event))
      .rejects.toThrow("No se pudo verificar la versión del evento.");
  });

  it("envía POST al id codificado con If-Match e Idempotency-Key", async () => {
    const queued = { id: event.id, status: "PENDING", retryCount: 0 };
    const fetchRetry = vi.fn().mockResolvedValue(Response.json(queued, { status: 202 }));
    vi.stubGlobal("fetch", fetchRetry);

    await expect(integrationOutboxService.retry(event.id, { etag, idempotencyKey: "retry-1" }))
      .resolves.toEqual(queued);
    expect(fetchRetry).toHaveBeenCalledExactlyOnceWith(expect.stringContaining(`${detailPath}/retry`), expect.objectContaining({
      method: "POST",
      headers: expect.objectContaining({ "If-Match": etag, "Idempotency-Key": "retry-1" }),
    }));
  });
});
