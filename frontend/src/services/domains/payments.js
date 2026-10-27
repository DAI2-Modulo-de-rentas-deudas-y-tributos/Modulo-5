import { request } from "../apiClient.js";
import { allPages } from "./shared.js";
import { taxpayerService } from "./taxpayers.js";

export const billService = {
  async list({ taxpayerId = "", status = "" } = {}) {
    const params = new URLSearchParams({ taxpayerId, status });
    return request(`/api/v1/bills?${params}`);
  },

  async search({ query = "" } = {}) {
    const params = new URLSearchParams({ query });
    return request(`/api/v1/bills/search?${params}`);
  },

  /** IssueBillRequest → BillResponse. El PDF vive en S3, nunca en la base. */
  async issue({ debtId }) {
    const debt = await request(`/api/v1/debts/${debtId}`);
    return request("/api/v1/bills", { method: "POST", body: { taxpayerId: debt.taxpayerId, debtIds: [Number(debtId)], dueDate: debt.dueDate } });
  },
};

export const paymentService = {
  async list({ taxpayerId = "", status = "", date = "", registeredBy = "" } = {}) {
    const params = new URLSearchParams({ taxpayerId, status, date, registeredBy });
    return request(`/api/v1/payments?${params}`);
  },

  /** Consulta completa para búsquedas de Caja por comprobante, persona u operación. */
  async listAll({ taxpayerId = "", status = "", date = "", registeredBy = "" } = {}) {
    const params = new URLSearchParams({ taxpayerId, status, date, registeredBy });
    return allPages(`/api/v1/payments?${params}`);
  },

  /**
   * RegisterPaymentRequest → PaymentResponse
   *
   * La API registra el medio de pago; el origen, el agente y la fecha los establece
   * el backend a partir de la sesión y del momento de la operación.
   */
  async register({ taxpayerId, debtId, amountPaid, method, idempotencyKey }) {
    return request("/api/v1/payments", {
      method: "POST",
      body: { taxpayerId, debtId, amountPaid, method },
      headers: idempotencyKey ? { "Idempotency-Key": idempotencyKey } : {},
    });
  },

  /** AllocatePaymentRequest → PaymentAllocationResponse (pagos sin imputar). */
  async allocate({ paymentId, debtId }) {
    const payment = await request(`/api/v1/payments/${paymentId}`);
    return request(`/api/v1/payments/${paymentId}/allocations`, {
      method: "POST",
      body: { debtId, amount: payment.unallocatedAmount },
    });
  },

  /** RequestPaymentReversalRequest → PaymentReversalResponse */
  async reverse({ paymentId, reason }) {
    return request(`/api/v1/payments/${paymentId}/reversal`, {
      method: "POST",
      body: { reason },
    });
  },

  /** El Cajero solicita la reversión; el pago permanece confirmado hasta su ejecución. */
  async requestReversal({ paymentId, reason }) {
    return request(`/api/v1/payments/${paymentId}/reversal-requests`, {
      method: "POST",
      body: { reason },
    });
  },
};

export const paymentReversalService = {
  async list({ status = "", from = "", to = "" } = {}) {
    const params = new URLSearchParams({ status, from, to, size: "100", sort: "requestedAt,desc" });
    const rows = await request(`/api/v1/payment-reversals?${params}`);
    return Promise.all(rows.map(async (reversal) => {
      const payment = await request(`/api/v1/payments/${reversal.paymentId}`);
      const taxpayer = await taxpayerService.getById(payment.taxpayerId);
      return { ...reversal, payment, taxpayer };
    }));
  },

  async detail(id) {
    const reversal = await request(`/api/v1/payment-reversals/${id}`);
    const [payment, allocationResult] = await Promise.all([
      request(`/api/v1/payments/${reversal.paymentId}`),
      request(`/api/v1/payments/${reversal.paymentId}/allocations`)
        .then((allocations) => ({ allocations, unavailable: false }))
        .catch((error) => {
          // El detalle de imputaciones tiene permisos más restrictivos que la
          // resolución de reversiones. No debe impedir que el Supervisor resuelva.
          if (error.status === 401 || error.status === 403) {
            return { allocations: [], unavailable: true };
          }
          throw error;
        }),
    ]);
    const { allocations, unavailable: allocationsUnavailable } = allocationResult;
    const [taxpayer, debts] = await Promise.all([
      taxpayerService.getById(payment.taxpayerId),
      Promise.all(
        [...new Set(allocations.map((allocation) => allocation.debtId).filter(Boolean))]
          .map((debtId) => request(`/api/v1/debts/${debtId}`).catch(() => null)),
      ),
    ]);
    const debtById = new Map(debts.filter(Boolean).map((debt) => [debt.id, debt]));
    return {
      ...reversal,
      payment,
      taxpayer,
      allocationsUnavailable,
      allocations: allocations.map((allocation) => ({
        ...allocation,
        debt: allocation.debtId ? debtById.get(allocation.debtId) ?? null : null,
      })),
    };
  },

  async approve({ id, observation = "" }) {
    return request(`/api/v1/payment-reversals/${id}/approve`, {
      method: "POST",
      body: { observation: observation || null },
    });
  },

  async reject({ id, reason }) {
    return request(`/api/v1/payment-reversals/${id}/reject`, {
      method: "POST",
      body: { reason },
    });
  },

  /** La ejecución es un paso separado y exclusivo de Personal de Rentas. */
  async execute(id) {
    return request(`/api/v1/payment-reversals/${id}/execute`, { method: "POST" });
  },
};

export const creditBalanceService = {
  async list({ taxpayerId = "", status = "" } = {}) {
    const params = new URLSearchParams({ taxpayerId, status });
    return request(`/api/v1/credit-balances?${params}`);
  },

  /** Deudas del contribuyente a las que se le puede aplicar el saldo. */
  async applicableDebts(creditId) {
    const credit = await request(`/api/v1/credit-balances/${creditId}`);
    return request(`/api/v1/taxpayers/${credit.taxpayerId}/debts?size=100`);
  },

  /**
   * ApplyCreditBalanceRequest. Total o parcial, pero nunca más que el saldo
   * disponible ni más que la deuda pendiente.
   */
  async apply({ creditId, debtId, amount, appliedBy }) {
    const application = await request(`/api/v1/credit-balances/${creditId}/applications`, {
      method: "POST",
      body: { debtId, amount },
    });
    // La respuesta confirma la aplicación pero no el saldo resultante: se relee la deuda
    // para poder decir en qué quedó, en vez de mostrar el importe aplicado como si fuera el saldo.
    const debt = await request(`/api/v1/debts/${debtId}`);
    return {
      ...application,
      appliedAmount: application.amount ?? amount,
      debt,
      debtSettled: Number(debt.outstandingAmount) === 0,
    };
  },
};

export const reconciliationService = {
  async importBatch(batchReference, items) {
    return request("/api/v1/payment-reconciliations/batches", { method: "POST", body: { batchReference, items } });
  },
  async observed() {
    return request("/api/v1/payment-reconciliations/observed?size=100");
  },
  async resolve(itemId, paymentId, reason) {
    return request(`/api/v1/payment-reconciliations/items/${itemId}/resolve`, { method: "POST", body: { paymentId, reason } });
  },
};

