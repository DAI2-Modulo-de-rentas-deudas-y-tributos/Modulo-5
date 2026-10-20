import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import App from "../App.jsx";
import { ingresarComoAgente } from "./helpers/ingresar.js";
import { instalarBackendFalso, pagina } from "./fixtures/backendFalso.js";

const debt = {
  id: 6, taxpayerId: 123, taxConceptId: 1, liquidationId: 6,
  originType: "LIQUIDATION", status: "PENDING", originalAmount: 100000,
  currentAmount: 102100, outstandingBalance: 102100,
  dueDate: "2026-10-17", overdue: true,
};

const appliedCharge = {
  applicationId: 1, debtId: 6, calculationDate: "2026-10-18", daysOverdue: 1,
  principal: 100000, surchargeRate: 2, surchargeAmount: 2000,
  interestRate: 0.1, interestAmount: 100, previousAdjustments: 0,
  totalAdjustment: 2100, updatedTotal: 102100,
  ruleCode: "GENERAL_MUNICIPAL", applied: false,
};

describe("recargos ya aplicados", () => {
  let user;

  beforeEach(() => { user = userEvent.setup(); });
  afterEach(() => {
    cleanup();
    sessionStorage.clear();
    window.history.pushState({}, "", "/");
    vi.unstubAllGlobals();
  });

  async function openDebtPage(routes) {
    const backend = instalarBackendFalso({ "GET /api/v1/debts": pagina([debt]), ...routes });
    render(<App />);
    await ingresarComoAgente(user, "mrivas");
    await user.click(await screen.findByRole("link", { name: /^deudas$/i }));
    await user.click(await screen.findByRole("button", { name: /calcular recargo/i }));
    return { backend, dialog: await screen.findByRole("dialog", { name: /recargos de deuda #6/i }) };
  }

  it("muestra el importe histórico y no ofrece volver a aplicar el recargo del mismo día", async () => {
    const { backend, dialog } = await openDebtPage({
      "POST /api/v1/debts/{id}/late-charge-preview": appliedCharge,
    });

    expect(within(dialog).getByText(/ya fue aplicado/i)).toBeDefined();
    expect(within(dialog).getByText(/total tras la aplicación/i)).toBeDefined();
    expect(within(dialog).queryByRole("button", { name: /confirmar aplicación/i })).toBeNull();
    expect(backend.llamadas.filter((call) => call.ruta === "/api/v1/debts/6/late-charges")).toHaveLength(0);
  });

  it("informa una aplicación concurrente sin afirmar que se cobró otra vez", async () => {
    const { dialog } = await openDebtPage({
      "POST /api/v1/debts/{id}/late-charge-preview": { ...appliedCharge, applicationId: null },
      "POST /api/v1/debts/{id}/late-charges": appliedCharge,
    });

    await user.click(within(dialog).getByRole("button", { name: /confirmar aplicación/i }));
    expect(await screen.findByText("Recargo ya aplicado")).toBeDefined();
    expect(screen.getByText(/no se agregó otro cargo/i)).toBeDefined();
  });
});
