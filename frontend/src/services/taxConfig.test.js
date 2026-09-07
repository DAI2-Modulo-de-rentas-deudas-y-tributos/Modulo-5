import { afterEach, describe, expect, it, vi } from "vitest";
import { taxConfigService } from "./rentasService.js";
import { instalarBackendFalso, pagina } from "../__tests__/fixtures/backendFalso.js";

/**
 * Configuración de tributos.
 *
 * El backend guarda conceptos y configuraciones por separado; la pantalla necesita
 * verlos como un concepto con su versión vigente, la que espera aprobación y el
 * borrador. Esa composición es del cliente, y es lo que se prueba acá. El flujo de
 * aprobación (quién puede, en qué orden) lo gobierna y lo prueba el backend.
 */

const CONCEPTOS = pagina([
  { id: 1, code: "TASA_SERVICIOS", name: "Tasa de servicios generales", type: "FEE", active: true },
  { id: 2, code: "ABL", name: "Alumbrado, barrido y limpieza", type: "FEE", active: false },
]);

const CONFIGURACIONES = pagina([
  { id: 10, taxConceptId: 1, version: 1, status: "ACTIVE", calculationType: "PERCENTAGE", rate: 2, validFrom: "2026-01-01" },
  { id: 11, taxConceptId: 1, version: 2, status: "DRAFT", calculationType: "PERCENTAGE", rate: 2.5, validFrom: "2027-01-01" },
  { id: 12, taxConceptId: 2, version: 1, status: "PENDING_APPROVAL", calculationType: "FIXED", rate: 0, validFrom: "2026-06-01" },
]);

const backendConCatalogo = (extra = {}) =>
  instalarBackendFalso({
    "GET /api/v1/tax-concepts": CONCEPTOS,
    "GET /api/v1/tax-configurations": CONFIGURACIONES,
    ...extra,
  });

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("composición del catálogo", () => {
  it("cada concepto expone su versión vigente, la pendiente y el borrador", async () => {
    backendConCatalogo();

    const conceptos = await taxConfigService.list();
    const tasa = conceptos.find((c) => c.code === "TASA_SERVICIOS");
    const abl = conceptos.find((c) => c.code === "ABL");

    expect(tasa.activeVersion.version).toBe(1);
    expect(tasa.draftVersion.version).toBe(2);
    expect(tasa.pendingVersion).toBeNull();
    expect(abl.pendingVersion.version).toBe(1);
    expect(abl.activeVersion).toBeNull();
  });

  it("la ficha trae el historial completo del concepto", async () => {
    backendConCatalogo();

    const ficha = await taxConfigService.detail("TASA_SERVICIOS");

    expect(ficha.versions).toHaveLength(2);
    expect(ficha.versions.map((v) => v.version)).toEqual([1, 2]);
  });

  it("un concepto inexistente falla en vez de devolver una ficha vacía", async () => {
    backendConCatalogo();

    await expect(taxConfigService.detail("NO_EXISTE")).rejects.toMatchObject({ status: 404 });
  });

  it("el combo de conceptos ofrece sólo los activos por defecto", async () => {
    backendConCatalogo();

    const activos = await taxConfigService.concepts();
    const todos = await taxConfigService.concepts({ onlyActive: false });

    expect(activos.map((c) => c.code)).toEqual(["TASA_SERVICIOS"]);
    expect(todos.length).toBeGreaterThan(activos.length);
  });
});

describe("propuesta de una versión nueva", () => {
  it("resuelve el concepto por código y manda su id", async () => {
    const backend = backendConCatalogo({ "POST /api/v1/tax-configurations": { id: 20, status: "DRAFT" } });

    await taxConfigService.proposeVersion({
      code: "TASA_SERVICIOS",
      calculationType: "PORCENTAJE",
      rate: 3,
      minimumAmount: 1000,
      maximumAmount: 90000,
      validFrom: "2027-01-01",
      requestedBy: "mrivas",
    });

    const alta = backend.llamadas.find((l) => l.metodo === "POST");
    expect(alta.cuerpo.taxConceptId).toBe(1);
    expect(alta.cuerpo.calculationType).toBe("PERCENTAGE");
  });

  it("en cálculo fijo el importe sale del mínimo, no queda en cero", async () => {
    // El formulario no pide alícuota cuando el cálculo es fijo.
    const backend = backendConCatalogo({ "POST /api/v1/tax-configurations": { id: 21, status: "DRAFT" } });

    await taxConfigService.proposeVersion({
      code: "TASA_SERVICIOS",
      calculationType: "FIJO",
      rate: undefined,
      minimumAmount: 45000,
      validFrom: "2027-01-01",
      requestedBy: "mrivas",
    });

    const alta = backend.llamadas.find((l) => l.metodo === "POST");
    expect(alta.cuerpo.fixedAmount).toBe(45000);
  });
});
