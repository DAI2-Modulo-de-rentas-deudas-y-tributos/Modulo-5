import { afterEach, describe, expect, it, vi } from "vitest";
import { settlementService } from "./rentasService.js";
import { instalarBackendFalso, pagina } from "../__tests__/fixtures/backendFalso.js";

/**
 * Liquidación masiva. Quién queda alcanzado y con qué importe lo decide el backend;
 * el cliente orquesta las tres llamadas que hacen falta y separa lo liquidable de lo
 * que quedó en error para que la pantalla pueda mostrarlo por separado.
 */

const CONCEPTO = pagina([{ id: 1, code: "TASA_SERVICIOS", name: "Tasa de servicios generales", type: "FEE", active: true }]);
const PADRON = pagina([
  { id: 101, taxpayerType: "CITIZEN", dni: "40111222", displayName: "Juan Pérez", status: "ACTIVE" },
  { id: 102, taxpayerType: "ORGANIZATION", cuit: "30-71234567-8", displayName: "Comercial ABC", status: "ACTIVE" },
]);

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("previsualización del lote", () => {
  it("resuelve el concepto y el padrón antes de crear la corrida", async () => {
    const backend = instalarBackendFalso({
      "GET /api/v1/tax-concepts": CONCEPTO,
      "GET /api/v1/taxpayers": PADRON,
      "POST /api/v1/liquidation-runs": { id: 55 },
      "POST /api/v1/liquidation-runs/{id}/preview": { run: { estimatedTotalAmount: 40000 }, items: [] },
    });

    await settlementService.previewBatch({ conceptCode: "TASA_SERVICIOS", period: "2026-09", baseAmount: 20000, dueDate: "2026-09-30" });

    const alta = backend.llamadas.find((l) => l.ruta === "/api/v1/liquidation-runs" && l.metodo === "POST");
    expect(alta.cuerpo.taxConceptId).toBe(1);
    expect(alta.cuerpo.period).toBe("2026-09");
    // Un ítem por contribuyente del padrón, con la base imponible pedida.
    expect(alta.cuerpo.items).toEqual([
      { taxpayerId: 101, taxableBase: 20000 },
      { taxpayerId: 102, taxableBase: 20000 },
    ]);
  });

  it("separa lo liquidable de lo que el backend marcó en error", async () => {
    instalarBackendFalso({
      "GET /api/v1/tax-concepts": CONCEPTO,
      "GET /api/v1/taxpayers": PADRON,
      "POST /api/v1/liquidation-runs": { id: 55 },
      "POST /api/v1/liquidation-runs/{id}/preview": {
        run: { estimatedTotalAmount: 20000 },
        items: [
          { taxpayerId: 101, status: "PENDING", amount: 20000 },
          { taxpayerId: 102, status: "ERROR", message: "Ya tiene liquidación del período" },
        ],
      },
    });

    const preview = await settlementService.previewBatch({ conceptCode: "TASA_SERVICIOS", period: "2026-09", baseAmount: 20000, dueDate: "2026-09-30" });

    expect(preview.items).toHaveLength(1);
    expect(preview.errors).toHaveLength(1);
    expect(preview.totals).toMatchObject({ toGenerate: 1, skipped: 1, amount: 20000 });
  });

  it("acota el padrón por tipo de contribuyente cuando se pide", async () => {
    const backend = instalarBackendFalso({
      "GET /api/v1/tax-concepts": CONCEPTO,
      "GET /api/v1/taxpayers": PADRON,
      "POST /api/v1/liquidation-runs": { id: 55 },
      "POST /api/v1/liquidation-runs/{id}/preview": { run: {}, items: [] },
    });

    await settlementService.previewBatch({
      conceptCode: "TASA_SERVICIOS",
      period: "2026-09",
      baseAmount: 20000,
      dueDate: "2026-09-30",
      taxpayerType: "ORGANIZATION",
    });

    const padron = backend.llamadas.find((l) => l.ruta === "/api/v1/taxpayers");
    expect(padron).toBeDefined();
  });

  it("falla si el concepto no existe en vez de liquidar con uno inventado", async () => {
    instalarBackendFalso({ "GET /api/v1/tax-concepts": pagina([]) });

    await expect(
      settlementService.previewBatch({ conceptCode: "NO_EXISTE", period: "2026-09", baseAmount: 20000, dueDate: "2026-09-30" }),
    ).rejects.toMatchObject({ status: 404 });
  });
});
