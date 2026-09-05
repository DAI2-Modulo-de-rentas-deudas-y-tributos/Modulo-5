import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import App from "../App.jsx";
import { ingresarComoAgente } from "./helpers/ingresar.js";

/** Las dos mejoras de consulta del auditor, desde la pantalla. */
async function entrarAAuditoria(user, modulo) {
  render(<App />);
  await ingresarComoAgente(user, "acastro", "audit123");
  await waitFor(() => expect(screen.getByRole("heading", { name: /hola, ana/i })).toBeDefined());
  await user.click(screen.getByRole("link", { name: modulo }));
  await waitFor(() => expect(screen.queryByText(/cargando información/i)).toBeNull());
}

describe("liquidaciones en la ficha del contribuyente", () => {
  let user;
  beforeEach(() => { user = userEvent.setup(); });
  afterEach(() => {
    cleanup();
    sessionStorage.clear();
    window.history.pushState({}, "", "/");
  });

  it("la ficha abre en Liquidaciones, que es donde empieza el circuito", async () => {
    await entrarAAuditoria(user, /^contribuyentes$/i);
    await user.click(await screen.findByText("Juan Pérez"));

    const solapas = await screen.findAllByRole("button", { name: /liquidaciones/i });
    expect(solapas.length).toBeGreaterThan(0);
    // Y la tabla ya muestra sus columnas.
    expect(await screen.findByText(/período/i)).toBeDefined();
  });

  it("se puede recorrer las cinco solapas", async () => {
    await entrarAAuditoria(user, /^contribuyentes$/i);
    await user.click(await screen.findByText("Juan Pérez"));

    for (const solapa of ["Deudas", "Pagos", "Planes", "Exenciones", "Liquidaciones"]) {
      await user.click(screen.getByRole("button", { name: new RegExp(`^${solapa}$`, "i") }));
    }
    expect(screen.getByRole("button", { name: /^liquidaciones$/i })).toBeDefined();
  });
});

describe("comparación de versiones de un concepto", () => {
  let user;
  beforeEach(() => { user = userEvent.setup(); });
  afterEach(() => {
    cleanup();
    sessionStorage.clear();
    window.history.pushState({}, "", "/");
  });

  it("pide una segunda versión cuando sólo se eligió una", async () => {
    await entrarAAuditoria(user, /^conceptos$/i);
    await user.click(await screen.findByText("TASA_SERVICIOS"));

    await user.click(await screen.findByLabelText(/comparar la versión 3/i));

    expect(await screen.findByText(/elegí una segunda versión/i)).toBeDefined();
  });

  it("compara dos versiones y resalta lo que cambió", async () => {
    await entrarAAuditoria(user, /^conceptos$/i);
    await user.click(await screen.findByText("TASA_SERVICIOS"));

    await user.click(await screen.findByLabelText(/comparar la versión 3/i));
    await user.click(await screen.findByLabelText(/comparar la versión 2/i));

    expect(await screen.findByText(/comparación · v2 → v3/i)).toBeDefined();
    expect(screen.getByText(/forma de cálculo/i)).toBeDefined();
    expect(screen.getByText(/qué se registró en cada cambio/i)).toBeDefined();
  });

  it("no deja elegir una tercera versión", async () => {
    await entrarAAuditoria(user, /^conceptos$/i);
    await user.click(await screen.findByText("TASA_SERVICIOS"));

    await user.click(await screen.findByLabelText(/comparar la versión 3/i));
    await user.click(await screen.findByLabelText(/comparar la versión 2/i));

    expect(screen.getByLabelText(/comparar la versión 1/i).disabled).toBe(true);
  });

  it("avisa cuando las versiones son anteriores al versionado de parámetros", async () => {
    await entrarAAuditoria(user, /^conceptos$/i);
    await user.click(await screen.findByText("TASA_SERVICIOS"));

    await user.click(await screen.findByLabelText(/comparar la versión 1/i));
    await user.click(await screen.findByLabelText(/comparar la versión 2/i));

    expect(await screen.findByText(/anteriores al versionado de parámetros/i)).toBeDefined();
  });

  it("permite limpiar la selección", async () => {
    await entrarAAuditoria(user, /^conceptos$/i);
    await user.click(await screen.findByText("TASA_SERVICIOS"));

    await user.click(await screen.findByLabelText(/comparar la versión 3/i));
    await user.click(await screen.findByRole("button", { name: /limpiar selección/i }));

    expect(screen.queryByText(/elegí una segunda versión/i)).toBeNull();
  });
});
