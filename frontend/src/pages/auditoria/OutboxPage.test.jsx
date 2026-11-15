import { act, cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../../services/apiClient.js";
import { integrationOutboxService } from "../../services/integrationOutboxService.js";
import OutboxPage from "./OutboxPage.jsx";

let authState;
vi.mock("../../context/AuthContext.jsx", () => ({ useAuth: () => authState }));

const event = {
  id: "evento-saliente-1",
  eventType: "debtSettled",
  targetModule: "CORE",
  aggregateId: "deuda-1",
  status: "FAILED",
  retryCount: 2,
  createdAt: "2026-11-15T10:00:00Z",
  lastAttemptAt: "2026-11-15T10:05:00Z",
};
const attempt = { etag: '"outbox-v2"', idempotencyKey: "retry-1" };
const queued = { ...event, status: "PENDING", retryCount: 0 };

afterEach(cleanup);

async function openDetail(role = "SUPERVISOR", row = event) {
  authState = { user: { role } };
  const list = vi.spyOn(integrationOutboxService, "list").mockResolvedValue([row]);
  const prepareRetry = vi.spyOn(integrationOutboxService, "prepareRetry").mockResolvedValue(attempt);
  const retry = vi.spyOn(integrationOutboxService, "retry").mockResolvedValue(queued);
  const user = userEvent.setup();
  render(<MemoryRouter><OutboxPage /></MemoryRouter>);

  await user.click(await screen.findByRole("button", { name: "Ver detalle" }));
  const detail = within(screen.getByRole("dialog", { name: "Detalle del evento saliente" }));
  return { user, detail, list, prepareRetry, retry };
}

describe("reintentos en la bandeja de eventos salientes", () => {
  it.each([
    ["SUPERVISOR", "FAILED", "CORE", true],
    ["SUPERVISOR", "DEAD_LETTER", "CORE", true],
    ["AUDITOR", "FAILED", "CORE", false],
    ["PERSONAL", "FAILED", "CORE", false],
    ["CAJERO", "FAILED", "CORE", false],
    ["CONTRIBUYENTE", "FAILED", "CORE", false],
    ["SUPERVISOR", "PENDING", "CORE", false],
    ["SUPERVISOR", "LOCAL_ONLY", "CORE", false],
    ["SUPERVISOR", "BROKER_CONFIRMED", "CORE", false],
    ["SUPERVISOR", "CORE_ACCEPTED", "CORE", false],
    ["SUPERVISOR", "PUBLISHED", "CORE", false],
    ["SUPERVISOR", "FAILED", "PLATFORM", false],
  ])("para %s con estado %s y destino %s, permite reintentar = %s", async (role, status, targetModule, visible) => {
    const { detail } = await openDetail(role, { ...event, status, targetModule });

    expect(Boolean(detail.queryByRole("button", { name: "Reintentar envío" }))).toBe(visible);
  });

  it("muestra la confirmación, cierra el detalle y recarga el estado desde la bandeja", async () => {
    const { user, detail, list, prepareRetry, retry } = await openDetail();
    list.mockResolvedValueOnce([queued]);

    await user.click(detail.getByRole("button", { name: "Reintentar envío" }));

    expect(await screen.findByText("Reintento encolado")).toBeTruthy();
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(await screen.findByRole("row", { name: /debtSettled.*Pendiente/i })).toBeTruthy();
    expect(prepareRetry).toHaveBeenCalledExactlyOnceWith(event);
    expect(retry).toHaveBeenCalledExactlyOnceWith(event.id, attempt);
    expect(list).toHaveBeenCalledTimes(2);

    await user.click(screen.getByRole("button", { name: "Cerrar" }));
    expect(screen.queryByText("Reintento encolado")).toBeNull();
  });

  it.each([404, 409, 412])("muestra el error %s, recarga y prepara un intento nuevo", async (status) => {
    const { user, detail, list, prepareRetry, retry } = await openDetail();
    const message = "El evento cambió durante la operación.";
    const newAttempt = { etag: '"outbox-v3"', idempotencyKey: "retry-2" };
    retry.mockRejectedValueOnce(new ApiError(message, status));
    prepareRetry.mockResolvedValueOnce(attempt).mockResolvedValueOnce(newAttempt);

    const retryButton = detail.getByRole("button", { name: "Reintentar envío" });
    await user.click(retryButton);

    expect(await detail.findByText(message)).toBeTruthy();
    expect(list).toHaveBeenCalledTimes(2);
    await waitFor(() => expect(retryButton.disabled).toBe(false));

    await user.click(retryButton);

    expect(await screen.findByText("Reintento encolado")).toBeTruthy();
    expect(prepareRetry).toHaveBeenCalledTimes(2);
    expect(retry).toHaveBeenNthCalledWith(1, event.id, attempt);
    expect(retry).toHaveBeenNthCalledWith(2, event.id, newAttempt);
  });

  it("reutiliza la misma clave y ETag si se pierde la respuesta del POST", async () => {
    const { user, detail, list, prepareRetry, retry } = await openDetail();
    retry.mockRejectedValueOnce(new TypeError("Se perdió la conexión."));

    const retryButton = detail.getByRole("button", { name: "Reintentar envío" });
    await user.click(retryButton);

    expect(await detail.findByText("Se perdió la conexión.")).toBeTruthy();
    expect(list).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(retryButton.disabled).toBe(false));
    await user.click(retryButton);

    expect(await screen.findByText("Reintento encolado")).toBeTruthy();
    expect(prepareRetry).toHaveBeenCalledTimes(1);
    expect(retry).toHaveBeenCalledTimes(2);
    expect(retry).toHaveBeenNthCalledWith(1, event.id, attempt);
    expect(retry).toHaveBeenNthCalledWith(2, event.id, attempt);
  });

  it("procesa un solo envío ante doble clic y mantiene abierto el detalle mientras espera", async () => {
    const { user, detail, retry } = await openDetail();
    const pending = Promise.withResolvers();
    retry.mockReturnValueOnce(pending.promise);

    const retryButton = detail.getByRole("button", { name: "Reintentar envío" });
    await user.dblClick(retryButton);

    expect(retry).toHaveBeenCalledExactlyOnceWith(event.id, attempt);
    const [closeIcon, closeButton] = detail.getAllByRole("button", { name: "Cerrar" });
    expect(retryButton.disabled).toBe(true);
    expect(closeButton.disabled).toBe(true);
    await user.click(closeIcon);
    await user.keyboard("{Escape}");
    expect(screen.getByRole("dialog")).toBeTruthy();

    await act(async () => { pending.resolve(queued); });
    expect(await screen.findByText("Reintento encolado")).toBeTruthy();
  });

  it("conserva el error fuera del modal cuando la recarga deja de incluir el evento", async () => {
    const { user, detail, list, retry } = await openDetail();
    list.mockResolvedValueOnce([]);
    retry.mockRejectedValueOnce(new ApiError("El evento ya no existe.", 404));

    await user.click(detail.getByRole("button", { name: "Reintentar envío" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    const alert = screen.getByRole("alert");
    expect(within(alert).getByText("El evento ya no existe.")).toBeTruthy();

    await user.click(within(alert).getByRole("button", { name: "Cerrar" }));
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("limpia el error cuando el usuario cierra manualmente el modal", async () => {
    const { user, detail, retry } = await openDetail();
    retry.mockRejectedValueOnce(new TypeError("Se perdió la conexión."));

    await user.click(detail.getByRole("button", { name: "Reintentar envío" }));
    expect(await detail.findByText("Se perdió la conexión.")).toBeTruthy();
    const closeButton = detail.getAllByRole("button", { name: "Cerrar" })[1];
    await waitFor(() => expect(closeButton.disabled).toBe(false));
    await user.click(closeButton);

    expect(screen.queryByRole("dialog")).toBeNull();
    expect(screen.queryByText("Se perdió la conexión.")).toBeNull();
  });
});
