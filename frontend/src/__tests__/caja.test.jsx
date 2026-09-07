import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import App from "../App.jsx";
import { ingresarComoAgente } from "./helpers/ingresar.js";
import { instalarBackendFalso } from "./fixtures/backendFalso.js";

/**
 * Ventanilla de caja: el cajero entra a su propia área y completa el circuito
 * buscar → cobrar → comprobante, tanto por documento como por número de boleta.
 */
async function loginAsCajero(user) {
  render(<App />);
  await ingresarComoAgente(user, "pcabrera", "caja123");
  await waitFor(() => expect(screen.getByRole("heading", { name: /hola, paula/i })).toBeDefined());
}

describe("ventanilla de caja", () => {
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

  it("abre el panel de caja con los cuatro módulos de ventanilla", async () => {
    await loginAsCajero(user);

    expect(screen.getByRole("link", { name: /cobros/i })).toBeDefined();
    expect(screen.getByRole("link", { name: /contribuyentes/i })).toBeDefined();
    expect(screen.getByRole("link", { name: /pagos/i })).toBeDefined();
    expect(screen.getByRole("link", { name: /boletas/i })).toBeDefined();
  });

  it("no ofrece los módulos que son del back-office de Rentas", async () => {
    await loginAsCajero(user);

    expect(screen.queryByRole("link", { name: /liquidaciones/i })).toBeNull();
    expect(screen.queryByRole("link", { name: /exenciones/i })).toBeNull();
    expect(screen.queryByRole("link", { name: /bitácora/i })).toBeNull();
  });

  it("muestra las operaciones de la jornada", async () => {
    await loginAsCajero(user);

    expect(await screen.findByText(/total cobrado/i)).toBeDefined();
    // "Pagos registrados" es la métrica y también parte de la descripción del módulo.
    expect(screen.getAllByText(/pagos registrados/i).length).toBeGreaterThan(0);
    // Los cobros del dataset ya figuran entre los últimos pagos.
    expect(await screen.findByText("REC-2026-9006")).toBeDefined();
  });

  it("lista los pagos del día y reimprime un comprobante", async () => {
    await loginAsCajero(user);
    await user.click(screen.getByRole("link", { name: /pagos/i }));

    // La grilla abre filtrada por la jornada en curso; el filtro lo resuelve el backend.
    expect(await screen.findByLabelText(/responsable/i)).toBeDefined();
    await user.click((await screen.findAllByText("REC-2026-9005"))[0]);

    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getAllByText("REC-2026-9005").length).toBeGreaterThan(0);
    expect(within(dialog).getByText(/^tarjeta$/i)).toBeDefined();
    expect(within(dialog).getByText(/pcabrera/i)).toBeDefined();
  });

  it("cobra una deuda buscando por documento y emite el comprobante", async () => {
    await loginAsCajero(user);
    await user.click(screen.getByRole("link", { name: /cobros/i }));

    const search = await screen.findByLabelText(/n° de boleta \/ n° de deuda/i);
    await user.type(search, "40111222");
    await user.click(screen.getByRole("button", { name: /buscar/i }));

    await user.click(await screen.findByText("Juan Pérez"));

    // El paso de cobro muestra a quién se le cobra antes de pedir el medio de pago.
    const debtSelect = await screen.findByLabelText(/deuda a cobrar/i);
    await user.selectOptions(debtSelect, within(debtSelect).getAllByRole("option")[1]);
    await user.selectOptions(screen.getByLabelText(/medio de pago/i), "CASH");

    await user.click(screen.getByRole("button", { name: /^registrar pago$/i }));

    expect(await screen.findByText(/pago registrado correctamente/i)).toBeDefined();
    expect(screen.getByRole("button", { name: /imprimir comprobante/i })).toBeDefined();
    expect(screen.getByRole("button", { name: /registrar otro/i })).toBeDefined();
  });

  it("cobra una boleta desde el buscador de boletas", async () => {
    // Tras el cobro la deuda queda saldada: la relectura devuelve saldo cero.
    let cobrada = false;
    instalarBackendFalso({
      "POST /api/v1/payments": () => {
        cobrada = true;
        return { id: 9007, taxpayerId: 78, amount: 35000, paymentMethod: "TRANSFER", unallocatedAmount: 0, status: "CONFIRMED", receiptNumber: "REC-2026-9007", paidAt: "2026-09-07T10:00:00-03:00" };
      },
      "GET /api/v1/debts/{id}": () => ({
        id: 3003, taxpayerId: 78, taxConceptId: 2, status: cobrada ? "PAID" : "PENDING",
        outstandingBalance: cobrada ? 0 : 35000, originalAmount: 35000, dueDate: "2026-09-15", overdue: false,
      }),
    });
    await loginAsCajero(user);
    await user.click(screen.getByRole("link", { name: /boletas/i }));

    await user.type(await screen.findByLabelText(/n° de boleta/i), "12002");
    await user.click(screen.getByRole("button", { name: /buscar/i }));

    await user.click((await screen.findAllByRole("button", { name: /registrar pago/i }))[0]);

    // La boleta llega con su deuda y su contribuyente ya resueltos: falta el medio de pago.
    expect(await screen.findByText(/vinculada a comercial abc/i)).toBeDefined();
    await user.selectOptions(await screen.findByLabelText(/medio de pago/i), "TRANSFER");
    await user.click(screen.getByRole("button", { name: /^registrar pago$/i }));

    expect(await screen.findByText(/pago registrado correctamente/i)).toBeDefined();
    // El aviso y el comprobante dicen lo mismo: la obligación quedó saldada.
    expect(screen.getAllByText(/la deuda quedó cancelada/i).length).toBeGreaterThan(0);
  });

  it("consulta la ficha del contribuyente sin poder editarla", async () => {
    await loginAsCajero(user);
    await user.click(screen.getByRole("link", { name: /contribuyentes/i }));

    expect(await screen.findByText(/consulta de sólo lectura/i)).toBeDefined();

    await user.click(await screen.findByText("Juan Pérez"));
    await user.click(await screen.findByRole("button", { name: /ver deudas y pagos/i }));

    expect(await screen.findByRole("heading", { name: /juan pérez/i })).toBeDefined();
    // La ventanilla cobra la deuda; emitir la boleta es de Rentas, acá sólo se imprime.
    expect(screen.getAllByRole("button", { name: /^cobrar$/i }).length).toBeGreaterThan(0);
    expect(screen.queryByRole("button", { name: /generar boleta/i })).toBeNull();
  });
});
