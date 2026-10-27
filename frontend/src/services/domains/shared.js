import { ApiError, request } from "../apiClient.js";

export const round2 = (value) => Math.round(value * 100) / 100;

export async function allPages(path) {
  const url = new URL(path, "http://rentas.local");
  url.searchParams.set("size", "100");
  const rows = [];
  for (let page = 0; ; page += 1) {
    url.searchParams.set("page", String(page));
    const result = await request(`${url.pathname}${url.search}`);
    if (!Array.isArray(result)) throw new ApiError("El servidor devolvió una colección inválida.", 502);
    rows.push(...result);
    if (!result.page || result.page.last || page + 1 >= result.page.totalPages) return rows;
    if (result.page.number !== page || result.length === 0) {
      throw new ApiError("No se pudo completar la consulta paginada.", 502);
    }
  }
}

export const openDebts = (debts) => debts.filter((debt) => Number(debt.outstandingAmount) > 0 && !["CANCELLED", "SETTLED"].includes(debt.status));

export const debtTotals = (debts) => {
  const pending = openDebts(debts);
  return {
    pendingCount: pending.length,
    outstanding: round2(pending.reduce((sum, debt) => sum + Number(debt.outstandingAmount), 0)),
    overdue: round2(pending.filter((debt) => debt.overdue || debt.status === "OVERDUE").reduce((sum, debt) => sum + Number(debt.outstandingAmount), 0)),
  };
};

export const paymentReceipt = (payment, taxpayer) => ({
  ...payment,
  paymentId: payment.id,
  taxpayer,
  issuedAt: payment.paidAt,
  cashier: payment.registeredBy ? { fullName: payment.registeredBy } : null,
  // El importe sin imputar del pago no es el saldo de una deuda.
  remainingBalance: null,
  settled: false,
});
