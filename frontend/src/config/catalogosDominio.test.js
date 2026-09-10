import { describe, it, expect } from "vitest";
import {
  PAYMENT_METHODS,
  PAYMENT_ORIGINS,
  DEBT_ORIGIN_TYPES,
  EXTERNAL_OBLIGATION_TYPES,
  labelOf,
} from "./catalogosDominio.js";

/**
 * Estas pruebas son el contrato con el backend: si allá cambia un enum, acá falla
 * y avisa, en vez de que el desajuste aparezca como un filtro rechazado en runtime.
 */
describe("catálogos de dominio", () => {
  it("usa exactamente los medios de pago del backend", () => {
    expect(PAYMENT_METHODS.map((o) => o.value)).toEqual(["CASH", "CARD", "TRANSFER", "DIGITAL_WALLET"]);
  });

  it("usa exactamente los orígenes de pago del backend", () => {
    expect(PAYMENT_ORIGINS.map((o) => o.value)).toEqual(["CASHIER", "ELECTRONIC", "EXTERNAL"]);
  });

  it("separa el origen de la deuda del tipo de obligación externa", () => {
    expect(DEBT_ORIGIN_TYPES.map((o) => o.value)).toEqual(["LIQUIDATION", "EXTERNAL_OBLIGATION"]);
    expect(EXTERNAL_OBLIGATION_TYPES.map((o) => o.value)).toEqual([
      "PERMIT_FEE",
      "COMMERCIAL_FINE",
      "TRAFFIC_INFRACTION",
    ]);
  });

  it("no conserva ningún valor inventado por el frontend", () => {
    const todos = [...PAYMENT_METHODS, ...PAYMENT_ORIGINS, ...DEBT_ORIGIN_TYPES, ...EXTERNAL_OBLIGATION_TYPES];
    for (const opcion of todos) {
      expect(opcion.value).toMatch(/^[A-Z_]+$/);
    }
    const valores = todos.map((o) => o.value);
    expect(valores).not.toContain("SETTLEMENT");
    expect(valores).not.toContain("EFECTIVO");
    expect(valores).not.toContain("TARJETA");
  });

  it("devuelve el valor crudo si no conoce la etiqueta", () => {
    expect(labelOf(PAYMENT_METHODS, "CASH")).toBe("Efectivo");
    expect(labelOf(PAYMENT_METHODS, "DESCONOCIDO")).toBe("DESCONOCIDO");
  });
});
