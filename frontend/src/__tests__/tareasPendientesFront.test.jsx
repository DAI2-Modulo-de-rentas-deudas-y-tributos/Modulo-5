import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import App from "../App.jsx";
import { ingresarComoAgente } from "./helpers/ingresar.js";
import { instalarBackendFalso, pagina } from "./fixtures/backendFalso.js";

const reversal = {
  id: 710,
  paymentId: 9005,
  reason: "El importe fue cobrado dos veces por error",
  status: "PENDING_APPROVAL",
  requestedBy: "pcabrera",
  requestedAt: "2026-09-19T10:30:00-03:00",
};

const planConfigurations = [
  {
    id: 42,
    version: 2,
    minimumInstallments: 3,
    maximumInstallments: 12,
    minimumDownPaymentPercentage: 10,
    interestRate: 5,
    graceDays: 5,
    maxOverdueInstallments: 2,
    partialInstallmentPaymentAllowed: true,
    refinancingAllowed: true,
    maxRefinancingCount: 1,
    validFrom: "2026-01-01",
    validUntil: null,
    active: true,
    createdBy: "mrivas",
    createdAt: "2026-01-01T10:00:00-03:00",
  },
  {
    id: 41,
    version: 1,
    minimumInstallments: 3,
    maximumInstallments: 6,
    minimumDownPaymentPercentage: 20,
    interestRate: 8,
    graceDays: 3,
    maxOverdueInstallments: 1,
    partialInstallmentPaymentAllowed: false,
    refinancingAllowed: false,
    maxRefinancingCount: 0,
    validFrom: "2025-01-01",
    validUntil: "2025-12-31",
    active: true,
    createdBy: "mrivas",
    createdAt: "2025-01-01T10:00:00-03:00",
  },
];

describe("tareas frontend de reversiones y financiación", () => {
  let user;

  beforeEach(() => {
    user = userEvent.setup();
  });

  afterEach(() => {
    cleanup();
    sessionStorage.clear();
    window.history.pushState({}, "", "/");
    vi.unstubAllGlobals();
  });

  it("permite al Cajero solicitar la reversión desde el detalle y exige motivo y confirmación", async () => {
    const fetchFalso = instalarBackendFalso({
      "POST /api/v1/payments/{id}/reversal-requests": ({ cuerpo }) => ({ ...reversal, reason: cuerpo.reason }),
    });
    render(<App />);
    await ingresarComoAgente(user, "pcabrera", "caja123");
    await waitFor(() => expect(screen.getByRole("heading", { name: /hola, paula/i })).toBeDefined());
    await user.click(screen.getByRole("link", { name: /^pagos$/i }));
    await user.type(await screen.findByRole("searchbox"), "40111222");
    expect(await screen.findByText("REC-2026-9005")).toBeDefined();
    await user.click((await screen.findAllByText("REC-2026-9005"))[0]);

    await user.click(await screen.findByRole("button", { name: /solicitar reversión/i }));
    const dialog = await screen.findByRole("dialog", { name: /solicitar reversión/i });
    await user.click(within(dialog).getByRole("button", { name: /enviar solicitud/i }));
    expect(await within(dialog).findByText(/describí el error/i)).toBeDefined();

    await user.type(within(dialog).getByLabelText(/motivo de la solicitud/i), reversal.reason);
    await user.click(within(dialog).getByRole("checkbox"));
    await user.click(within(dialog).getByRole("button", { name: /enviar solicitud/i }));

    expect(await screen.findByText(/quedó pendiente de aprobación/i)).toBeDefined();
    const call = fetchFalso.llamadas.find((item) => item.ruta.endsWith("/reversal-requests"));
    expect(call.cuerpo).toEqual({ reason: reversal.reason });
  });

  it("muestra al Supervisor el pago, la deuda, las imputaciones y permite aprobar", async () => {
    instalarBackendFalso({
      "GET /api/v1/payment-reversals": pagina([reversal]),
      "GET /api/v1/payment-reversals/{id}": reversal,
      "GET /api/v1/payments/{id}/allocations": [
        { id: 81, paymentId: 9005, debtId: 3001, amount: 25000, status: "ACTIVE" },
      ],
      "POST /api/v1/payment-reversals/{id}/approve": { ...reversal, status: "APPROVED", resolvedBy: "jlopez", resolvedAt: "2026-09-20T09:00:00-03:00" },
    });
    render(<App />);
    await ingresarComoAgente(user, "jlopez", "rentas123");
    await user.click(await screen.findByRole("link", { name: /reversiones de pago/i }));

    expect(await screen.findByText("REC-2026-9005")).toBeDefined();
    await user.click(await screen.findByRole("button", { name: /revisar/i }));
    const dialog = await screen.findByRole("dialog", { name: /solicitud de reversión/i });
    expect(await within(dialog).findByText("Juan Pérez")).toBeDefined();
    expect(within(dialog).getByText("Deuda #3001")).toBeDefined();
    expect(within(dialog).getByText(reversal.reason)).toBeDefined();

    await user.click(within(dialog).getByRole("button", { name: /aprobar solicitud/i }));
    expect(await screen.findByText(/fue aprobada.*sigue confirmado/i)).toBeDefined();
  });

  it("exige un motivo al Supervisor y permite rechazar la solicitud", async () => {
    const fetchFalso = instalarBackendFalso({
      "GET /api/v1/payment-reversals": pagina([reversal]),
      "GET /api/v1/payment-reversals/{id}": reversal,
      "GET /api/v1/payments/{id}/allocations": [],
      "POST /api/v1/payment-reversals/{id}/reject": ({ cuerpo }) => ({
        ...reversal,
        status: "REJECTED",
        resolvedBy: "jlopez",
        resolvedAt: "2026-09-20T09:00:00-03:00",
        resolutionReason: cuerpo.reason,
      }),
    });
    render(<App />);
    await ingresarComoAgente(user, "jlopez", "rentas123");
    await user.click(await screen.findByRole("link", { name: /reversiones de pago/i }));
    await user.click(await screen.findByRole("button", { name: /revisar/i }));

    const dialog = await screen.findByRole("dialog", { name: /solicitud de reversión/i });
    await user.selectOptions(within(dialog).getByLabelText(/decisión/i), "REJECTED");
    await user.click(within(dialog).getByRole("button", { name: /rechazar solicitud/i }));
    expect(await within(dialog).findByText(/motivo del rechazo.*5 caracteres/i)).toBeDefined();

    const reason = "El comprobante es correcto";
    await user.type(within(dialog).getByLabelText(/motivo del rechazo/i), reason);
    await user.click(within(dialog).getByRole("button", { name: /rechazar solicitud/i }));
    expect(await screen.findByText(/fue rechazada.*no fue modificado/i)).toBeDefined();
    const call = fetchFalso.llamadas.find((item) => item.ruta.endsWith("/reject"));
    expect(call.cuerpo).toEqual({ reason });
  });

  it("consulta la configuración vigente y el historial y crea una versión nueva", async () => {
    const fetchFalso = instalarBackendFalso({
      "GET /api/v1/payment-plan-configurations": pagina(planConfigurations),
      "POST /api/v1/payment-plan-configurations": ({ cuerpo }) => ({
        ...cuerpo,
        id: 43,
        version: 3,
        createdBy: "mrivas",
        createdAt: "2026-09-20T10:00:00-03:00",
      }),
    });
    render(<App />);
    await ingresarComoAgente(user, "mrivas", "rentas123");
    await user.click(await screen.findByRole("link", { name: /parámetros de financiación/i }));

    expect(await screen.findByRole("heading", { name: /configuración vigente.*v2/i })).toBeDefined();
    expect(screen.getByText("v1")).toBeDefined();
    expect(screen.getByText("Histórica")).toBeDefined();

    await user.click(screen.getByRole("button", { name: /nueva versión/i }));
    const dialog = await screen.findByRole("dialog", { name: /nueva versión de financiación/i });
    await user.clear(within(dialog).getByLabelText(/cuotas máximas/i));
    await user.type(within(dialog).getByLabelText(/cuotas máximas/i), "18");
    await user.click(within(dialog).getByRole("button", { name: /guardar versión/i }));

    expect(await screen.findByText(/versión 3 quedó registrada/i)).toBeDefined();
    const call = fetchFalso.llamadas.find((item) => item.metodo === "POST" && item.ruta === "/api/v1/payment-plan-configurations");
    expect(call.cuerpo.maximumInstallments).toBe(18);
    expect(call.cuerpo.active).toBe(true);
  });
});
