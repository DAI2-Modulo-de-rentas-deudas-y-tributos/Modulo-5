import { afterEach, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import BoletaDocument from "./BoletaDocument.jsx";

afterEach(cleanup);

it("muestra el motivo de una boleta anulada y no vuelve a exponer su código de pago", () => {
  render(<BoletaDocument bill={{
    id: 42,
    debtId: 8,
    barcode: "BILL-OLD-42",
    status: "CANCELLED",
    cancellationReason: "DEBT_BALANCE_CHANGED",
    amount: 100,
    dueDate: "2026-11-30",
    issuedAt: "2026-11-01T10:00:00Z",
  }} taxpayer={{ name: "Contribuyente de prueba", documentType: "DNI", document: "12345678" }} />);

  expect(screen.getByText("Boleta anulada")).toBeTruthy();
  expect(screen.getByText("Anulada porque cambió el saldo de una o más deudas.")).toBeTruthy();
  expect(screen.getByText(/no revoca automáticamente códigos ya presentados a proveedores de cobro externos/)).toBeTruthy();
  expect(screen.queryByText("BILL-OLD-42")).toBeNull();
});
