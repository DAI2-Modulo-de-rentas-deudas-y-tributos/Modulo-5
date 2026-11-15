import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import App from "../App.jsx";
import { ingresarComoContribuyente } from "./helpers/ingresar.js";
import { instalarBackendFalso } from "./fixtures/backendFalso.js";

describe("pendientes reales del Sprint 3 en el portal", () => {
  let user;

  beforeEach(() => { user = userEvent.setup(); });
  afterEach(() => {
    cleanup();
    sessionStorage.clear();
    window.history.pushState({}, "", "/");
    vi.unstubAllGlobals();
  });

  it("muestra beneficio activo con vigencia, porcentaje y conceptos alcanzados, pero no uno vencido", async () => {
    const backend = instalarBackendFalso({
      "GET /api/v1/taxpayers/{id}/benefits": [
        {
          id: 2,
          externalBenefitId: "M8-PROPIO",
          taxpayerId: 123,
          benefitType: "SOCIAL_RATE",
          status: "ACTIVE",
          discountPercentage: 50,
          validFrom: "2020-01-01",
          validUntil: "2099-12-31",
          taxConceptCodes: ["TASA_SERVICIOS", "ABL"],
        },
        {
          id: 3,
          externalBenefitId: "M8-VENCIDO",
          taxpayerId: 123,
          benefitType: "SOCIAL_RATE",
          status: "ACTIVE",
          discountPercentage: 25,
          validFrom: "2000-01-01",
          validUntil: "2000-12-31",
          taxConceptCodes: ["PATENTE"],
        },
      ],
    });
    render(<App />);
    await ingresarComoContribuyente(user);
    await user.click(await screen.findByRole("link", { name: /^beneficios tributarios$/i }));

    expect(await screen.findByText("M8-PROPIO")).toBeDefined();
    expect(screen.getByText("SOCIAL_RATE")).toBeDefined();
    expect(screen.getByText("50%")).toBeDefined();
    expect(screen.getByText(/TASA_SERVICIOS, ABL/i)).toBeDefined();
    expect(screen.getByText("31/12/2099")).toBeDefined();
    expect(screen.queryByText("M8-VENCIDO")).toBeNull();
    expect(backend.llamadas.some((call) => call.ruta === "/api/v1/taxpayers/123/benefits")).toBe(true);
    expect(backend.llamadas.some((call) => /taxpayers\/(?!123)\d+\/benefits/.test(call.ruta))).toBe(false);
  });

  it("consulta y muestra imputaciones de deuda y cuota en el detalle de un pago propio", async () => {
    instalarBackendFalso({
      "GET /api/v1/payments/{id}": {
        id: 9005, taxpayerId: 123, receiptNumber: "REC-2026-9005", paymentMethod: "CARD",
        origin: "ELECTRONIC", status: "CONFIRMED", amount: 25000, allocatedAmount: 25000,
        unallocatedAmount: 0, paidAt: "2026-08-25T09:40:00-03:00", registeredBy: "portal",
      },
      "GET /api/v1/payments/{id}/allocations": [
        { id: 1, paymentId: 9005, targetType: "DEBT", debtId: 3001, installmentId: null, amount: 15000, status: "ACTIVE" },
        { id: 2, paymentId: 9005, targetType: "INSTALLMENT", debtId: null, installmentId: 77, amount: 10000, status: "ACTIVE" },
      ],
    });
    render(<App />);
    await ingresarComoContribuyente(user);
    await user.click(await screen.findByRole("link", { name: /^mis pagos$/i }));
    await user.click((await screen.findAllByText("REC-2026-9005"))[0]);

    const dialog = await screen.findByRole("dialog", { name: /pago #9005/i });
    await waitFor(() => expect(within(dialog).getByText("Deuda #3001")).toBeDefined());
    expect(within(dialog).getByText("Cuota #77")).toBeDefined();
    expect(within(dialog).getByText("$ 15.000,00")).toBeDefined();
    expect(within(dialog).getByText("$ 10.000,00")).toBeDefined();
    expect(within(dialog).getAllByText("Activo")).toHaveLength(2);
    expect(within(dialog).queryByRole("button", { name: /modificar|imputar/i })).toBeNull();
  });
});
