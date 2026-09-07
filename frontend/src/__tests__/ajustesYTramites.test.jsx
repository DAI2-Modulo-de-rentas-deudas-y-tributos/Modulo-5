import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import App from "../App.jsx";
import { ingresarComoAgente } from "./helpers/ingresar.js";
import { instalarBackendFalso, pagina } from "./fixtures/backendFalso.js";

/** Ajustes manuales, saldos a favor y el trámite de documentación, desde la pantalla. */
async function entrar(user, usuario, modulo) {
  render(<App />);
  await ingresarComoAgente(user, usuario, "rentas123");
  await waitFor(() => expect(screen.getByRole("heading", { name: /hola,/i })).toBeDefined());
  await user.click(screen.getByRole("link", { name: modulo }));
  await waitFor(() => expect(screen.queryByText(/cargando información/i)).toBeNull());
}

describe("ajustes y saldos a favor", () => {
  let user;
  beforeEach(() => {
    user = userEvent.setup();
    instalarBackendFalso();
  });
  afterEach(() => {
    cleanup();
    sessionStorage.clear();
    window.history.pushState({}, "", "/");
    vi.unstubAllGlobals();
  });

  it("aclara que autorizar y ejecutar son actos distintos", async () => {
    await entrar(user, "mrivas", /ajustes y saldos/i);

    expect(await screen.findByText(/autorizar y ejecutar son actos distintos/i)).toBeDefined();
  });

  it("muestra el saldo a favor disponible", async () => {
    await entrar(user, "mrivas", /ajustes y saldos/i);

    expect(await screen.findByText(/saldos a favor/i)).toBeDefined();
    expect(screen.getByText(/aplicar a una deuda/i)).toBeDefined();
  });

  it("aplicar un saldo aclara que no genera un pago", async () => {
    await entrar(user, "mrivas", /ajustes y saldos/i);
    await user.click(await screen.findByRole("button", { name: /aplicar a una deuda/i }));

    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText(/no se registra un pago nuevo/i)).toBeDefined();
  });

  it("aplica el saldo y reduce la deuda", async () => {
    await entrar(user, "mrivas", /ajustes y saldos/i);
    await user.click(await screen.findByRole("button", { name: /aplicar a una deuda/i }));

    const dialog = await screen.findByRole("dialog");
    const select = within(dialog).getByLabelText(/deuda/i);
    await waitFor(() =>
      expect(within(select).getAllByRole("option").length).toBeGreaterThan(1),
    );
    await user.selectOptions(select, within(select).getAllByRole("option")[1]);
    await user.type(within(dialog).getByLabelText(/importe a aplicar/i), "5000");
    await user.click(within(dialog).getByRole("button", { name: /^aplicar$/i }));

    expect(await screen.findByText(/saldo aplicado/i)).toBeDefined();
  });

  it("el ajuste exige motivo y algún cambio", async () => {
    await entrar(user, "mrivas", /ajustes y saldos/i);
    await user.click(await screen.findByRole("button", { name: /proponer ajuste/i }));

    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: /^proponer$/i }));

    expect(await within(dialog).findByText(/elegí la deuda/i)).toBeDefined();
  });

  it("el analista propone y no puede autorizar", async () => {
    await entrar(user, "mrivas", /ajustes y saldos/i);
    await user.click(await screen.findByRole("button", { name: /proponer ajuste/i }));

    const dialog = await screen.findByRole("dialog");
    const select = within(dialog).getByLabelText(/^deuda/i);
    await waitFor(() =>
      expect(within(select).getAllByRole("option").length).toBeGreaterThan(1),
    );
    await user.selectOptions(select, within(select).getAllByRole("option")[1]);
    await user.clear(within(dialog).getByLabelText(/importe ajustado/i));
    await user.type(within(dialog).getByLabelText(/importe ajustado/i), "1000");
    await user.type(within(dialog).getByLabelText(/motivo del ajuste/i), "Error de carga");
    await user.click(within(dialog).getByRole("button", { name: /^proponer$/i }));

    expect(await screen.findByText(/ajuste propuesto/i)).toBeDefined();
    expect(await screen.findByText(/espera al supervisor/i)).toBeDefined();
  });

  it("el Supervisor autoriza y el backend aplica el ajuste", async () => {
    // Autorizar y aplicar ocurren en la misma operación del backend: aprobado es aplicado.
    let autorizado = false;
    instalarBackendFalso({
      "POST /api/v1/adjustments/{id}/approve": () => {
        autorizado = true;
        return { id: 4001, status: "APPROVED" };
      },
      "GET /api/v1/adjustments": () => pagina([
        { id: 4001, debtId: 3001, type: "DISCOUNT", amount: 25000, reason: "Error en la base imponible", status: autorizado ? "APPROVED" : "PENDING_APPROVAL", requestedBy: "mrivas", requestedAt: "2026-08-26T09:00:00-03:00", previousDebtAmount: 85000, newDebtAmount: 60000 },
      ]),
    });
    await entrar(user, "jlopez", /ajustes y saldos/i);

    await user.click(await screen.findByRole("button", { name: /autorizar/i }));
    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: /confirmar/i }));

    expect(await screen.findByText(/ajuste autorizado/i)).toBeDefined();
    expect(screen.queryByRole("button", { name: /^autorizar$/i })).toBeNull();
  });
});

describe("trámite de documentación en exenciones", () => {
  let user;
  beforeEach(() => {
    user = userEvent.setup();
    instalarBackendFalso();
  });
  afterEach(() => {
    cleanup();
    sessionStorage.clear();
    window.history.pushState({}, "", "/");
  });

  it("aclara que los pasos no salen de Rentas", async () => {
    await entrar(user, "mrivas", /exenciones/i);
    await user.click((await screen.findAllByRole("button", { name: /trámite/i }))[0]);

    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText(/estos pasos no salen de rentas/i)).toBeDefined();
  });

  it("pedir documentación exige decir cuál falta", async () => {
    await entrar(user, "mrivas", /exenciones/i);
    await user.click((await screen.findAllByRole("button", { name: /trámite/i }))[0]);

    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: /confirmar/i }));

    expect(await within(dialog).findByText(/indicá qué documentación falta/i)).toBeDefined();
  });

  it("pide documentación y la solicitud queda marcada", async () => {
    // Pedida la documentación, la solicitud pasa a esperarla y el listado lo refleja.
    let pedida = false;
    instalarBackendFalso({
      "POST /api/v1/exemption-requests/{id}/request-documentation": () => {
        pedida = true;
        return { id: 600, status: "DOCUMENTATION_REQUIRED" };
      },
      "GET /api/v1/exemption-requests": () => pagina([
        { id: 600, taxpayerId: 123, taxConceptId: 1, status: pedida ? "DOCUMENTATION_REQUIRED" : "PENDING", requestedPercentage: 100, reason: "Situación socioeconómica", requestedFrom: "2026-09-01", requestedUntil: "2027-08-31", requestedAt: "2026-08-18T10:00:00-03:00" },
      ]),
    });
    await entrar(user, "mrivas", /exenciones/i);
    await user.click((await screen.findAllByRole("button", { name: /trámite/i }))[0]);

    const dialog = await screen.findByRole("dialog");
    await user.type(
      within(dialog).getByLabelText(/qué documentación falta/i),
      "Certificado de ingresos",
    );
    await user.click(within(dialog).getByRole("button", { name: /confirmar/i }));

    expect(await screen.findByText(/trámite actualizado/i)).toBeDefined();
    expect(await screen.findByText(/falta documentación/i)).toBeDefined();
  });
});

describe("información recibida en tickets", () => {
  let user;
  beforeEach(() => {
    user = userEvent.setup();
    instalarBackendFalso();
  });
  afterEach(() => {
    cleanup();
    sessionStorage.clear();
    window.history.pushState({}, "", "/");
  });

  it("no anuncia adjuntos que el backend no expone", async () => {
    await entrar(user, "mrivas", /tickets/i);

    // TicketResponse no tiene adjuntos: el listado no debe mostrar un contador inventado.
    expect(await screen.findByRole("table")).toBeDefined();
    expect(screen.queryByText(/adjunto\(s\)/i)).toBeNull();
  });
});
