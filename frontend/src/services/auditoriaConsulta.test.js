import { afterEach, describe, expect, it, vi } from "vitest";
import { auditService } from "./rentasService.js";
import { instalarBackendFalso, pagina } from "../__tests__/fixtures/backendFalso.js";

/**
 * Consulta del auditor. Es un área de sólo lectura: lo que se prueba es que cada
 * pantalla pida el recurso real que le corresponde —las rutas heredadas de auditoría
 * las traduce el adaptador— y que la respuesta llegue con la forma que la tabla
 * espera, sin completar huecos con datos inventados.
 */

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("legajo del contribuyente", () => {
  it("se resuelve contra el padrón real", async () => {
    const backend = instalarBackendFalso({
      "GET /api/v1/taxpayers/{id}": { id: 123, taxpayerType: "CITIZEN", dni: "40111222", displayName: "Juan Pérez", status: "ACTIVE" },
    });

    const legajo = await auditService.taxpayerFile(123);

    expect(backend.llamadas[0].ruta).toBe("/api/v1/taxpayers/123");
    expect(legajo.name).toBe("Juan Pérez");
  });

  it("las liquidaciones se leen del recurso de liquidaciones", async () => {
    const backend = instalarBackendFalso({
      "GET /api/v1/liquidations": pagina([
        { id: 5001, taxpayerId: 123, taxConceptId: 1, finalAmount: 20000, period: "2026-09" },
      ]),
    });

    const liquidaciones = await auditService.settlements({ taxpayer: "123" });

    expect(backend.llamadas[0].ruta).toBe("/api/v1/liquidations");
    expect(liquidaciones).toHaveLength(1);
    // El backend no manda el nombre del concepto: se rotula por id, no se inventa.
    expect(liquidaciones[0].conceptName).toBe("Concepto #1");
  });

  it("no inventa filas cuando no hay liquidaciones", async () => {
    instalarBackendFalso({ "GET /api/v1/liquidations": pagina([]) });

    expect(await auditService.settlements({ taxpayer: "999" })).toEqual([]);
  });
});

describe("ficha de un concepto", () => {
  it("lo busca por código en el catálogo real", async () => {
    const backend = instalarBackendFalso({
      "GET /api/v1/tax-concepts": pagina([
        { id: 1, code: "TASA_SERVICIOS", name: "Tasa de servicios generales", type: "FEE", active: true },
      ]),
    });

    const concepto = await auditService.conceptDetail("TASA_SERVICIOS");

    expect(backend.llamadas[0].ruta).toBe("/api/v1/tax-concepts");
    expect(concepto.name).toBe("Tasa de servicios generales");
  });

  it("un código que el catálogo no tiene falla en vez de devolver una ficha vacía", async () => {
    instalarBackendFalso({ "GET /api/v1/tax-concepts": pagina([]) });

    await expect(auditService.conceptDetail("NO_EXISTE")).rejects.toMatchObject({ status: 404 });
  });
});

describe("indicadores", () => {
  it("se componen del resumen que publica el backend", async () => {
    const backend = instalarBackendFalso({
      "GET /api/v1/indicators/summary": {
        collection: { paymentCount: 3, confirmedAmount: 260 },
        debt: { openCount: 2, outstandingAmount: 160 },
        delinquency: { overdueDebtCount: 1, overdueAmount: 80, overduePercentage: 50 },
      },
    });

    const indicadores = await auditService.indicators({ from: "2026-09-01", to: "2026-09-30" });

    expect(backend.llamadas[0].ruta).toBe("/api/v1/indicators/summary");
    expect(indicadores.totalCollected).toBe(260);
    // La deuda pendiente excluye la vencida, que se informa aparte.
    expect(indicadores.pendingDebt).toBe(80);
    expect(indicadores.overdueDebt).toBe(80);
  });

  it("devuelve ceros, no huecos, cuando el resumen viene vacío", async () => {
    instalarBackendFalso({ "GET /api/v1/indicators/summary": {} });

    const indicadores = await auditService.indicators();

    expect(indicadores.totalCollected).toBe(0);
    expect(indicadores.byConcept).toEqual([]);
  });
});
