import { beforeEach, describe, expect, it, vi } from "vitest";
import { adaptApiRequest } from "./apiAdapters.js";

/**
 * Alta de versiones de configuración contra el backend real (SCRUM-288).
 *
 * La pantalla trabaja con los códigos internos en español y con los campos vacíos
 * que deja el formulario; el backend espera el enum CalculationType en inglés y
 * BigDecimal. Estas pruebas fijan la traducción sobre el path definitivo, que es el
 * que usa la pantalla: antes sólo se traducía en la rama del path legado y el POST
 * viajaba con PORCENTAJE / FIJO / IMPORTE_EXTERNO, que el backend rechaza con 400.
 */
describe("POST /api/v1/tax-configurations", () => {
  it.each([
    ["PORCENTAJE", "PERCENTAGE"],
    ["FIJO", "FIXED"],
    ["IMPORTE_EXTERNO", "EXTERNAL"],
  ])("traduce la forma de cálculo %s a %s", (interno, esperado) => {
    const { options } = adaptApiRequest("/api/v1/tax-configurations", {
      method: "POST",
      body: { taxConceptId: 1, calculationType: interno, validFrom: "2027-01-01" },
    });

    expect(options.body.calculationType).toBe(esperado);
  });

  it("traduce igual por el path legado y por el definitivo", () => {
    const body = { taxConceptId: 1, calculationType: "PORCENTAJE", rate: "2.5", validFrom: "2027-01-01" };
    const legado = adaptApiRequest("/api/v1/tax-config/TASA_SERVICIOS/versions", { method: "POST", body });
    const definitivo = adaptApiRequest("/api/v1/tax-configurations", { method: "POST", body });

    expect(definitivo.path).toBe(legado.path);
    expect(definitivo.options.body).toEqual(legado.options.body);
  });

  it("no vuelve a traducir un enum que ya viene en inglés", () => {
    const { options } = adaptApiRequest("/api/v1/tax-configurations", {
      method: "POST",
      body: { taxConceptId: 1, calculationType: "PERCENTAGE", validFrom: "2027-01-01" },
    });

    expect(options.body.calculationType).toBe("PERCENTAGE");
  });

  it("convierte a número los importes que el formulario deja vacíos", () => {
    const { options } = adaptApiRequest("/api/v1/tax-configurations", {
      method: "POST",
      body: {
        taxConceptId: 1,
        calculationType: "FIJO",
        rate: "",
        fixedAmount: "118000",
        minimumAmount: "",
        maximumAmount: "",
        validFrom: "2027-01-01",
        validUntil: "",
      },
    });

    // El backend recibe BigDecimal: un "" en un campo numérico también da 400.
    expect(options.body).toMatchObject({
      rate: 0,
      fixedAmount: 118000,
      minimumAmount: 0,
      maximumAmount: null,
      validUntil: null,
    });
  });

  it("deja intactas las consultas GET del mismo recurso", () => {
    const { path, options } = adaptApiRequest("/api/v1/tax-configurations?size=100");

    expect(path).toBe("/api/v1/tax-configurations?size=100");
    expect(options.body).toBeUndefined();
  });
});

describe("propuesta de versión en modo API", () => {
  beforeEach(() => {
    vi.resetModules();
    vi.unstubAllEnvs();
    vi.restoreAllMocks();
  });

  /** Devuelve el cuerpo JSON de la llamada que crea la configuración. */
  async function proponer(calculationType, extra = {}) {
    vi.stubEnv("VITE_USE_MOCKS", "false");
    const concepto = new Response(
      JSON.stringify({ content: [{ id: 7, code: "TASA_SERVICIOS", name: "Tasa", active: true }] }),
      { status: 200, headers: { "content-type": "application/json" } },
    );
    const creada = new Response(JSON.stringify({ id: 55, taxConceptId: 7, calculationType: "PERCENTAGE" }), {
      status: 201,
      headers: { "content-type": "application/json" },
    });
    const fetchMock = vi.fn().mockResolvedValueOnce(concepto).mockResolvedValueOnce(creada);
    vi.stubGlobal("fetch", fetchMock);

    const { taxConfigService } = await import("./rentasService.js");
    await taxConfigService.proposeVersion({
      code: "TASA_SERVICIOS",
      calculationType,
      rate: "",
      minimumAmount: "",
      maximumAmount: "",
      validFrom: "2027-01-01",
      validUntil: "2027-12-31",
      note: "Nueva versión",
      requestedBy: "jlopez",
      ...extra,
    });

    const [url, init] = fetchMock.mock.calls[1];
    return { url, body: JSON.parse(init.body) };
  }

  it("manda el enum en inglés en las tres formas de cálculo", async () => {
    expect((await proponer("PORCENTAJE", { rate: "2.5" })).body.calculationType).toBe("PERCENTAGE");
    expect((await proponer("FIJO", { minimumAmount: "118000" })).body.calculationType).toBe("FIXED");
    expect((await proponer("IMPORTE_EXTERNO")).body.calculationType).toBe("EXTERNAL");
  });

  it("no deja llegar campos vacíos ni el código en español al backend", async () => {
    const { url, body } = await proponer("PORCENTAJE", { rate: "2.5" });

    expect(url).toContain("/api/v1/tax-configurations");
    expect(body).toMatchObject({ taxConceptId: 7, calculationType: "PERCENTAGE", rate: 2.5, minimumAmount: 0 });
    expect(JSON.stringify(body)).not.toMatch(/PORCENTAJE|FIJO|IMPORTE_EXTERNO/);
    Object.values(body).forEach((value) => expect(value).not.toBe(""));
  });

  it("el cálculo fijo viaja con importe: el backend lo exige no nulo", async () => {
    const { body } = await proponer("FIJO", { minimumAmount: "118000" });

    expect(body.calculationType).toBe("FIXED");
    expect(body.fixedAmount).toBe(118000);
  });
});
