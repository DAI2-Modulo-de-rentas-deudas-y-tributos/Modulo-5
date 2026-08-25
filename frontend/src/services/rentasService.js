/**
 * Servicios de aplicación de Rentas.
 *
 * Cada función representa una operación de negocio (no un CRUD genérico), en línea
 * con los DTO por caso de uso acordados: RegisterPaymentRequest, AllocatePaymentRequest,
 * RequestPaymentReversalRequest, etc. Cuando el backend exista basta con quitar la
 * rama de mocks: la firma y el shape de respuesta no cambian.
 */
import { USE_MOCKS, delay, request, ApiError } from "./apiClient.js";
import * as db from "./mockDb.js";

/** Copia mutable en memoria: las acciones de la demo persisten durante la sesión. */
const store = {
  taxpayers: db.taxpayers.map((t) => ({ ...t })),
  settlements: db.settlements.map((s) => ({ ...s })),
  debts: db.debts.map((d) => ({ ...d })),
  bills: db.bills.map((b) => ({ ...b })),
  payments: db.payments.map((p) => ({ ...p })),
  paymentPlans: db.paymentPlans.map((p) => ({ ...p })),
  exemptions: db.exemptions.map((e) => ({ ...e })),
  tickets: db.tickets.map((t) => ({ ...t })),
  eventLog: db.eventLog.map((e) => ({ ...e })),
};

let sequence = 90000;
const nextId = () => ++sequence;
const nowIso = () => new Date().toISOString();

/** Registra en la bitácora el evento que el backend publicaría vía outbox. */
function recordOutboundEvent(eventType, destinationModule, data) {
  store.eventLog.unshift({
    eventId: crypto.randomUUID(),
    eventType,
    direction: "OUT",
    sourceModule: "M5",
    destinationModule,
    occurredAt: nowIso(),
    processedAt: nowIso(),
    status: "PUBLISHED",
    attempts: 1,
    data,
  });
}

const matches = (value, term) =>
  String(value ?? "").toLowerCase().includes(term.toLowerCase());

/** Los importes se redondean a centavos: el dinero nunca se muestra con ruido binario. */
const round2 = (value) => Math.round(value * 100) / 100;

// ---------------------------------------------------------------- Autenticación

export const authService = {
  async login({ username, password }) {
    if (!USE_MOCKS) {
      return request("/api/v1/auth/login", { method: "POST", body: { username, password } });
    }
    await delay();
    const user = db.USERS.find(
      (u) => u.username === username.trim().toLowerCase() && u.password === password,
    );
    if (!user) {
      throw new ApiError("Usuario o contraseña incorrectos.", 401);
    }
    const { password: _omit, ...profile } = user;
    return { token: `mock.${btoa(user.username)}.token`, user: profile };
  },

  async logout() {
    if (!USE_MOCKS) {
      await request("/api/v1/auth/logout", { method: "POST" }).catch(() => null);
    }
  },
};

// --------------------------------------------------------------- Contribuyentes

export const taxpayerService = {
  async search({ query = "", type = "" } = {}) {
    if (!USE_MOCKS) {
      const params = new URLSearchParams({ query, type });
      return request(`/api/v1/taxpayers?${params}`);
    }
    await delay();
    return store.taxpayers.filter((t) => {
      const byType = !type || t.type === type;
      const byQuery =
        !query ||
        matches(t.name, query) ||
        matches(t.document, query) ||
        matches(t.cuit, query) ||
        matches(t.id, query);
      return byType && byQuery;
    });
  },

  async getById(id) {
    if (!USE_MOCKS) return request(`/api/v1/taxpayers/${id}`);
    await delay(200);
    return store.taxpayers.find((t) => t.id === Number(id)) ?? null;
  },
};

// ---------------------------------------------------------------- Liquidaciones

export const settlementService = {
  async list({ period = "", conceptCode = "", status = "", taxpayerId = "" } = {}) {
    if (!USE_MOCKS) {
      const params = new URLSearchParams({ period, conceptCode, status, taxpayerId });
      return request(`/api/v1/settlements?${params}`);
    }
    await delay();
    return store.settlements.filter(
      (s) =>
        (!period || s.period === period) &&
        (!conceptCode || s.conceptCode === conceptCode) &&
        (!status || s.status === status) &&
        (!taxpayerId || s.taxpayerId === Number(taxpayerId)),
    );
  },

  /** GenerateSettlementRequest → SettlementResponse */
  async generate({ taxpayerId, conceptCode, period, baseAmount, dueDate }) {
    if (!USE_MOCKS) {
      return request("/api/v1/settlements", {
        method: "POST",
        body: { taxpayerId, conceptCode, period, baseAmount, dueDate },
      });
    }
    await delay();
    const taxpayer = store.taxpayers.find((t) => t.id === Number(taxpayerId));
    if (!taxpayer) throw new ApiError("El contribuyente no existe en el padrón local.", 404);

    // El descuento sale del beneficio social replicado desde M8 (socialBenefitUpdated).
    const benefit = taxpayer.benefit;
    const applies =
      benefit?.status === "ACTIVE" && benefit.applicableConceptCodes.includes(conceptCode);
    const discountPercentage = applies ? benefit.discountPercentage : 0;
    const amount = Number(baseAmount) * (1 - discountPercentage / 100);

    const settlement = {
      id: nextId(),
      taxpayerId: Number(taxpayerId),
      taxpayerType: taxpayer.type,
      conceptCode,
      period,
      baseAmount: Number(baseAmount),
      discountPercentage,
      amount,
      dueDate,
      status: "DRAFT",
      createdAt: nowIso(),
    };
    store.settlements.unshift(settlement);
    return settlement;
  },

  /** Confirma la liquidación y genera la deuda asociada (evento interno debtGenerated). */
  async issue(settlementId) {
    if (!USE_MOCKS) {
      return request(`/api/v1/settlements/${settlementId}/issue`, { method: "POST" });
    }
    await delay();
    const settlement = store.settlements.find((s) => s.id === Number(settlementId));
    if (!settlement) throw new ApiError("Liquidación inexistente.", 404);
    if (settlement.status !== "DRAFT") {
      throw new ApiError("Sólo se pueden emitir liquidaciones en estado borrador.", 409);
    }
    settlement.status = "ISSUED";

    store.debts.unshift({
      id: nextId(),
      taxpayerId: settlement.taxpayerId,
      taxpayerType: settlement.taxpayerType,
      conceptCode: settlement.conceptCode,
      originType: "SETTLEMENT",
      originId: settlement.id,
      originalAmount: settlement.amount,
      outstandingAmount: settlement.amount,
      dueDate: settlement.dueDate,
      status: "PENDING",
      reportedToM8: false,
    });
    return settlement;
  },
};

// ------------------------------------------------------------------------ Deudas

export const debtService = {
  async list({ taxpayerId = "", status = "", originType = "" } = {}) {
    if (!USE_MOCKS) {
      const params = new URLSearchParams({ taxpayerId, status, originType });
      return request(`/api/v1/debts?${params}`);
    }
    await delay();
    return store.debts.filter(
      (d) =>
        (!taxpayerId || d.taxpayerId === Number(taxpayerId)) &&
        (!status || d.status === status) &&
        (!originType || d.originType === originType),
    );
  },

  /** Estado de cuenta consolidado de un contribuyente. */
  async accountStatement(taxpayerId) {
    if (!USE_MOCKS) return request(`/api/v1/taxpayers/${taxpayerId}/account-statement`);
    await delay();
    const items = store.debts.filter((d) => d.taxpayerId === Number(taxpayerId));
    return {
      taxpayerId: Number(taxpayerId),
      items,
      totalOutstanding: items.reduce((acc, d) => acc + d.outstandingAmount, 0),
      totalOverdue: items
        .filter((d) => d.status === "OVERDUE")
        .reduce((acc, d) => acc + d.outstandingAmount, 0),
    };
  },

  /** Informa la deuda vencida a Desarrollo Social (evento overdueDebt → M8). */
  async reportOverdue(debtId) {
    if (!USE_MOCKS) {
      return request(`/api/v1/debts/${debtId}/report-overdue`, { method: "POST" });
    }
    await delay();
    const debt = store.debts.find((d) => d.id === Number(debtId));
    if (!debt) throw new ApiError("Deuda inexistente.", 404);
    if (debt.status !== "OVERDUE") {
      throw new ApiError("Sólo se informan deudas en estado vencido.", 409);
    }
    debt.reportedToM8 = true;
    recordOutboundEvent("overdueDebt", "M8", {
      debtId: debt.id,
      citizenId: debt.taxpayerId,
      conceptCode: debt.conceptCode,
      outstandingAmount: debt.outstandingAmount,
      dueDate: debt.dueDate,
    });
    return debt;
  },
};

// ----------------------------------------------------------------------- Boletas

export const billService = {
  async list({ taxpayerId = "", status = "" } = {}) {
    if (!USE_MOCKS) {
      const params = new URLSearchParams({ taxpayerId, status });
      return request(`/api/v1/bills?${params}`);
    }
    await delay();
    return store.bills.filter(
      (b) =>
        (!taxpayerId || b.taxpayerId === Number(taxpayerId)) && (!status || b.status === status),
    );
  },

  /** IssueBillRequest → BillResponse. El PDF vive en S3, nunca en la base. */
  async issue({ debtId }) {
    if (!USE_MOCKS) {
      return request("/api/v1/bills", { method: "POST", body: { debtId } });
    }
    await delay();
    const debt = store.debts.find((d) => d.id === Number(debtId));
    if (!debt) throw new ApiError("Deuda inexistente.", 404);
    if (debt.outstandingAmount <= 0) {
      throw new ApiError("La deuda no tiene saldo pendiente.", 409);
    }
    const bill = {
      id: nextId(),
      debtId: debt.id,
      taxpayerId: debt.taxpayerId,
      conceptCode: debt.conceptCode,
      amount: debt.outstandingAmount,
      dueDate: debt.dueDate,
      barcode: String(999000000000000000000 + debt.id).slice(0, 22),
      status: "ISSUED",
      issuedAt: nowIso(),
      documentUrl: `s3://rentas-documents/boletas/2026/${nextId()}.pdf`,
    };
    store.bills.unshift(bill);
    return bill;
  },
};

// ------------------------------------------------------------------------- Pagos

export const paymentService = {
  async list({ taxpayerId = "", status = "" } = {}) {
    if (!USE_MOCKS) {
      const params = new URLSearchParams({ taxpayerId, status });
      return request(`/api/v1/payments?${params}`);
    }
    await delay();
    return store.payments.filter(
      (p) =>
        (!taxpayerId || p.taxpayerId === Number(taxpayerId)) && (!status || p.status === status),
    );
  },

  /** RegisterPaymentRequest → PaymentResponse */
  async register({ taxpayerId, debtId, amountPaid, channel, paidAt }) {
    if (!USE_MOCKS) {
      return request("/api/v1/payments", {
        method: "POST",
        body: { taxpayerId, debtId, amountPaid, channel, paidAt },
      });
    }
    await delay();
    const amount = Number(amountPaid);
    if (!(amount > 0)) throw new ApiError("El importe debe ser mayor a cero.", 400);

    const debt = debtId ? store.debts.find((d) => d.id === Number(debtId)) : null;
    if (debtId && !debt) throw new ApiError("Deuda inexistente.", 404);
    if (debt && amount > debt.outstandingAmount) {
      throw new ApiError(
        "El importe supera el saldo de la deuda: registre el pago sin imputar y genere saldo a favor.",
        409,
      );
    }

    const payment = {
      id: nextId(),
      taxpayerId: Number(taxpayerId),
      debtId: debt ? debt.id : null,
      originType: debt ? debt.originType : "SETTLEMENT",
      originId: debt ? debt.originId : null,
      amountPaid: amount,
      remainingBalance: debt ? debt.outstandingAmount - amount : null,
      paidAt: paidAt ? new Date(paidAt).toISOString() : nowIso(),
      channel,
      receiptNumber: `REC-2026-${nextId()}`,
      status: debt ? "REGISTERED" : "UNALLOCATED",
      allocated: Boolean(debt),
    };
    store.payments.unshift(payment);

    if (debt) applyPaymentToDebt(debt, payment);
    return payment;
  },

  /** AllocatePaymentRequest → PaymentAllocationResponse (pagos sin imputar). */
  async allocate({ paymentId, debtId }) {
    if (!USE_MOCKS) {
      return request(`/api/v1/payments/${paymentId}/allocations`, {
        method: "POST",
        body: { debtId },
      });
    }
    await delay();
    const payment = store.payments.find((p) => p.id === Number(paymentId));
    if (!payment) throw new ApiError("Pago inexistente.", 404);
    if (payment.allocated) throw new ApiError("El pago ya está imputado.", 409);
    if (payment.status === "REVERSED") throw new ApiError("El pago está reversado.", 409);

    const debt = store.debts.find((d) => d.id === Number(debtId));
    if (!debt) throw new ApiError("Deuda inexistente.", 404);
    if (debt.taxpayerId !== payment.taxpayerId) {
      throw new ApiError("La deuda pertenece a otro contribuyente.", 409);
    }
    if (payment.amountPaid > debt.outstandingAmount) {
      throw new ApiError("El importe del pago supera el saldo de la deuda.", 409);
    }

    payment.debtId = debt.id;
    payment.originType = debt.originType;
    payment.originId = debt.originId;
    payment.allocated = true;
    payment.status = "REGISTERED";
    payment.remainingBalance = debt.outstandingAmount - payment.amountPaid;

    applyPaymentToDebt(debt, payment);
    return payment;
  },

  /** RequestPaymentReversalRequest → PaymentReversalResponse */
  async reverse({ paymentId, reason }) {
    if (!USE_MOCKS) {
      return request(`/api/v1/payments/${paymentId}/reversal`, {
        method: "POST",
        body: { reason },
      });
    }
    await delay();
    const payment = store.payments.find((p) => p.id === Number(paymentId));
    if (!payment) throw new ApiError("Pago inexistente.", 404);
    if (payment.status === "REVERSED") throw new ApiError("El pago ya fue reversado.", 409);
    if (!reason?.trim()) throw new ApiError("El motivo de la reversión es obligatorio.", 400);

    payment.status = "REVERSED";
    payment.allocated = false;
    payment.reversalReason = reason;

    const debt = store.debts.find((d) => d.id === payment.debtId);
    if (debt) {
      debt.outstandingAmount += payment.amountPaid;
      debt.status = new Date(debt.dueDate) < new Date() ? "OVERDUE" : "PENDING";
      payment.remainingBalance = debt.outstandingAmount;
    }

    recordOutboundEvent("paymentReversed", originModule(payment.originType), {
      paymentId: payment.id,
      originType: payment.originType,
      originId: payment.originId,
      reversedAmount: payment.amountPaid,
      remainingBalance: payment.remainingBalance,
      reason,
    });
    return payment;
  },
};

/** Descuenta el pago de la deuda y publica paymentRegistered / debtSettled. */
function applyPaymentToDebt(debt, payment) {
  debt.outstandingAmount -= payment.amountPaid;

  recordOutboundEvent("paymentRegistered", originModule(debt.originType), {
    paymentId: payment.id,
    originType: debt.originType,
    originId: debt.originId,
    amountPaid: payment.amountPaid,
    paidAt: payment.paidAt,
    remainingBalance: debt.outstandingAmount,
  });

  if (debt.outstandingAmount <= 0) {
    debt.outstandingAmount = 0;
    debt.status = "SETTLED";
    recordOutboundEvent("debtSettled", originModule(debt.originType), {
      debtId: debt.id,
      originType: debt.originType,
      originId: debt.originId,
      settledAt: nowIso(),
    });
  }
}

/** Módulo destinatario según el origen lógico de la deuda. */
function originModule(originType) {
  if (originType === "PERMIT_FEE" || originType === "COMMERCIAL_FINE") return "M4";
  if (originType === "TRAFFIC_INFRACTION") return "M7";
  return "M5";
}

// -------------------------------------------------------------- Planes de pago

export const paymentPlanService = {
  async list({ status = "" } = {}) {
    if (!USE_MOCKS) {
      const params = new URLSearchParams({ status });
      return request(`/api/v1/payment-plans?${params}`);
    }
    await delay();
    return store.paymentPlans.filter((p) => !status || p.status === status);
  },

  /** Simula el plan antes de resolver: interés fijo del 5% por cada 3 cuotas. */
  simulate({ totalDebt, installments }) {
    const n = Number(installments) || 1;
    const interestRate = Math.floor(n / 3) * 0.05;
    const totalAmount = round2(Number(totalDebt) * (1 + interestRate));
    return {
      installments: n,
      interestRate,
      totalAmount,
      // La diferencia por redondeo se absorbe en la última cuota al generar el plan.
      installmentAmount: round2(totalAmount / n),
    };
  },

  /** ResolvePaymentPlanRequest → publica updatePaymentPlanStatus (GRANTED | REJECTED). */
  async resolve({ requestId, status, installments, reason, resolvedBy }) {
    if (!USE_MOCKS) {
      return request(`/api/v1/payment-plans/${requestId}/status`, {
        method: "PUT",
        body: { status, installments, reason },
      });
    }
    await delay();
    const plan = store.paymentPlans.find((p) => p.requestId === Number(requestId));
    if (!plan) throw new ApiError("Solicitud inexistente.", 404);
    if (plan.status !== "REQUESTED") {
      throw new ApiError("La solicitud ya fue resuelta.", 409);
    }
    if (status === "REJECTED" && !reason?.trim()) {
      throw new ApiError("El motivo del rechazo es obligatorio.", 400);
    }

    plan.status = status;
    plan.resolvedAt = nowIso();
    plan.resolvedBy = resolvedBy;

    if (status === "GRANTED") {
      const simulation = paymentPlanService.simulate({
        totalDebt: plan.totalDebt,
        installments: installments ?? plan.installments,
      });
      plan.planId = nextId();
      plan.installments = simulation.installments;
      plan.totalAmount = simulation.totalAmount;
      recordOutboundEvent("updatePaymentPlanStatus", "M5", {
        requestId: plan.requestId,
        status: "GRANTED",
        planId: plan.planId,
        taxpayerId: plan.taxpayerId,
        installments: plan.installments,
        totalAmount: plan.totalAmount,
      });
    } else {
      plan.reason = reason;
      recordOutboundEvent("updatePaymentPlanStatus", "M5", {
        requestId: plan.requestId,
        status: "REJECTED",
        taxpayerId: plan.taxpayerId,
        reason,
      });
    }
    return plan;
  },
};

// ------------------------------------------------------------------ Exenciones

export const exemptionService = {
  async list({ status = "" } = {}) {
    if (!USE_MOCKS) {
      const params = new URLSearchParams({ status });
      return request(`/api/v1/exemptions?${params}`);
    }
    await delay();
    return store.exemptions.filter((e) => !status || e.status === status);
  },

  /** Alta de solicitud por mesa de entradas → publica exemptionRequested hacia M8. */
  async requestExemption({
    citizenId,
    conceptCode,
    reason,
    requestedPercentage,
    requestedFrom,
    requestedUntil,
  }) {
    if (!USE_MOCKS) {
      return request("/api/v1/exemptions", {
        method: "POST",
        body: { citizenId, conceptCode, reason, requestedPercentage, requestedFrom, requestedUntil },
      });
    }
    await delay();
    const taxpayer = store.taxpayers.find((t) => t.id === Number(citizenId));
    if (!taxpayer) throw new ApiError("El contribuyente no existe en el padrón local.", 404);

    const exemption = {
      requestId: nextId(),
      exemptionId: null,
      citizenId: Number(citizenId),
      conceptCode,
      reason,
      requestedPercentage: Number(requestedPercentage),
      requestedFrom,
      requestedUntil,
      status: "REQUESTED",
      requestedAt: nowIso(),
      hasSocialBenefit: taxpayer.benefit?.status === "ACTIVE",
      attachments: [],
      resolvedBy: null,
    };
    store.exemptions.unshift(exemption);

    recordOutboundEvent("exemptionRequested", "M8", {
      requestId: exemption.requestId,
      citizenId: exemption.citizenId,
      conceptCode: exemption.conceptCode,
      reason: exemption.reason,
      requestedPercentage: exemption.requestedPercentage,
      requestedFrom: exemption.requestedFrom,
      requestedUntil: exemption.requestedUntil,
    });
    return exemption;
  },

  /** ResolveExemptionRequest → publica updateExemptionStatus (APPROVED | REJECTED). */
  async resolve({ requestId, status, percentage, validFrom, validUntil, reason, resolvedBy }) {
    if (!USE_MOCKS) {
      return request(`/api/v1/exemptions/${requestId}/status`, {
        method: "PUT",
        body: { status, percentage, validFrom, validUntil, reason },
      });
    }
    await delay();
    const exemption = store.exemptions.find((e) => e.requestId === Number(requestId));
    if (!exemption) throw new ApiError("Solicitud inexistente.", 404);
    if (exemption.status !== "REQUESTED") {
      throw new ApiError("La solicitud ya fue resuelta.", 409);
    }
    if (status === "REJECTED" && !reason?.trim()) {
      throw new ApiError("El motivo del rechazo es obligatorio.", 400);
    }

    exemption.status = status;
    exemption.resolvedBy = resolvedBy;

    if (status === "APPROVED") {
      exemption.exemptionId = nextId();
      exemption.percentage = Number(percentage ?? exemption.requestedPercentage);
      exemption.validFrom = validFrom ?? exemption.requestedFrom;
      exemption.validUntil = validUntil ?? exemption.requestedUntil;
      recordOutboundEvent("updateExemptionStatus", "M8", {
        requestId: exemption.requestId,
        status: "APPROVED",
        exemptionId: exemption.exemptionId,
        citizenId: exemption.citizenId,
        conceptCode: exemption.conceptCode,
        percentage: exemption.percentage,
        validFrom: exemption.validFrom,
        validUntil: exemption.validUntil,
      });
    } else {
      exemption.reason_rejected = reason;
      recordOutboundEvent("updateExemptionStatus", "M8", {
        requestId: exemption.requestId,
        status: "REJECTED",
        citizenId: exemption.citizenId,
        conceptCode: exemption.conceptCode,
        reason,
      });
    }
    return exemption;
  },
};

// --------------------------------------------------------------------- Tickets

export const ticketService = {
  async list({ status = "", priority = "" } = {}) {
    if (!USE_MOCKS) {
      const params = new URLSearchParams({ status, priority });
      return request(`/api/v1/tickets?${params}`);
    }
    await delay();
    return store.tickets.filter(
      (t) => (!status || t.status === status) && (!priority || t.priority === priority),
    );
  },

  /** UpdateTicketStatusRequest → publica updateTicketStatus hacia M2. */
  async updateStatus({ ticketId, status, reason, assignedTo }) {
    if (!USE_MOCKS) {
      return request(`/api/v1/tickets/${ticketId}/status`, {
        method: "PUT",
        body: { status, reason },
      });
    }
    await delay();
    const ticket = store.tickets.find((t) => t.ticketId === Number(ticketId));
    if (!ticket) throw new ApiError("Ticket inexistente.", 404);
    if (status === "REJECTED" && !reason?.trim()) {
      throw new ApiError("El motivo del rechazo es obligatorio.", 400);
    }

    ticket.status = status;
    if (assignedTo) ticket.assignedTo = assignedTo;

    recordOutboundEvent("updateTicketStatus", "M2", {
      ticketId: ticket.ticketId,
      status,
      ...(reason ? { reason } : {}),
    });
    return ticket;
  },
};

// ------------------------------------------------------------ Bitácora de eventos

export const eventService = {
  async list({ direction = "", status = "", eventType = "" } = {}) {
    if (!USE_MOCKS) {
      const params = new URLSearchParams({ direction, status, eventType });
      return request(`/api/v1/events?${params}`);
    }
    await delay();
    return store.eventLog.filter(
      (e) =>
        (!direction || e.direction === direction) &&
        (!status || e.status === status) &&
        (!eventType || matches(e.eventType, eventType)),
    );
  },

  /** Reproceso manual de un evento en DLQ (el consumidor es idempotente vía eventId). */
  async retry(eventId) {
    if (!USE_MOCKS) {
      return request(`/api/v1/events/${eventId}/retry`, { method: "POST" });
    }
    await delay(500);
    const event = store.eventLog.find((e) => e.eventId === eventId);
    if (!event) throw new ApiError("Evento inexistente.", 404);
    if (event.status !== "DLQ") throw new ApiError("Sólo se reprocesan eventos en DLQ.", 409);
    event.status = "PROCESSED";
    event.processedAt = nowIso();
    event.attempts += 1;
    return event;
  },
};

// -------------------------------------------------------------------- Dashboard

export const dashboardService = {
  /** Métricas del panel de inicio, una por módulo funcional. */
  async metrics() {
    if (!USE_MOCKS) return request("/api/v1/dashboard/metrics");
    await delay();
    const today = "2026-08-25";
    return {
      contribuyentes: store.taxpayers.length,
      liquidaciones: store.settlements.filter((s) => s.period.startsWith("2026-08")).length,
      deudas: store.debts.filter((d) => d.status === "OVERDUE").length,
      boletas: store.bills.filter((b) => b.status === "ISSUED").length,
      pagos: store.payments.filter((p) => p.paidAt.startsWith(today)).length,
      planes: store.paymentPlans.filter((p) => p.status === "REQUESTED").length,
      exenciones: store.exemptions.filter((e) => e.status === "REQUESTED").length,
      tickets: store.tickets.filter((t) => t.status !== "COMPLETED" && t.status !== "REJECTED")
        .length,
      eventos: store.eventLog.filter((e) => e.status === "DLQ").length,
      totalOverdueAmount: store.debts
        .filter((d) => d.status === "OVERDUE")
        .reduce((acc, d) => acc + d.outstandingAmount, 0),
      unallocatedPayments: store.payments.filter((p) => p.status === "UNALLOCATED").length,
    };
  },
};
