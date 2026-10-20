import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import App from "../App.jsx";
import { ingresarComoAgente } from "./helpers/ingresar.js";
import { instalarBackendFalso, pagina } from "./fixtures/backendFalso.js";
import { resetPlanConfigurationCache } from "../hooks/usePlanConfiguration.js";

describe("simulación de refinanciación en Rentas", () => {
  beforeEach(() => resetPlanConfigurationCache());

  afterEach(() => {
    cleanup();
    sessionStorage.clear();
    window.history.pushState({}, "", "/");
    vi.unstubAllGlobals();
  });

  it("muestra alternativas calculadas por el backend y no ofrece un anticipo inexistente", async () => {
    const user = userEvent.setup();
    const configuration = {
      id: 1, active: true, refinancingAllowed: true, maxRefinancingCount: 2,
      minimumInstallments: 3, maximumInstallments: 12,
    };
    const backend = instalarBackendFalso({
      "GET /api/v1/payment-plans": pagina([{
        id: 851, taxpayerId: 123, status: "ACTIVE", configurationId: 1,
        refinancingCount: 0, installmentCount: 6, totalPlanAmount: 10000,
        outstandingPlanAmount: 8000,
      }]),
      "GET /api/v1/payment-plan-configurations": pagina([configuration]),
      "GET /api/v1/payment-plan-configurations/{id}": configuration,
      "GET /api/v1/refinancing-requests": pagina([]),
      "POST /api/v1/payment-plans/{id}/refinancing/simulations": ({ cuerpo }) => ({
        originalPlanId: 851, outstandingPrincipal: 8000, interest: 800,
        total: 8800, installments: cuerpo.installments, exceptional: false,
      }),
    });

    render(<App />);
    await ingresarComoAgente(user, "mrivas");
    await user.click(await screen.findByRole("link", { name: /^refinanciación$/i }));
    await user.click(await screen.findByRole("button", { name: /^refinanciar$/i }));

    const dialog = await screen.findByRole("dialog");
    const alternatives = await within(dialog).findAllByRole("button", { name: /cuotas/i });
    expect(alternatives).toHaveLength(3);
    expect(within(dialog).queryByLabelText(/anticipo/i)).toBeNull();
    expect(backend.llamadas.filter((call) => call.metodo === "POST" && call.ruta.endsWith("/refinancing/simulations")))
      .toMatchObject([
        { cuerpo: { installments: 3 } },
        { cuerpo: { installments: 8 } },
        { cuerpo: { installments: 12 } },
      ]);
  });
});
