import { describe, expect, it } from "vitest";
import { actionLabelFor, entityLabelFor, formatDate, labelFor, todayInFiscalTimezone } from "./format.js";

describe("formatDate", () => {
  it("keeps a date-only value on the same civil day", () => {
    expect(formatDate("2026-09-15")).toBe("15/09/2026");
  });

  it("uses the fiscal day when UTC has already rolled over", () => {
    expect(todayInFiscalTimezone(new Date("2026-11-02T00:30:00.000Z"))).toBe("2026-11-01");
  });

  it("presents backend audit enums with friendly labels", () => {
    expect(labelFor("ROLE_CASHIER")).toBe("Caja");
    expect(actionLabelFor("PAYMENT_ALLOCATED")).toBe("Pago imputado");
    expect(entityLabelFor("PaymentAllocation")).toBe("Imputación de pago");
    expect(labelFor("CANCELLED")).toBe("Anulada");
  });
});
