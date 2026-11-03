import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import DebtDetailModal from "./DebtDetailModal.jsx";
import { debtDetailsService } from "../../services/debtDetailsService.js";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("DebtDetailModal", () => {
  it("muestra saldo, origen externo y motivo de anulación", async () => {
    vi.spyOn(debtDetailsService, "detail").mockResolvedValue({
      id: 4,
      status: "CANCELLED",
      originalAmount: 1200.5,
      outstandingAmount: 0,
      dueDate: "2026-11-10",
      originType: "EXTERNAL_OBLIGATION",
      originId: "legacy-4",
      source: { externalReferenceId: "M7-4", sourceModule: "M7" },
      cancellationReason: "Anulada por el módulo de origen",
    });

    render(<DebtDetailModal debtId={4} includeExternalOrigin onClose={vi.fn()} />);

    expect(await screen.findByRole("dialog", { name: "Deuda #4" })).toBeTruthy();
    expect(debtDetailsService.detail).toHaveBeenCalledWith(4, { includeExternalOrigin: true });
    expect(screen.getByText("M7-4")).toBeTruthy();
    expect(screen.getByText("M7")).toBeTruthy();
    expect(screen.getByText("Anulada por el módulo de origen")).toBeTruthy();
    expect(screen.getByText(/1\.200,50/)).toBeTruthy();
  });

  it("permite reintentar una consulta fallida", async () => {
    const user = userEvent.setup();
    const detail = vi.spyOn(debtDetailsService, "detail")
      .mockRejectedValueOnce(new Error("No disponible"))
      .mockResolvedValueOnce({ id: 5, status: "PENDING", originalAmount: 100, outstandingAmount: 100 });

    render(<DebtDetailModal debtId={5} onClose={vi.fn()} />);

    expect(await screen.findByText("No disponible")).toBeTruthy();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));
    expect(await screen.findByText("Importe original")).toBeTruthy();
    expect(detail).toHaveBeenCalledTimes(2);
  });
});
