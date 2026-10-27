import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import App from "../App.jsx";
import { ingresarComoAgente } from "./helpers/ingresar.js";
import { instalarBackendFalso, pagina } from "./fixtures/backendFalso.js";

async function entrarAPlanes(user, username) {
  render(<App />);
  await ingresarComoAgente(user, username);
  await waitFor(() => expect(screen.getByRole("heading", { name: /hola,/i })).toBeDefined());
  await user.click(screen.getByRole("link", { name: /planes de pago/i }));
  await screen.findByRole("navigation", { name: /breadcrumb/i });
}

describe("resolución de solicitudes de planes", () => {
  beforeEach(() => instalarBackendFalso());

  afterEach(() => {
    cleanup();
    sessionStorage.clear();
    window.history.pushState({}, "", "/");
    vi.unstubAllGlobals();
  });

  it("exige motivo al rechazar y envía la decisión al servicio", async () => {
    const fetch = instalarBackendFalso({
      "GET /api/v1/payment-plan-requests": () => pagina([{
        id: 800,
        taxpayerId: 123,
        status: "PENDING",
        totalDebtAtRequest: 125000,
        requestedInstallments: 6,
        estimatedDownPayment: 0,
        requestedAt: "2026-08-20T10:00:00-03:00",
      }]),
    });
    const user = userEvent.setup();
    await entrarAPlanes(user, "mrivas");
    await user.click(await screen.findByRole("button", { name: /^resolver$/i }));

    const dialog = await screen.findByRole("dialog");
    await user.selectOptions(within(dialog).getByLabelText(/resolución/i), "REJECTED");
    await user.click(within(dialog).getByRole("button", { name: /rechazar solicitud/i }));

    expect(await within(dialog).findByText(/motivo del rechazo.*obligatorio/i)).toBeDefined();
    expect(fetch.llamadas.some(({ metodo, ruta }) => metodo === "POST" && ruta.endsWith("/reject"))).toBe(false);

    await user.type(within(dialog).getByLabelText(/motivo del rechazo/i), "No cumple las condiciones");
    await user.click(within(dialog).getByRole("button", { name: /rechazar solicitud/i }));

    expect(await screen.findByText(/se comunicó el rechazo/i)).toBeDefined();
    expect(fetch.llamadas).toContainEqual({
      metodo: "POST",
      ruta: "/api/v1/payment-plan-requests/800/reject",
      cuerpo: { reason: "No cumple las condiciones" },
    });
  });

  it("muestra parámetros estándar al Supervisor y aprueba una solicitud derivada", async () => {
    const fetch = instalarBackendFalso({
      "GET /api/v1/payment-plan-requests": () => pagina([{
        id: 800,
        taxpayerId: 123,
        status: "PENDING_EXCEPTION_APPROVAL",
        exceptional: true,
        totalDebtAtRequest: 125000,
        requestedInstallments: 12,
        estimatedDownPayment: 10000,
        estimatedFinancedAmount: 115000,
        estimatedInterest: 12000,
        estimatedTotalAmount: 127000,
        exceptionReason: "Situación excepcional",
        requestedAt: "2026-08-20T10:00:00-03:00",
      }]),
      "GET /api/v1/payment-plan-configurations": () => pagina([{
        id: 1,
        active: true,
        minimumInstallments: 3,
        maximumInstallments: 6,
        minimumDownPaymentPercentage: 20,
        interestRate: 5,
      }]),
      "POST /api/v1/payment-plan-requests/{id}/grant": () => ({
        id: 800,
        status: "GRANTED",
        paymentPlanId: 950,
        requestedInstallments: 12,
        totalPlanAmount: 127000,
      }),
    });
    const user = userEvent.setup();
    await entrarAPlanes(user, "jlopez");
    await user.click(await screen.findByRole("button", { name: /^resolver$/i }));

    const dialog = await screen.findByRole("dialog");
    expect(await within(dialog).findByText(/3 a 6 cuotas.*anticipo mínimo 20%.*tasa 5%/i)).toBeDefined();
    expect(within(dialog).getByText(/situación excepcional/i)).toBeDefined();
    await user.click(within(dialog).getByRole("button", { name: /otorgar plan/i }));

    expect(await screen.findByText(/plan otorgado/i)).toBeDefined();
    expect(fetch.llamadas).toContainEqual({
      metodo: "POST",
      ruta: "/api/v1/payment-plan-requests/800/approve-exception",
      cuerpo: { observation: null },
    });
    expect(fetch.llamadas.some(({ metodo, ruta }) => metodo === "POST" && ruta === "/api/v1/payment-plan-requests/800/grant")).toBe(true);
  });
});
