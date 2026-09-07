import { describe, expect, it } from "vitest";
import { debtService, settlementService } from "./rentasService.js";

/**
 * Generación masiva. La previsualización distingue tres grupos: lo que se genera,
 * lo que queda afuera y lo que se genera pero exige atención.
 */
describe("liquidaciones masivas", () => {
  it("previsualiza el lote con el descuento calculado por contribuyente", async () => {
    const preview = await settlementService.previewBatch({
      conceptCode: "TASA_SERVICIOS",
      period: "2027-01",
      baseAmount: 100000,
      dueDate: "2027-01-15",
    });

    expect(preview.totals.toGenerate).toBe(preview.items.length);
    // Juan Pérez tiene beneficio social del 50% sobre TASA_SERVICIOS.
    const conBeneficio = preview.items.find((i) => i.taxpayerId === 123);
    expect(conBeneficio.discountPercentage).toBe(50);
    expect(conBeneficio.amount).toBe(50000);
    // El resto se liquida por la base completa.
    const sinBeneficio = preview.items.find((i) => i.taxpayerId === 78);
    expect(sinBeneficio.amount).toBe(100000);
  });

  it("deja afuera a quien ya tiene liquidación de ese concepto y período", async () => {
    // La liquidación 7001 es de Juan Pérez, TASA_SERVICIOS, 2026-08.
    const preview = await settlementService.previewBatch({
      conceptCode: "TASA_SERVICIOS",
      period: "2026-08",
      baseAmount: 100000,
      dueDate: "2026-09-15",
    });

    const omitido = preview.errors.find((e) => e.taxpayerId === 123);
    expect(omitido.reason).toMatch(/ya tiene la liquidación #7001/i);
    expect(preview.items.some((i) => i.taxpayerId === 123)).toBe(false);
  });

  it("liquida igual al bloqueado y al fallecido, pero los marca", async () => {
    const preview = await settlementService.previewBatch({
      conceptCode: "ABL",
      period: "2027-02",
      baseAmount: 50000,
      dueDate: "2027-02-15",
    });

    // La obligación existe aunque M1 haya informado bloqueo o fallecimiento.
    expect(preview.items.some((i) => i.taxpayerId === 145)).toBe(true);
    expect(preview.items.some((i) => i.taxpayerId === 190)).toBe(true);
    expect(preview.warnings.map((w) => w.taxpayerId)).toEqual(
      expect.arrayContaining([145, 190]),
    );
  });

  it("acota el lote por tipo de contribuyente", async () => {
    const preview = await settlementService.previewBatch({
      conceptCode: "ABL",
      period: "2027-03",
      baseAmount: 50000,
      dueDate: "2027-03-15",
      taxpayerType: "ORGANIZATION",
    });

    expect(preview.items.every((i) => i.taxpayerType === "ORGANIZATION")).toBe(true);
  });

  it("genera el lote en borrador, sin deuda todavía", async () => {
    const antes = (await debtService.list()).length;

    const result = await settlementService.generateBatch({
      conceptCode: "PATENTE",
      period: "2027-04",
      baseAmount: 40000,
      dueDate: "2027-04-15",
    });

    expect(result.generated.length).toBeGreaterThan(0);
    expect(result.generated.every((s) => s.status === "DRAFT")).toBe(true);
    // El borrador no genera deuda: eso pasa recién al emitir.
    expect((await debtService.list()).length).toBe(antes);
  });

  it("no genera dos veces el mismo lote", async () => {
    const params = {
      conceptCode: "ABL",
      period: "2027-05",
      baseAmount: 30000,
      dueDate: "2027-05-15",
    };
    const primero = await settlementService.generateBatch(params);
    const segundo = await settlementService.previewBatch(params);

    expect(segundo.totals.toGenerate).toBe(0);
    expect(segundo.errors.length).toBe(primero.generated.length);
  });

  it("falla si no queda nadie alcanzado", async () => {
    const params = {
      conceptCode: "ABL",
      period: "2027-06",
      baseAmount: 30000,
      dueDate: "2027-06-15",
    };
    await settlementService.generateBatch(params);

    await expect(settlementService.generateBatch(params)).rejects.toThrow(
      /no hay contribuyentes alcanzados/i,
    );
  });
});
