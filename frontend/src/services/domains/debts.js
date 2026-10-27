import { ApiError, request } from "../apiClient.js";
import { round2 } from "./shared.js";

export const debtService = {
  async detail(id) {
    const debt = await request(`/api/v1/debts/${id}`);
    let source = null;
    let sourceError = null;
    if (debt.externalObligationId) {
      try { source = await request(`/api/v1/external-obligations/${debt.externalObligationId}`); }
      catch (error) { sourceError = error.message; }
    }
    return { ...debt, source, sourceError };
  },
  async list({ taxpayerId = "", status = "", originType = "" } = {}) {
    const params = new URLSearchParams({ taxpayerId, status, originType });
    return request(`/api/v1/debts?${params}`);
  },

  /** Estado de cuenta consolidado de un contribuyente. */
  async accountStatement(taxpayerId) {
    return request(`/api/v1/taxpayers/${taxpayerId}/account-statement`);
  },

  /** Informa la deuda vencida a Desarrollo Social (evento overdueDebt → M8). */
  async reportOverdue(debtId) {
    throw new ApiError("El reporte outbound de deuda vencida depende del contrato M8 pendiente.", 501, null, "M8_OUTBOUND_PENDING");
  },

  async previewLateCharge(debtId, calculationDate) {
    return request(`/api/v1/debts/${debtId}/late-charge-preview`, { method: "POST", body: { calculationDate } });
  },

  async applyLateCharge(debtId, calculationDate) {
    return request(`/api/v1/debts/${debtId}/late-charges`, { method: "POST", body: { calculationDate } });
  },
};

export const administrationService = {
  async processDueDates(processingDate) {
    return request("/api/v1/administration/process-due-dates", { method: "POST", body: { processingDate } });
  },
};

export const debtAdjustmentService = {
  async list({ status = "", debtId = "" } = {}) {
    const params = new URLSearchParams({ status, debtId });
    const rows = await request(`/api/v1/debt-adjustments?${params}`);
    return Promise.all(rows.map(async (row) => {
      if (row.status !== "PENDING_APPROVAL") return row;
      const debt = await request(`/api/v1/debts/${row.debtId}`);
      const delta = row.type === "DISCOUNT" ? -Number(row.amount) : Number(row.amount);
      return {
        ...row,
        previousAmount: Number(debt.outstandingAmount),
        newAmount: round2(Number(debt.outstandingAmount) + delta),
      };
    }));
  },

  /** RequestDebtAdjustmentRequest: el analista propone el cambio con su motivo. */
  async request({ debtId, newAmount, newDueDate, reason, requestedBy }) {
    const debt = await request(`/api/v1/debts/${debtId}`);
    if (newDueDate && newDueDate !== debt.dueDate) {
      throw new ApiError("El backend real no admite ajustar el vencimiento.", 501, null, "DUE_DATE_ADJUSTMENT_UNSUPPORTED");
    }
    const delta = Number(newAmount) - Number(debt.outstandingAmount);
    if (!Number.isFinite(delta) || delta === 0) throw new ApiError("El ajuste debe cambiar el importe.", 400);
    return request("/api/v1/adjustments", {
      method: "POST",
      body: { debtId, type: delta < 0 ? "DISCOUNT" : "SURCHARGE", amount: Math.abs(delta), reason },
    });
  },

  /** El Supervisor autoriza y aplica el cambio, o rechaza la solicitud. */
  async resolve({ adjustmentId, status, resolvedBy, resolverRole, reason }) {
    const action = status === "REJECTED" ? "reject" : "approve";
    return request(`/api/v1/adjustments/${adjustmentId}/${action}`, { method: "POST", body: action === "reject" ? { reason } : { observation: reason ?? null } });
  },

};
