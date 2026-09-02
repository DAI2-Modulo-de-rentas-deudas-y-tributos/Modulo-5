import { describe, expect, it } from "vitest";
import { actionLabelFor, entityLabelFor, formatDate, labelFor } from "./format.js";

describe("formatDate", () => {
  it("keeps a date-only value on the same civil day", () => {
    expect(formatDate("2026-09-15")).toBe("15/09/2026");
  });

  it("presents backend audit enums with friendly labels", () => {
    expect(labelFor("ROLE_CASHIER")).toBe("Caja");
    expect(actionLabelFor("PAYMENT_ALLOCATED")).toBe("Pago imputado");
    expect(entityLabelFor("PaymentAllocation")).toBe("Imputación de pago");
  });
});
