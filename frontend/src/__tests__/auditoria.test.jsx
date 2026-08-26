import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import App from "../App.jsx";
import { AUDITORIA_MODULES } from "../config/auditoriaModules.js";

/**
 * Área de Auditoría: acceso de sólo lectura al circuito completo. Las pruebas recorren
 * el camino que hace un auditor —del listado al detalle— y verifican que no aparezcan
 * acciones que modifiquen entidades.
 */
async function loginAsAuditor(user) {
  render(<App />);
  await user.type(screen.getByLabelText(/usuario/i), "acastro");
  await user.type(screen.getByLabelText(/contraseña/i), "audit123");
  await user.click(screen.getByRole("button", { name: /ingresar/i }));
  await waitFor(() => expect(screen.getByRole("heading", { name: /hola, ana/i })).toBeDefined());
}

describe("área de auditoría", () => {
  let user;

  beforeEach(() => {
    user = userEvent.setup();
  });

  afterEach(() => {
    cleanup();
    sessionStorage.clear();
    window.history.pushState({}, "", "/");
  });

  it("abre el panel con los módulos de consulta", async () => {
    await loginAsAuditor(user);

    expect(screen.getByRole("link", { name: /^conceptos$/i })).toBeDefined();
    expect(screen.getByRole("link", { name: /^integraciones$/i })).toBeDefined();
    expect(screen.getByRole("link", { name: /^auditoría$/i })).toBeDefined();
    expect(screen.getByRole("link", { name: /^indicadores$/i })).toBeDefined();
    // El aviso está en el panel y también en el pie del menú lateral.
    expect((await screen.findAllByText(/acceso de sólo lectura/i)).length).toBeGreaterThan(0);
  });

  it.each(AUDITORIA_MODULES.map((module) => [module.label, module]))(
    "abre %s desde el menú lateral",
    async (label, module) => {
      await loginAsAuditor(user);
      await user.click(screen.getByRole("link", { name: new RegExp(`^${label}$`, "i") }));

      const breadcrumb = await screen.findByRole("navigation", { name: /breadcrumb/i });
      expect(within(breadcrumb).getByText(module.label)).toBeDefined();
      await waitFor(() => expect(screen.queryByText(/cargando información/i)).toBeNull());
    },
  );

  it("no ofrece acciones que modifiquen entidades", async () => {
    await loginAsAuditor(user);
    await user.click(screen.getByRole("link", { name: /^pagos$/i }));
    await screen.findByRole("navigation", { name: /breadcrumb/i });

    // El back-office y la caja sí las tienen; auditoría, no.
    expect(screen.queryByRole("button", { name: /registrar pago/i })).toBeNull();
    expect(screen.queryByRole("button", { name: /reversar/i })).toBeNull();
    expect(screen.queryByRole("button", { name: /emitir/i })).toBeNull();
  });

  it("abre una deuda y muestra su composición e historial", async () => {
    await loginAsAuditor(user);
    await user.click(screen.getByRole("link", { name: /^deudas$/i }));

    await user.click(await screen.findByText("#3200"));

    expect(await screen.findByRole("heading", { name: /deuda #3200/i })).toBeDefined();
    expect(screen.getByText(/composición del saldo/i)).toBeDefined();
    expect(screen.getByText(/historial de estados/i)).toBeDefined();
    expect(screen.getByText(/importe original/i)).toBeDefined();
  });

  it("sigue la cadena del evento en DLQ hasta su payload", async () => {
    await loginAsAuditor(user);
    await user.click(screen.getByRole("link", { name: /^integraciones$/i }));

    await user.click(await screen.findByText("permitUpdate"));

    expect(await screen.findByText(/el evento quedó en dlq/i)).toBeDefined();
    // El payload se muestra tal como viajó en el envelope común.
    expect(screen.getByText(/"permitId": 250/)).toBeDefined();
  });

  it("reconstruye una reversión con los valores antes y después", async () => {
    await loginAsAuditor(user);
    await user.click(screen.getByRole("link", { name: /^auditoría$/i }));

    // "Pago reversado" también figura como opción del filtro de acción: buscamos en la tabla.
    const table = await screen.findByRole("table");
    await user.click(within(table).getByText(/pago reversado/i));

    expect(await screen.findByText(/valores anteriores/i)).toBeDefined();
    expect(screen.getByText(/valores posteriores/i)).toBeDefined();
    expect(screen.getByText(/pago registrado por error/i)).toBeDefined();
  });

  it("abre un indicador en las filas que lo componen", async () => {
    await loginAsAuditor(user);
    await user.click(screen.getByRole("link", { name: /^indicadores$/i }));

    await user.click(await screen.findByRole("button", { name: /deuda vencida/i }));

    expect(await screen.findByText(/filas que componen el indicador/i)).toBeDefined();
    expect(screen.getByText(/^cantidad$/i)).toBeDefined();
  });

  it("ofrece la vista de tabla de los gráficos", async () => {
    await loginAsAuditor(user);
    await user.click(screen.getByRole("link", { name: /^indicadores$/i }));

    await user.click(await screen.findByRole("button", { name: /ver como tabla/i }));

    expect(await screen.findByRole("button", { name: /ver gráficos/i })).toBeDefined();
    expect(screen.getByRole("columnheader", { name: /recaudado/i })).toBeDefined();
  });
});
