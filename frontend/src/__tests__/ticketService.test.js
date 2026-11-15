import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("../services/apiClient.js", () => ({
  AUTH_MODE: "demo",
  request: vi.fn(),
  ApiError: class ApiError extends Error {},
}));

import { request } from "../services/apiClient.js";
import { ticketService } from "../services/rentasService.js";

describe("gestión local de tickets", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    request.mockResolvedValue({ id: 7, status: "IN_PROGRESS" });
  });

  it("inicia la gestión con la operación real del backend", async () => {
    await ticketService.updateStatus({ ticketId: 7, status: "IN_PROGRESS" });
    expect(request).toHaveBeenCalledWith("/api/v1/tickets/7/assign", { method: "POST" });
  });

  it.each([
    ["WAITING_FOR_INFORMATION", "request-information", { message: "Adjuntá el comprobante" }],
    ["COMPLETED", "complete", { resolution: "Pago imputado" }],
    ["REJECTED", "reject", { reason: "No corresponde a Rentas" }],
  ])("envía %s a su endpoint", async (status, action, body) => {
    await ticketService.updateStatus({ ticketId: 7, status, message: Object.values(body)[0] });
    expect(request).toHaveBeenCalledWith(`/api/v1/tickets/7/${action}`, { method: "POST", body });
  });

  it("resuelve directamente sin emitir un STARTED previo innecesario", async () => {
    await ticketService.updateStatus({ ticketId: 7, status: "COMPLETED", message: "Resuelto" });
    expect(request.mock.calls.map(([url]) => url)).toEqual([
      "/api/v1/tickets/7/complete",
    ]);
  });

  it("envia el avance por la operacion existente de actualizaciones", async () => {
    await ticketService.updateStatus({ ticketId: 7, status: "PROGRESS", message: "Se reviso el expediente" });
    expect(request).toHaveBeenCalledWith("/api/v1/tickets/7/updates", {
      method: "POST", body: { message: "Se reviso el expediente" },
    });
  });

  it("envia una devolucion con explicacion privada", async () => {
    await ticketService.updateStatus({ ticketId: 7, status: "RETURNED", reasonCode: "REQUEST_TYPE_MISMATCH", message: "Corresponde a otra area" });
    expect(request).toHaveBeenCalledWith("/api/v1/tickets/7/return", {
      method: "POST", body: { reasonCode: "REQUEST_TYPE_MISMATCH", internalMessage: "Corresponde a otra area" },
    });
  });
});
