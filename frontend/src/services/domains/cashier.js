import { taxpayerService } from "./taxpayers.js";
import { billService } from "./payments.js";
import { ApiError, request } from "../apiClient.js";
import { allPages, debtTotals, openDebts, paymentReceipt, round2 } from "./shared.js";

export const cashierService = {
  /** Búsqueda unificada: documento, CUIT, nombre, N° de boleta o N° de deuda. */
  async search({ query = "" } = {}) {
    const term = query.trim();
    if (!term) return [];
    const [taxpayers, bills, debt] = await Promise.all([
      taxpayerService.search({ query: term }),
      billService.search({ query: term }),
      /^\d+$/.test(term) ? request(`/api/v1/debts/${term}`).catch((error) => {
        if (error.status === 404) return null;
        throw error;
      }) : null,
    ]);
    return [
      ...taxpayers.map((taxpayer) => ({ kind: "TAXPAYER", id: taxpayer.id, title: taxpayer.name, subtitle: `${taxpayer.documentType} ${taxpayer.document}`, detail: taxpayer.cuit, status: taxpayer.status, amount: null })),
      ...bills.map((bill) => ({ ...bill, kind: "BILL", title: `Boleta #${bill.id}`, subtitle: bill.barcode, detail: `Contribuyente #${bill.taxpayerId}` })),
      ...(debt ? [{ ...debt, kind: "DEBT", title: `Deuda #${debt.id}`, subtitle: debt.conceptCode, detail: `Contribuyente #${debt.taxpayerId}`, amount: debt.outstandingAmount }] : []),
    ];
  },

  /**
   * Contexto de cobro de un resultado de búsqueda: qué se cobra y a quién.
   * Por contribuyente devuelve todas sus deudas con saldo; por boleta o deuda,
   * sólo la obligación elegida.
   */
  async chargeContext({ kind, id, planId, taxpayerId: selectedTaxpayerId }) {
    let taxpayerId = id;
    let bill = null;
    let debts;
    let installments = [];
    if (kind === "DEBT") {
      const debt = await request(`/api/v1/debts/${id}`);
      taxpayerId = debt.taxpayerId;
      debts = [debt];
    } else if (kind === "BILL") {
      bill = await request(`/api/v1/bills/${id}`);
      taxpayerId = bill.taxpayerId;
      if (bill.status !== "ISSUED") throw new ApiError("La boleta no admite pagos.", 409, null, "BILL_NOT_PAYABLE");
      debts = await Promise.all((bill.debts ?? []).map((item) => request(`/api/v1/debts/${item.debtId}`)));
    } else if (kind === "TAXPAYER") {
      debts = await allPages(`/api/v1/taxpayers/${id}/debts`);
    } else if (kind === "INSTALLMENT") {
      if (!planId || !selectedTaxpayerId) {
        throw new ApiError("No se pudo identificar el plan y el contribuyente de la cuota.", 400);
      }
      taxpayerId = Number(selectedTaxpayerId);
      const installment = await request(`/api/v1/payment-plans/${planId}/installments/${id}`);
      if (Number(installment.paymentPlanId) !== Number(planId)) {
        throw new ApiError("La cuota no pertenece al plan informado.", 409);
      }
      installments = [{ ...installment, planId: Number(planId) }];
      debts = [];
    } else {
      throw new ApiError("El tipo de búsqueda no es válido.", 400);
    }
    const taxpayer = await taxpayerService.getById(taxpayerId);
    const payable = openDebts(debts).filter((debt) => !debt.inPaymentPlan);
    const installment = installments[0] ?? null;
    const totals = kind === "INSTALLMENT"
      ? {
          pendingCount: installment ? 1 : 0,
          outstanding: Number(installment?.outstandingAmount ?? 0),
          overdue: installment?.overdue || installment?.status === "OVERDUE" ? Number(installment.outstandingAmount) : 0,
        }
      : debtTotals(debts);
    return {
      kind,
      taxpayer,
      bill,
      debts: payable,
      installments,
      totals,
      selectedDebtId: kind !== "TAXPAYER" && payable.length === 1 ? payable[0].id : null,
      selectedInstallmentId: kind === "INSTALLMENT" ? installment?.id : null,
    };
  },

  /** RegisterCounterPaymentRequest → CounterPaymentReceiptResponse */
  async registerCounterPayment({ debtId, installmentId, paymentPlanId, taxpayerId, billId, amountPaid, method, registeredBy, idempotencyKey }) {
    const isInstallment = Boolean(installmentId);
    let debt = null;
    let installment = null;
    let resolvedTaxpayerId = taxpayerId;
    if (isInstallment) {
      if (!paymentPlanId || !resolvedTaxpayerId) {
        throw new ApiError("No se pudo identificar el plan y el contribuyente de la cuota.", 400);
      }
      installment = await request(`/api/v1/payment-plans/${paymentPlanId}/installments/${installmentId}`);
    } else {
      debt = await request(`/api/v1/debts/${debtId}`);
      resolvedTaxpayerId = debt.taxpayerId;
    }
    const taxpayer = await taxpayerService.getById(resolvedTaxpayerId);
    const payment = await request("/api/v1/cashier/payments", {
      method: "POST",
      body: {
        taxpayerId: resolvedTaxpayerId,
        billId: isInstallment ? null : billId,
        amountPaid,
        method,
        registeredBy,
        allocations: [{
          debtId: isInstallment ? null : Number(debtId),
          installmentId: isInstallment ? Number(installmentId) : null,
          amount: amountPaid,
        }],
      },
      headers: idempotencyKey ? { "Idempotency-Key": idempotencyKey } : {},
    });
    const receipt = isInstallment
      ? {
          ...paymentReceipt(payment, taxpayer),
          targetType: "INSTALLMENT",
          installmentId: Number(installmentId),
          paymentPlanId: Number(paymentPlanId),
          installmentNumber: installment.number,
          conceptCode: `Plan #${paymentPlanId}`,
          wasOverdue: installment.overdue || installment.status === "OVERDUE",
        }
      : {
          ...paymentReceipt(payment, taxpayer),
          targetType: "DEBT",
          debtId: Number(debtId),
          conceptCode: debt.conceptCode,
          wasOverdue: debt.overdue || debt.status === "OVERDUE",
        };
    try {
      const updatedTarget = isInstallment
        ? await request(`/api/v1/payment-plans/${paymentPlanId}/installments/${installmentId}`)
        : await request(`/api/v1/debts/${debtId}`);
      receipt.remainingBalance = updatedTarget.outstandingAmount;
      receipt.settled = Number(updatedTarget.outstandingAmount) === 0;
    } catch {
      // El pago ya se confirmó: no presentar este fallo de lectura como un alta fallida.
      receipt.balanceUnavailable = true;
    }
    return receipt;
  },

  /** Reimpresión: el comprobante de un pago ya registrado. */
  async receipt(paymentId) {
    const [receipt, payment] = await Promise.all([
      request(`/api/v1/payments/${paymentId}/receipt`),
      request(`/api/v1/payments/${paymentId}`),
    ]);
    const taxpayer = await taxpayerService.getById(payment.taxpayerId);
    return { ...paymentReceipt(payment, taxpayer), receiptNumber: receipt.receiptNumber, amountPaid: receipt.amount, issuedAt: receipt.paidAt };
  },

  /** Ficha de ventanilla: deudas, pagos y boletas del contribuyente en una consulta. */
  async taxpayerFile(taxpayerId) {
    const [taxpayer, debts, payments, bills, plans] = await Promise.all([
      taxpayerService.getById(taxpayerId),
      allPages(`/api/v1/taxpayers/${taxpayerId}/debts`),
      allPages(`/api/v1/payments?taxpayerId=${taxpayerId}`),
      allPages(`/api/v1/taxpayers/${taxpayerId}/bills`),
      allPages(`/api/v1/taxpayers/${taxpayerId}/payment-plans`),
    ]);
    const activePlans = plans.filter((plan) => plan.status === "ACTIVE" || plan.lifecycle === "CURRENT");
    const installments = (await Promise.all(activePlans.map(async (plan) => {
      const planId = plan.planId ?? plan.id;
      const schedule = await request(`/api/v1/payment-plans/${planId}/installments`);
      return schedule.map((installment) => ({ ...installment, planId }));
    }))).flat();
    return {
      taxpayer,
      debts,
      payments,
      bills,
      plans,
      installments,
      totals: {
        ...debtTotals(debts),
        paid: round2(payments.filter((payment) => payment.status !== "REVERSED").reduce((sum, payment) => sum + Number(payment.amountPaid), 0)),
      },
    };
  },

  /** Agentes que pueden figurar como responsables de un cobro. */
  async agents() {
    const payments = await allPages("/api/v1/payments");
    return [...new Set(payments.map((payment) => payment.registeredBy).filter(Boolean))].map((username) => ({ value: username, label: username }));
  },

  /** Resumen de la jornada del cajero: lo que muestra el panel de caja. */
  async dailySummary({ registeredBy = "", date } = {}) {
    const params = new URLSearchParams({ registeredBy, date: date ?? "" });
    return request(`/api/v1/cashier/daily-summary?${params}`);
  },
};
