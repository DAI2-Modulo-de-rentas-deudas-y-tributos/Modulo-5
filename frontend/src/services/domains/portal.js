import { request } from "../apiClient.js";
import { allPages } from "./shared.js";
import { paymentPlanService } from "./paymentPlans.js";
import { exemptionService } from "./exemptions.js";

export const portalService = {
  /** Resumen de la cuenta: lo que el ciudadano ve al entrar. */
  async accountSummary(taxpayerId) {
    const [summary, debts] = await Promise.all([
      request(`/api/v1/portal/${taxpayerId}/account-summary`),
      portalService.debts({ taxpayerId }),
    ]);
    const obligations = debts
      .filter((debt) => Number(debt.outstandingAmount) > 0)
      .sort((a, b) => String(a.dueDate).localeCompare(String(b.dueDate)));
    const next = obligations[0];
    return {
      ...summary,
      obligations,
      nextDueDate: next
        ? {
            debtId: next.id,
            conceptName: next.conceptName,
            amount: next.outstandingAmount,
            dueDate: next.dueDate,
            daysLeft: next.daysLeft,
            overdue: next.status === "OVERDUE",
          }
        : null,
      counts: { ...summary.counts, debts: obligations.length },
    };
  },

  /** Avisos de la portada: lo que exige atención, de lo más urgente a lo informativo. */
  async notices(taxpayerId) {
    return request(`/api/v1/portal/${taxpayerId}/notices`);
  },

  // --------------------------------------------------------------- Consultas

  async debts({ taxpayerId, status = "" }) {
    const params = new URLSearchParams({ status });
    const [debts, bills, concepts] = await Promise.all([
      request(`/api/v1/portal/${taxpayerId}/debts?${params}`),
      request(`/api/v1/portal/${taxpayerId}/bills`),
      allPages("/api/v1/tax-concepts"),
    ]);
    const conceptById = new Map(concepts.map((concept) => [concept.id, concept]));
    return debts.map((debt) => ({
      ...debt,
      conceptName: conceptById.get(debt.conceptId)?.name ?? debt.conceptName,
      billId: bills.find((bill) => bill.debtId === debt.id)?.id ?? null,
    }));
  },

  async bills({ taxpayerId, status = "" }) {
    const params = new URLSearchParams({ status });
    return request(`/api/v1/portal/${taxpayerId}/bills?${params}`);
  },

  async payments({ taxpayerId }) {
    return request(`/api/v1/portal/${taxpayerId}/payments`);
  },

  /** Detalle visible para el titular con las imputaciones de sus propias obligaciones. */
  async paymentDetail({ paymentId }) {
    const [payment, allocations] = await Promise.all([
      request(`/api/v1/payments/${paymentId}`),
      request(`/api/v1/payments/${paymentId}/allocations`),
    ]);
    return { ...payment, allocations };
  },

  async creditBalances({ taxpayerId }) {
    return request(`/api/v1/taxpayers/${taxpayerId}/credit-balances`);
  },

  async paymentPlans({ taxpayerId }) {
    const requests = await allPages(`/api/v1/taxpayers/${taxpayerId}/payment-plan-requests`);
    return Promise.all(requests.map(async (row) => {
      const [debts, plan, schedule] = await Promise.all([
        request(`/api/v1/payment-plan-requests/${row.requestId}/debts`).catch(() => []),
        row.planId ? request(`/api/v1/payment-plans/${row.planId}`) : null,
        row.planId ? request(`/api/v1/payment-plans/${row.planId}/installments`) : [],
      ]);
      return {
        ...row,
        debts,
        debtIds: debts.map((debt) => debt.id),
        lifecycle: plan?.lifecycle ?? null,
        downPayment: plan ? Number(plan.downPaymentAmount) : Number(row.downPayment),
        financedAmount: plan ? Number(plan.financedPrincipalAmount) : Number(row.estimatedFinancedAmount),
        interestAmount: plan ? Number(plan.financingInterestAmount) : Number(row.estimatedInterest),
        totalAmount: plan ? Number(plan.totalPlanAmount) : Number(row.totalAmount),
        schedule: schedule.map((installment) => ({
          ...installment,
          amount: Number(installment.totalAmount),
        })),
      };
    }));
  },

  async exemptions({ taxpayerId }) {
    const [requests, granted, concepts] = await Promise.all([
      allPages(`/api/v1/taxpayers/${taxpayerId}/exemption-requests`),
      request(`/api/v1/taxpayers/${taxpayerId}/exemptions`),
      allPages("/api/v1/tax-concepts"),
    ]);
    return Promise.all(requests.map(async (row) => {
      const approval = granted.find((item) => Number(item.requestId) === Number(row.requestId));
      const concept = concepts.find((item) => Number(item.id) === Number(row.conceptId));
      const documents = await request(`/api/v1/exemption-requests/${row.requestId}/documents`);
      return {
        ...row,
        conceptName: concept?.name ?? row.conceptName,
        percentage: approval?.percentage ?? null,
        validFrom: approval?.validFrom ?? null,
        validUntil: approval?.validUntil ?? null,
        attachments: documents,
      };
    }));
  },

  async benefits({ taxpayerId }) {
    return request(`/api/v1/taxpayers/${taxpayerId}/benefits`);
  },

  async activePaymentPlans({ taxpayerId }) {
    return allPages(`/api/v1/taxpayers/${taxpayerId}/payment-plans`);
  },

  async planInstallments(planId) {
    return request(`/api/v1/payment-plans/${planId}/installments`);
  },

  // ---------------------------------------------------------------- Trámites

  /** Simulación previa: el ciudadano ve la cuota antes de mandar la solicitud. */
  async simulatePaymentPlan({ taxpayerId, debtIds, installments }) {
    return paymentPlanService.simulate({ taxpayerId, debtIds, installments });
  },

  /** Pedir financiar deudas en cuotas → publica paymentPlanRequested. */
  async requestPaymentPlan({ taxpayerId, debtIds, installments }) {
    return paymentPlanService.request({ taxpayerId, debtIds, installments });
  },

  async previewElectronicPayment({ debtId, paymentMethod, amount }) {
    return request("/api/v1/electronic-payments/preview", {
      method: "POST",
      body: { debtId, paymentMethod, amount },
    });
  },

  async createElectronicPayment({ debtId, paymentMethod, amount, idempotencyKey }) {
    return request("/api/v1/electronic-payments", {
      method: "POST",
      body: { debtId, paymentMethod, amount },
      headers: idempotencyKey ? { "Idempotency-Key": idempotencyKey } : {},
    });
  },

  async electronicPayment(paymentId) {
    return request(`/api/v1/electronic-payments/${paymentId}`);
  },

  async paymentReceipt(paymentId) {
    return request(`/api/v1/payments/${paymentId}/receipt`);
  },

  async simulateRefinancing({ planId, installments }) {
    return request(`/api/v1/payment-plans/${planId}/refinancing/simulations`, {
      method: "POST",
      body: { installments },
    });
  },

  async requestRefinancing({ planId, installments }) {
    return request(`/api/v1/payment-plans/${planId}/refinancing-requests`, {
      method: "POST",
      body: { installments },
    });
  },

  async refinancingRequest(requestId) {
    return request(`/api/v1/refinancing-requests/${requestId}`);
  },

  /** Pedir una exención total o parcial → publica exemptionRequested hacia M8. */
  async requestExemption({
    taxpayerId,
    conceptCode,
    reason,
    requestedPercentage,
    requestedFrom,
    requestedUntil,
    attachments = [],
  }) {
    return exemptionService.requestExemption({
      citizenId: taxpayerId,
      conceptCode,
      reason,
      requestedPercentage,
      requestedFrom,
      requestedUntil,
      attachments,
    });
  },
};
