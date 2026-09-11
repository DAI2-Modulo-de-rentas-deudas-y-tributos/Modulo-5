import { afterEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { RegisterPaymentModal } from "../pages/rentas/PagosPage.jsx";
import { ChargeStep } from "../pages/caja/CobrosPage.jsx";
import { cashierService, debtService, paymentService } from "../services/rentasService.js";

afterEach(() => { cleanup(); vi.restoreAllMocks(); });

const deuda = { id: 51, outstandingAmount: 100, conceptCode: "ABL", status: "PENDING", dueDate: "2026-10-01" };

async function abrir(area, completado) {
  const user = userEvent.setup();
  if (area === "RENTAS") {
    vi.spyOn(debtService, "list").mockResolvedValue([deuda]);
    render(<RegisterPaymentModal taxpayerOptions={[{ value: "9", label: "QA" }]} onClose={() => {}} onDone={completado} />);
    await user.selectOptions(screen.getByLabelText(/contribuyente/i), "9");
    await user.type(screen.getByLabelText(/^importe/i), "100");
    await user.selectOptions(screen.getByLabelText(/canal/i), "VENTANILLA");
  } else {
    render(<ChargeStep context={{ taxpayer: { id: 9, name: "QA", status: "ACTIVE" }, debts: [deuda], totals: { pendingCount: 1, outstanding: 100, overdue: 0 }, kind: "TAXPAYER", selectedDebtId: 51 }} cashier="QA" onCharged={completado} onCancel={() => {}} />);
    await user.selectOptions(screen.getByLabelText(/medio de pago/i), "CASH");
  }
  return document.querySelector("form");
}

describe.each(["RENTAS", "CAJA"])("seguridad de la intención de pago en %s", (area) => {
  it("bloquea envío simultáneo, conserva UUID al reintentar y renueva sólo después del éxito", async () => {
    const servicio = area === "RENTAS" ? paymentService : cashierService;
    const metodo = area === "RENTAS" ? "register" : "registerCounterPayment";
    let rechazar;
    const completado = vi.fn();
    const registrar = vi.spyOn(servicio, metodo)
      .mockImplementationOnce(() => new Promise((_, reject) => { rechazar = reject; }))
      .mockResolvedValue({ id: 71 });
    const form = await abrir(area, completado);
    act(() => { fireEvent.submit(form); fireEvent.submit(form); });
    expect(registrar).toHaveBeenCalledTimes(1);
    const key = registrar.mock.calls[0][0].idempotencyKey;
    expect(key).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i);
    await act(async () => { rechazar(new TypeError("Respuesta perdida")); });
    expect(await screen.findByText("Respuesta perdida")).toBeDefined();
    fireEvent.submit(form);
    await waitFor(() => expect(completado).toHaveBeenCalledTimes(1));
    expect(registrar.mock.calls[1][0]).toEqual(registrar.mock.calls[0][0]);
    fireEvent.submit(form);
    await waitFor(() => expect(completado).toHaveBeenCalledTimes(2));
    expect(registrar.mock.calls[2][0].idempotencyKey).not.toBe(key);
  });

  it("muestra el conflicto 409 sin cambiar silenciosamente la key ni registrar otro pago", async () => {
    const completado = vi.fn();
    const servicio = area === "RENTAS" ? paymentService : cashierService;
    const registrar = vi.spyOn(servicio, area === "RENTAS" ? "register" : "registerCounterPayment")
      .mockRejectedValueOnce(new TypeError("Respuesta perdida"))
      .mockRejectedValue(Object.assign(new Error("Clave reutilizada con otro importe"), { status: 409 }));
    const form = await abrir(area, completado);
    fireEvent.submit(form);
    await screen.findByText("Respuesta perdida");
    fireEvent.change(screen.getByLabelText(/^importe/i), { target: { value: "90" } });
    fireEvent.submit(form);
    expect(await screen.findByText("Clave reutilizada con otro importe")).toBeDefined();
    expect(registrar.mock.calls[1][0].idempotencyKey).toBe(registrar.mock.calls[0][0].idempotencyKey);
    expect(completado).not.toHaveBeenCalled();
  });
});
