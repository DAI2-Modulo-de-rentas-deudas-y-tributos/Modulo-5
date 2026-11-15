import { afterEach, describe, expect, it, vi } from "vitest";
import { actionLabelFor, entityLabelFor, formatDate, labelFor, todayFiscalDate } from "./format.js";

afterEach(() => vi.useRealTimers());

describe("formatDate", () => {
  it("keeps a date-only value on the same civil day", () => {
    expect(formatDate("2026-09-15")).toBe("15/09/2026");
  });

  it("presents backend audit enums with friendly labels", () => {
    expect(labelFor("ROLE_CASHIER")).toBe("Caja");
    expect(actionLabelFor("PAYMENT_ALLOCATED")).toBe("Pago imputado");
    expect(entityLabelFor("PaymentAllocation")).toBe("Imputación de pago");
    expect(labelFor("CANCELLED")).toBe("Anulada");
  });

  it("uses the Buenos Aires civil date when UTC has already crossed midnight", () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-11-05T01:13:00Z"));

    expect(todayFiscalDate()).toBe("2026-11-04");
  });
});
