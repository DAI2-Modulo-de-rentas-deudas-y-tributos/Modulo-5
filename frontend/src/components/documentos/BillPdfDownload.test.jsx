import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import BillPdfDownload from "./BillPdfDownload.jsx";

const api = vi.hoisted(() => ({ request: vi.fn(), mocks: false }));
vi.mock("../../services/apiClient.js", () => ({ request: api.request, get USE_MOCKS() { return api.mocks; } }));

beforeEach(() => {
  api.mocks = false;
  api.request.mockReset();
  vi.stubGlobal("URL", { createObjectURL: vi.fn(() => "blob:qa-pdf"), revokeObjectURL: vi.fn() });
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

it("inicia una sola descarga, conserva la pantalla y libera el archivo temporal", async () => {
  vi.useFakeTimers();
  let resolve;
  api.request.mockReturnValue(new Promise((done) => { resolve = done; }));
  const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function () {
    expect(this.download).toBe("BILL-42.pdf");
    expect(this.getAttribute("href")).toBe("blob:qa-pdf");
    expect(this.isConnected).toBe(true);
  });
  render(<BillPdfDownload billId={42} />);
  fireEvent.click(screen.getByRole("button", { name: "PDF" }));
  const pending = screen.getByRole("button", { name: "Descargando…" });
  expect(pending.disabled).toBe(true);
  fireEvent.click(pending);
  expect(api.request).toHaveBeenCalledTimes(1);
  expect(api.request).toHaveBeenCalledWith("/api/v1/bills/42/document", { responseType: "blob" });
  await act(async () => resolve({ blob: new Blob(["%PDF-"]), filename: "BILL-42.pdf" }));
  expect(click).toHaveBeenCalledTimes(1);
  expect(document.querySelector("a[download]")).toBeNull();
  expect(screen.getByRole("button", { name: "PDF" }).disabled).toBe(false);
  act(() => vi.runAllTimers());
  expect(URL.revokeObjectURL).toHaveBeenCalledWith("blob:qa-pdf");
});

it("muestra el error y permite reintentar sin navegar", async () => {
  api.request.mockRejectedValue(new Error("No puede acceder a datos de otro contribuyente"));
  render(<BillPdfDownload billId={42} />);
  fireEvent.click(screen.getByRole("button", { name: "PDF" }));
  expect((await screen.findByRole("alert")).textContent).toContain("otro contribuyente");
  expect(URL.createObjectURL).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole("button", { name: "PDF" }));
  await waitFor(() => expect(api.request).toHaveBeenCalledTimes(2));
});

it("no intenta descargar referencias S3 ficticias en modo demo", () => {
  api.mocks = true;
  render(<BillPdfDownload billId={42} />);
  const button = screen.getByRole("button", { name: "PDF" });
  expect(button.disabled).toBe(true);
  fireEvent.click(button);
  expect(api.request).not.toHaveBeenCalled();
});
