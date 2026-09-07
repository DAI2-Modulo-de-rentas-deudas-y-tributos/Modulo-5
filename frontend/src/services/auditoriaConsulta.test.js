import { describe, expect, it } from "vitest";
import { auditService } from "./rentasService.js";

/**
 * Mejoras de consulta de Auditoría. En archivo propio: varias ramas amplían
 * `rentasService.test.js` y anexar al final del mismo archivo choca al mergear.
 */
describe("ficha del contribuyente con liquidaciones", () => {
  it("suma las liquidaciones al legajo, que es donde empieza el circuito", async () => {
    const file = await auditService.taxpayerFile(123);

    expect(file.settlements.length).toBeGreaterThan(0);
    expect(file.settlements.every((s) => s.taxpayerId === 123)).toBe(true);
  });

  it("las devuelve de la más reciente a la más antigua", async () => {
    const file = await auditService.taxpayerFile(123);
    const fechas = file.settlements.map((s) => new Date(s.createdAt).getTime());

    expect([...fechas].sort((a, b) => b - a)).toEqual(fechas);
  });

  it("cada liquidación trae el nombre del concepto y la versión con la que se calculó", async () => {
    const file = await auditService.taxpayerFile(123);
    const liquidacion = file.settlements.find((s) => s.id === 7001);

    expect(liquidacion.conceptName).toBe("Tasa de servicios generales");
    // Las del dataset son anteriores al sello de versión; la ficha lo muestra como "—".
    expect(liquidacion.conceptVersion).toBeUndefined();
  });

  it("el legajo mantiene los cuatro frentes que ya tenía", async () => {
    const file = await auditService.taxpayerFile(123);

    expect(file.debts.length).toBeGreaterThan(0);
    expect(file.payments.length).toBeGreaterThan(0);
    expect(Array.isArray(file.plans)).toBe(true);
    expect(Array.isArray(file.exemptions)).toBe(true);
  });
});

describe("versiones de un concepto", () => {
  it("el historial llega completo y con sus parámetros", async () => {
    const concepto = await auditService.conceptDetail("TASA_SERVICIOS");

    expect(concepto.versions.length).toBe(3);
    // La vigente guarda las reglas con las que se liquida hoy.
    const vigente = concepto.versions.find((v) => v.status === "ACTIVE");
    expect(vigente.rate).toBe(2);
    expect(vigente.calculationType).toBe("PORCENTAJE");
  });

  it("las versiones viejas no guardan parámetros y eso se distingue", async () => {
    const concepto = await auditService.conceptDetail("TASA_SERVICIOS");
    const antigua = concepto.versions.find((v) => v.version === 1);

    expect(antigua.calculationType).toBeUndefined();
    // Pero sí queda registrado qué se cambió y quién.
    expect(antigua.note).toBeTruthy();
    expect(antigua.user).toBeTruthy();
  });
});
