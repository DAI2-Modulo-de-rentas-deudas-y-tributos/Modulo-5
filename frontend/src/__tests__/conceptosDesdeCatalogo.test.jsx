import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import App from "../App.jsx";
import { ingresarComoAgente } from "./helpers/ingresar.js";
import { resetTaxConceptsCache } from "../hooks/useTaxConcepts.js";

/**
 * Conceptos de los combos operativos (SCRUM-240).
 *
 * Los formularios ofrecían una lista fija copiada del dataset local: se podía elegir
 * un código que el módulo no tenía dado de alta y la operación fallaba recién al
 * registrarla ("Concepto inexistente"). Ahora las opciones salen del catálogo, así
 * que sólo se puede elegir lo que el backend realmente conoce.
 */
async function entrar(user, usuario, modulo) {
  render(<App />);
  await ingresarComoAgente(user, usuario);
  await waitFor(() => expect(screen.getByRole("heading", { name: /hola,/i })).toBeDefined());
  await user.click(screen.getByRole("link", { name: modulo }));
  await waitFor(() => expect(screen.queryByText(/cargando información/i)).toBeNull());
}

/** Códigos ofrecidos por un combo de conceptos, sin la opción vacía del placeholder. */
const codigosDe = (select) =>
  within(select)
    .getAllByRole("option")
    .map((option) => option.value)
    .filter(Boolean);

describe("conceptos de los combos operativos", () => {
  let user;

  beforeEach(() => {
    user = userEvent.setup();
    resetTaxConceptsCache();
  });

  afterEach(() => {
    cleanup();
    sessionStorage.clear();
    window.history.pushState({}, "", "/");
  });

  it("la nueva solicitud de exención ofrece los conceptos del catálogo", async () => {
    await entrar(user, "mrivas", /^exenciones$/i);
    await user.click(await screen.findByRole("button", { name: /nueva solicitud/i }));

    const dialog = await screen.findByRole("dialog");
    const combo = within(dialog).getByLabelText(/concepto/i);

    await waitFor(() => expect(codigosDe(combo).length).toBeGreaterThan(0));
    const { taxConfigService } = await import("../services/rentasService.js");
    const catalogo = (await taxConfigService.concepts()).map((c) => c.code);

    expect(codigosDe(combo)).toEqual(catalogo);
  });

  it("no ofrece un concepto dado de baja", async () => {
    const { taxConfigService } = await import("../services/rentasService.js");
    const todos = await taxConfigService.concepts({ onlyActive: false });
    const baja = todos.find((c) => c.status !== "ACTIVE");
    expect(baja).toBeDefined();

    await entrar(user, "mrivas", /^exenciones$/i);
    await user.click(await screen.findByRole("button", { name: /nueva solicitud/i }));

    const dialog = await screen.findByRole("dialog");
    const combo = within(dialog).getByLabelText(/concepto/i);

    await waitFor(() => expect(codigosDe(combo).length).toBeGreaterThan(0));
    expect(codigosDe(combo)).not.toContain(baja.code);
  });

  it("registra la solicitud con un concepto elegido del catálogo", async () => {
    await entrar(user, "mrivas", /^exenciones$/i);
    await user.click(await screen.findByRole("button", { name: /nueva solicitud/i }));

    const dialog = await screen.findByRole("dialog");
    const combo = within(dialog).getByLabelText(/concepto/i);
    await waitFor(() => expect(codigosDe(combo).length).toBeGreaterThan(0));

    await user.selectOptions(within(dialog).getByLabelText(/ciudadano/i), codigoDelPrimerCiudadano(dialog));
    await user.selectOptions(combo, codigosDe(combo)[0]);
    await user.type(within(dialog).getByLabelText(/porcentaje solicitado/i), "50");
    await user.type(within(dialog).getByLabelText(/vigente desde/i), "2027-01-01");
    await user.type(within(dialog).getByLabelText(/vigente hasta/i), "2027-12-31");
    await user.type(within(dialog).getByLabelText(/motivo/i), "Situación socioeconómica");
    await user.click(within(dialog).getByRole("button", { name: /registrar solicitud/i }));

    expect(await screen.findByText(/solicitud registrada/i)).toBeDefined();
  });

  it("la liquidación y su generación masiva usan el mismo catálogo", async () => {
    await entrar(user, "mrivas", /^liquidaciones$/i);

    await user.click(await screen.findByRole("button", { name: /nueva liquidación/i }));
    const nueva = await screen.findByRole("dialog");
    const comboNueva = within(nueva).getByLabelText(/concepto/i);
    await waitFor(() => expect(codigosDe(comboNueva).length).toBeGreaterThan(0));
    const enNueva = codigosDe(comboNueva);
    await user.click(within(nueva).getByRole("button", { name: /cancelar/i }));

    await user.click(await screen.findByRole("button", { name: /generación masiva/i }));
    const masiva = await screen.findByRole("dialog");
    const comboMasiva = within(masiva).getByLabelText(/concepto/i);
    await waitFor(() => expect(codigosDe(comboMasiva).length).toBeGreaterThan(0));

    const { taxConfigService } = await import("../services/rentasService.js");
    const catalogo = (await taxConfigService.concepts()).map((c) => c.code);

    expect(enNueva).toEqual(catalogo);
    expect(codigosDe(comboMasiva)).toEqual(catalogo);
  });

  it("ningún combo ofrece un código que el catálogo no tenga", async () => {
    const { taxConfigService } = await import("../services/rentasService.js");
    const catalogo = (await taxConfigService.concepts()).map((c) => c.code);

    await entrar(user, "mrivas", /^exenciones$/i);
    await user.click(await screen.findByRole("button", { name: /nueva solicitud/i }));
    const dialog = await screen.findByRole("dialog");
    const combo = within(dialog).getByLabelText(/concepto/i);
    await waitFor(() => expect(codigosDe(combo).length).toBeGreaterThan(0));

    codigosDe(combo).forEach((codigo) => expect(catalogo).toContain(codigo));
  });
});

/** Primer ciudadano del padrón, para completar el formulario sin fijar un id. */
function codigoDelPrimerCiudadano(dialog) {
  return within(within(dialog).getByLabelText(/ciudadano/i))
    .getAllByRole("option")
    .map((option) => option.value)
    .filter(Boolean)[0];
}

describe("catálogo de conceptos del servicio", () => {
  it("devuelve sólo los conceptos activos por defecto", async () => {
    const { taxConfigService } = await import("../services/rentasService.js");

    const activos = await taxConfigService.concepts();
    const todos = await taxConfigService.concepts({ onlyActive: false });

    expect(activos.length).toBeGreaterThan(0);
    expect(activos.every((c) => c.status === "ACTIVE")).toBe(true);
    expect(todos.length).toBeGreaterThan(activos.length);
  });

  it("consulta el catálogo real cuando no se usan mocks", async () => {
    vi.resetModules();
    vi.stubEnv("VITE_USE_MOCKS", "false");
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          content: [
            { id: 1, code: "TASA_SERVICIOS", name: "Tasa de servicios generales", type: "FEE", active: true },
            { id: 2, code: "CARGO_ESTADIA", name: "Cargo por estadía", type: "CHARGE", active: false },
          ],
        }),
        { status: 200, headers: { "content-type": "application/json" } },
      ),
    );
    vi.stubGlobal("fetch", fetchMock);

    const { taxConfigService } = await import("../services/rentasService.js");
    const conceptos = await taxConfigService.concepts();

    expect(fetchMock.mock.calls[0][0]).toContain("/api/v1/tax-concepts");
    expect(conceptos.map((c) => c.code)).toEqual(["TASA_SERVICIOS"]);

    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
    vi.resetModules();
  });
});
