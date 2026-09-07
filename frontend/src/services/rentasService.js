/**
 * Servicios de aplicación de Rentas.
 *
 * Cada función representa una operación de negocio (no un CRUD genérico), en línea
 * con los DTO por caso de uso acordados: RegisterPaymentRequest, AllocatePaymentRequest,
 * RequestPaymentReversalRequest, etc. Toda la información viene del backend.
 */
import { AUTH_MODE, request, ApiError } from "./apiClient.js";

let sequence = 90000;

/** Los importes se redondean a centavos: el dinero nunca se muestra con ruido binario. */
const round2 = (value) => Math.round(value * 100) / 100;

// ---------------------------------------------------------------- Autenticación

export const authService = {
  async login({ username, password }) {
    if (AUTH_MODE === "core") {
      throw new ApiError("La autenticación Core/JWT todavía no tiene un contrato integrado.", 503, null, "CORE_AUTH_PENDING");
    }
    const result = await request("/api/v1/dev-auth/login", { method: "POST", body: { username, password } });
    const UI_ROLES = { RENTAS: "PERSONAL", SUPERVISOR: "SUPERVISOR", CASHIER: "CAJERO", AUDITOR: "AUDITOR", TAXPAYER: "CONTRIBUYENTE" };
    const ROLE_LABELS = { RENTAS: "Personal de Rentas", SUPERVISOR: "Supervisor de Rentas", CASHIER: "Cajero de Rentas", AUDITOR: "Auditor de Rentas", TAXPAYER: "Contribuyente" };
    return {
      token: result.token,
      user: {
        ...result.user,
        fullName: result.user.displayName,
        roleLabel: ROLE_LABELS[result.user.role],
        email: result.user.username,
        backendRole: result.user.role,
        devAuthorities: result.user.authorities,
        role: UI_ROLES[result.user.role],
      },
    };
  },

  async logout() {
    // En modo mock la sesión es local. Core/JWT sigue pendiente de contrato.
  },
};

// --------------------------------------------------------------- Contribuyentes

export const taxpayerService = {
  async search({ query = "", type = "" } = {}) {
    const params = new URLSearchParams({ query, type });
    return request(`/api/v1/taxpayers?${params}`);
  },

  async getById(id) {
    return request(`/api/v1/taxpayers/${id}`);
  },
};

// ------------------------------------------------- Configuración de tributos

async function apiTaxConfigModel() {
  const [concepts, configurations] = await Promise.all([
    request("/api/v1/tax-concepts?size=100"),
    request("/api/v1/tax-configurations?size=100"),
  ]);
  return concepts.map((concept) => {
    const versions = configurations.filter((item) => item.taxConceptId === concept.id);
    return {
      ...concept,
      versions,
      activeVersion: versions.find((item) => item.status === "ACTIVE") ?? null,
      pendingVersion: versions.find((item) => item.status === "PENDING_APPROVAL") ?? null,
      draftVersion: versions.find((item) => item.status === "DRAFT") ?? null,
    };
  });
}

async function apiConceptByCode(code) {
  const concepts = await request(`/api/v1/tax-concepts?q=${encodeURIComponent(code)}&size=100`);
  const concept = concepts.find((item) => item.code === code);
  if (!concept) throw new ApiError("Concepto inexistente.", 404);
  return concept;
}

/**
 * Configuración de tributos.
 *
 * Una versión nueva no toca las liquidaciones ya emitidas: cada liquidación guarda
 * con qué versión se calculó, así que un cambio de alícuota rige de acá en adelante
 * y el pasado queda reconstruible. Los cambios que afectan el cálculo pasan por
 * aprobación del Supervisor antes de regir.
 */
export const taxConfigService = {
  /** Conceptos con su versión vigente y si tienen algo esperando aprobación. */
  async list({ type = "", status = "" } = {}) {
    const concepts = await apiTaxConfigModel();
    return concepts.filter((item) => (!type || item.type === type) && (!status || item.status === status));
  },

  /**
   * Catálogo de conceptos para los combos de las pantallas operativas.
   *
   * Los formularios no pueden ofrecer un código que el módulo no tenga dado de alta:
   * la operación fallaría recién al registrarla. Por defecto sólo devuelve los
   * conceptos activos, que son los únicos sobre los que se puede operar hoy.
   */
  async concepts({ onlyActive = true } = {}) {
    const concepts = await request("/api/v1/tax-concepts?size=100");
    return onlyActive ? concepts.filter((c) => c.status === "ACTIVE") : concepts;
  },

  /** Ficha del concepto con todo su historial de versiones. */
  async detail(code) {
    const concept = (await apiTaxConfigModel()).find((item) => item.code === code);
    if (!concept) throw new ApiError("Concepto inexistente.", 404);
    return { ...concept, settlementCount: null, openDebtCount: null };
  },

  /**
   * ProposeTaxConfigVersionRequest: crea una versión en borrador con las reglas de
   * cálculo propuestas. No rige hasta que el Supervisor la apruebe.
   */
  async proposeVersion({
    code,
    calculationType,
    rate,
    minimumAmount,
    maximumAmount,
    validFrom,
    validUntil,
    conceptStatus,
    note,
    requestedBy,
  }) {
    const concept = await apiConceptByCode(code);
    // El código de cálculo viaja en español: `adaptApiRequest` lo traduce al enum
    // del backend (CALC_TO_API) y normaliza los importes vacíos del formulario.
    return request("/api/v1/tax-configurations", {
      method: "POST",
      body: {
        taxConceptId: concept.id,
        calculationType,
        rate,
        // El formulario no pide alícuota cuando el cálculo es fijo: el importe de
        // esa forma de cálculo es el mínimo cargado, no cero.
        fixedAmount: calculationType === "FIJO" ? rate || minimumAmount : 0,
        minimumAmount,
        maximumAmount,
        partialPaymentAllowed: true,
        paymentPlanAllowed: true,
        validFrom,
        validUntil,
      },
    });
  },

  /** El borrador pasa a la bandeja del Supervisor. */
  async submitForApproval({ code, version, requestedBy }) {
    const concept = await apiConceptByCode(code);
    const versions = await request(`/api/v1/tax-configurations?conceptId=${concept.id}&size=100`);
    const target = versions.find((item) => item.version === Number(version));
    if (!target) throw new ApiError("Versión inexistente.", 404);
    return request(`/api/v1/tax-configurations/${target.id}/submit`, { method: "POST" });
  },

  /** Bandeja del Supervisor: todo lo que espera aprobación, de lo más viejo a lo nuevo. */
  async pendingApprovals() {
    return (await apiTaxConfigModel()).flatMap((concept) => concept.versions
      .filter((item) => item.status === "PENDING_APPROVAL")
      .map((item) => ({ ...item, code: concept.code, name: concept.name, type: concept.type, currentVersion: concept.activeVersion })));
  },

  /**
   * ResolveTaxConfigVersionRequest. Al aprobar, la versión pasa a regir y la anterior
   * queda inactiva: se conserva el historial completo, nunca se borra una versión.
   */
  async resolveVersion({ code, version, status, resolvedBy, resolverRole, reason }) {
    const concept = await apiConceptByCode(code);
    const versions = await request(`/api/v1/tax-configurations?conceptId=${concept.id}&size=100`);
    const target = versions.find((item) => item.version === Number(version));
    if (!target) throw new ApiError("Versión inexistente.", 404);
    const action = status === "REJECTED" ? "reject" : "approve";
    return request(`/api/v1/tax-configurations/${target.id}/${action}`, { method: "POST", body: action === "reject" ? { reason } : { observation: reason ?? null } });
  },
};

// ---------------------------------------------------------------- Liquidaciones

export const settlementService = {
  async list({ period = "", conceptCode = "", status = "", taxpayerId = "" } = {}) {
    const params = new URLSearchParams({ period, status, taxpayerId });
    if (conceptCode) params.set("conceptId", String((await apiConceptByCode(conceptCode)).id));
    return request(`/api/v1/settlements?${params}`);
  },

  /** GenerateSettlementRequest → SettlementResponse */
  async generate({ taxpayerId, conceptCode, period, baseAmount, dueDate }) {
    const concept = await apiConceptByCode(conceptCode);
    return request("/api/v1/settlements", {
      method: "POST",
      body: { taxpayerId, conceptId: concept.id, period, baseAmount, dueDate },
    });
  },

  /**
   * Previsualiza un lote antes de emitirlo (PreviewSettlementBatchRequest).
   *
   * Devuelve tres grupos porque no todos los casos son iguales:
   *  - `items`    : lo que se va a generar, con el descuento ya calculado por contribuyente.
   *  - `errors`   : lo que queda afuera y por qué. Nada de esto se genera.
   *  - `warnings` : se genera igual, pero el operador tiene que saberlo.
   *
   * Un contribuyente bloqueado o fallecido **no** es un error: la obligación existe
   * igual. Lo que M1 restringe es la emisión de boletas, no la liquidación.
   */
  async previewBatch({ conceptCode, period, baseAmount, dueDate, taxpayerType = "" }) {
    const [concept, taxpayers] = await Promise.all([
      apiConceptByCode(conceptCode),
      taxpayerService.search({ type: taxpayerType }),
    ]);
    const run = await request("/api/v1/liquidation-runs", {
      method: "POST",
      body: { taxConceptId: concept.id, period, dueDate, items: taxpayers.map((item) => ({ taxpayerId: item.id, taxableBase: Number(baseAmount) })) },
    });
    const preview = await request(`/api/v1/liquidation-runs/${run.id}/preview`, { method: "POST" });
    const items = (preview.items ?? []).filter((item) => item.status !== "ERROR");
    const errors = (preview.items ?? []).filter((item) => item.status === "ERROR");
    return { ...preview, items, errors, warnings: [], totals: { toGenerate: items.length, skipped: errors.length, flagged: 0, amount: preview.run?.estimatedTotalAmount ?? 0, discounted: 0 } };
  },

  /**
   * GenerateSettlementBatchRequest → genera el lote en borrador.
   *
   * Queda en `DRAFT` a propósito: emitir doscientas liquidaciones de una es
   * irreversible, así que la emisión sigue siendo un acto explícito por liquidación.
   */
  async generateBatch({ conceptCode, period, baseAmount, dueDate, taxpayerType = "" }) {
    const [concept, taxpayers] = await Promise.all([
      apiConceptByCode(conceptCode),
      taxpayerService.search({ type: taxpayerType }),
    ]);
    const run = await request("/api/v1/liquidation-runs", {
      method: "POST",
      body: { taxConceptId: concept.id, period, dueDate, items: taxpayers.map((item) => ({ taxpayerId: item.id, taxableBase: Number(baseAmount) })) },
    });
    const preview = await request(`/api/v1/liquidation-runs/${run.id}/preview`, { method: "POST" });
    const items = (preview.items ?? []).filter((item) => item.status !== "ERROR");
    return { run: preview.run, generated: items.map((item) => ({ ...item, status: "DRAFT" })), errors: (preview.items ?? []).filter((item) => item.status === "ERROR"), warnings: [], totals: { amount: preview.run?.estimatedTotalAmount ?? 0 } };
  },

  /** Confirma la liquidación y genera la deuda asociada (evento interno debtGenerated). */
  async issue(settlementId) {
    return request(`/api/v1/settlements/${settlementId}/issue`, { method: "POST" });
  },
};

// ------------------------------------------------------------------------ Deudas

export const debtService = {
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

// ------------------------------------------------------------- Ajuste manual

/**
 * Ajustes manuales de deuda.
 *
 * Separa autorizar de ejecutar: el Supervisor aprueba, pero el ajuste se aplica
 * cuando el analista lo ejecuta. Si la deuda ya fue informada a otro módulo por
 * `overdueDebt`, la corrección **no** se comunica republicando ese evento —se
 * interpretaría como una deuda nueva—, sino con `debtUpdated`, que todavía no está
 * en el contrato y queda registrado como pendiente de acuerdo intermodular.
 */
export const debtAdjustmentService = {
  async list({ status = "", debtId = "" } = {}) {
    const params = new URLSearchParams({ status, debtId });
    return request(`/api/v1/debt-adjustments?${params}`);
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

  /** El Supervisor autoriza o rechaza. Autorizar no aplica el cambio todavía. */
  async resolve({ adjustmentId, status, resolvedBy, resolverRole, reason }) {
    const action = status === "REJECTED" ? "reject" : "approve";
    return request(`/api/v1/adjustments/${adjustmentId}/${action}`, { method: "POST", body: action === "reject" ? { reason } : { observation: reason ?? null } });
  },

  /**
   * ExecuteDebtAdjustmentRequest: aplica el cambio autorizado.
   *
   * Si la deuda ya viajó a M8 en `overdueDebt`, se registra `debtUpdated` con los
   * valores anterior y posterior. No se republica `overdueDebt`: el consumidor lo
   * leería como una deuda distinta y la contaría dos veces.
   */
  async execute({ adjustmentId, executedBy }) {
    return request(`/api/v1/adjustments/${adjustmentId}`);
  },
};

// ----------------------------------------------------------------------- Boletas

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

// ------------------------------------------------------------------------- Pagos

export const paymentService = {
  async list({ taxpayerId = "", status = "", date = "", registeredBy = "" } = {}) {
    const params = new URLSearchParams({ taxpayerId, status, date, registeredBy });
    return request(`/api/v1/payments?${params}`);
  },

  /**
   * RegisterPaymentRequest → PaymentResponse
   *
   * `channel` es por dónde entró el dinero (ventanilla, homebanking…); `method` es el
   * instrumento con el que pagó el contribuyente y `registeredBy` el agente responsable.
   */
  async register({ taxpayerId, debtId, amountPaid, channel, paidAt, method, registeredBy }) {
    return request("/api/v1/payments", {
      method: "POST",
      body: { taxpayerId, debtId, amountPaid, channel, paidAt, method, registeredBy },
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
};

// ------------------------------------------------------------ Saldos a favor

/**
 * Saldos a favor.
 *
 * Aplicar un saldo **no genera un pago nuevo** ni publica `paymentRegistered`: ese
 * dinero ya se registró cuando el saldo se creó. Volver a publicarlo contabilizaría
 * dos veces el mismo importe. La aplicación es una operación interna de M5.
 */
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
    return request(`/api/v1/credit-balances/${creditId}/applications`, {
      method: "POST",
      body: { debtId, amount },
    });
  },
};

// -------------------------------------------------------------- Planes de pago

export const paymentPlanService = {
  async list({ status = "", internalStatus = "" } = {}) {
    const params = new URLSearchParams({ status, internalStatus });
    return request(`/api/v1/payment-plans?${params}`);
  },

  /**
   * RequestPaymentPlanRequest → publica paymentPlanRequested.
   * La solicitud nace en el contribuyente; Rentas la resuelve después.
   */
  async request({ taxpayerId, debtIds, installments, downPayment = 0 }) {
    return request("/api/v1/payment-plans", {
      method: "POST",
      body: { taxpayerId, debtIds, installments, downPayment },
    });
  },

  /**
   * Simula el plan: interés fijo del 5% por cada 3 cuotas.
   *
   * El anticipo se paga al contado y sale de la base financiada, así que baja el
   * interés y la cuota. Sin anticipo el cálculo es el de siempre.
   */
  simulate({ totalDebt, installments, downPayment = 0 }) {
    const n = Number(installments) || 1;
    const total = Number(totalDebt);
    const anticipo = Math.min(Math.max(Number(downPayment) || 0, 0), total);

    const financedAmount = round2(total - anticipo);
    const interestRate = Math.floor(n / 3) * 0.05;
    const interestAmount = round2(financedAmount * interestRate);
    const totalAmount = round2(anticipo + financedAmount + interestAmount);

    return {
      installments: n,
      interestRate,
      downPayment: round2(anticipo),
      financedAmount,
      interestAmount,
      totalAmount,
      // La diferencia por redondeo se absorbe en la última cuota al generar el plan.
      installmentAmount: round2((financedAmount + interestAmount) / n),
    };
  },

  /**
   * Deriva la solicitud al Supervisor (EscalatePaymentPlanRequest).
   *
   * Es un estado **interno de M5**: no se publica nada. El contrato con el exterior
   * sólo contempla la resolución final (GRANTED | REJECTED), así que inventar un
   * estado intermodular para la derivación rompería el acuerdo con los otros módulos.
   */
  async escalate({ requestId, escalatedBy, note }) {
    return request(`/api/v1/payment-plan-requests/${requestId}/submit-exception`, {
      method: "POST",
      body: { reason: note },
    });
  },

  /** ResolvePaymentPlanRequest → publica updatePaymentPlanStatus (GRANTED | REJECTED). */
  async resolve({ requestId, status, installments, reason, resolvedBy, resolverRole }) {
    const action = status === "REJECTED" ? "reject" : "grant";
    return request(`/api/v1/payment-plan-requests/${requestId}/${action}`, { method: "POST", body: action === "reject" ? { reason } : { downPaymentAmount: 0 } });
  },
};

// -------------------------------------------------------------- Refinanciación

export const refinancingService = {
  /** Planes que el operador puede refinanciar, con el motivo cuando no se puede. */
  async eligiblePlans({ taxpayerId = "", onlyEligible = false } = {}) {
    const params = new URLSearchParams({ taxpayerId, size: "100" });
    const plans = await request(`/api/v1/payment-plans?${params}`);
    return plans.map((plan) => ({ ...plan, eligible: plan.status === "EXPIRED", reasons: plan.status === "EXPIRED" ? [] : ["El backend sólo admite refinanciar planes vencidos."], outstandingAmount: plan.outstandingAmount })).filter((plan) => !onlyEligible || plan.eligible);
  },

  /**
   * Simula la refinanciación sobre el saldo vivo del plan, no sobre la deuda original:
   * lo ya pagado no se vuelve a financiar.
   */
  simulate({ outstandingAmount, installments, downPayment = 0 }) {
    return paymentPlanService.simulate({
      totalDebt: outstandingAmount,
      installments,
      downPayment,
    });
  },

  /**
   * RequestRefinancingRequest. La solicitud es interna: no se publica nada por el
   * solo hecho de pedirla. El plan vigente sigue igual hasta que se apruebe.
   */
  async request({ planId, installments, downPayment = 0, requestedBy, note }) {
    return request(`/api/v1/payment-plans/${planId}/refinancing-requests`, {
      method: "POST",
      body: { installments },
    });
  },

  /** Deriva la evaluación al Supervisor. Igual que en los planes, es estado interno. */
  async escalate({ requestId, escalatedBy, note }) {
    return request(`/api/v1/refinancing-requests/${requestId}/submit-exception`, {
      method: "POST",
      body: { reason: note },
    });
  },

  /**
   * ResolveRefinancingRequest. La refinanciación se hace efectiva recién al aprobarse.
   *
   * El plan original **no se elimina**: pasa a `REFINANCED` y conserva sus cuotas, sus
   * pagos y su resolución como antecedente, enlazado con el plan que lo reemplaza.
   */
  async resolve({ requestId, status, resolvedBy, resolverRole, reason }) {
    const exceptional = resolverRole === "SUPERVISOR";
    const action = status === "REJECTED" ? (exceptional ? "reject-exception" : "reject") : (exceptional ? "approve-exception" : "grant");
    return request(`/api/v1/refinancing-requests/${requestId}/${action}`, { method: "POST", body: action.includes("reject") ? { reason } : { observation: reason ?? null } });
  },

  async list({ status = "" } = {}) {
    const params = new URLSearchParams({ status });
    return request(`/api/v1/refinancing-requests?${params}`);
  },
};

// ------------------------------------------------------------------ Exenciones

export const exemptionService = {
  async list({ status = "", internalStatus = "" } = {}) {
    const params = new URLSearchParams({ status, size: "100" });
    return request(`/api/v1/exemption-requests?${params}`);
  },

  /**
   * Estado interno del trámite. M8 sólo conoce APPROVED y REJECTED: los pasos de
   * documentación y revisión son de M5 y no se publican.
   */
  async advanceWorkflow({ requestId, internalStatus, note, actor }) {
    const action = { PENDING_REVIEW: "start-review", DOCUMENTATION_REQUIRED: "request-documentation", PENDING_RESOLUTION: "submit-resolution" }[internalStatus];
    if (!action) throw new ApiError("Ese paso requiere adjuntar documentación mediante su operación específica.", 409);
    return request(`/api/v1/exemption-requests/${requestId}/${action}`, { method: "POST", body: action === "request-documentation" ? { message: note } : action === "submit-resolution" ? { observation: note ?? null } : undefined });
  },

  /** Registra la documentación que el ciudadano presentó por mesa de entradas. */
  async attachDocumentation({ requestId, attachments, actor }) {
    if (!attachments?.length) throw new ApiError("Adjuntá al menos un archivo.", 400);
    let result;
    for (const file of attachments) {
      result = await request(`/api/v1/exemption-requests/${requestId}/documentation`, { method: "POST", body: { externalDocumentId: file.externalDocumentId ?? file.name ?? String(file), documentType: file.type ?? "DOCUMENT", fileName: file.name ?? String(file) } });
    }
    return result;
  },

  /** Alta de solicitud por mesa de entradas → publica exemptionRequested hacia M8. */
  async requestExemption({
    citizenId,
    conceptCode,
    reason,
    requestedPercentage,
    requestedFrom,
    requestedUntil,
    attachments = [],
  }) {
    const concept = await apiConceptByCode(conceptCode);
    const created = await request("/api/v1/exemption-requests", {
      method: "POST",
      body: {
        taxpayerId: citizenId,
        taxConceptId: concept.id,
        reason,
        percentage: requestedPercentage,
        validFrom: requestedFrom,
        validUntil: requestedUntil,
      },
    });
    if (attachments.length) await exemptionService.attachDocumentation({ requestId: created.id, attachments });
    return created;
  },

  /** ResolveExemptionRequest → publica updateExemptionStatus (APPROVED | REJECTED). */
  async resolve({ requestId, status, percentage, validFrom, validUntil, reason, resolvedBy }) {
    const action = status === "REJECTED" ? "reject" : "approve";
    return request(`/api/v1/exemption-requests/${requestId}/${action}`, { method: "POST", body: action === "reject" ? { reason } : { percentage, validFrom, validUntil, observation: reason ?? null } });
  },
};

// --------------------------------------------------------------------- Tickets

export const ticketService = {
  async list({ status = "", priority = "" } = {}) {
    const params = new URLSearchParams({ status, priority });
    return request(`/api/v1/tickets?${params}`);
  },

  /** UpdateTicketStatusRequest → publica updateTicketStatus hacia M2. */
  async updateStatus({ ticketId, status, reason, assignedTo }) {
    return request(`/api/v1/tickets/${ticketId}/status`, {
      method: "PUT",
      body: { status, reason },
    });
  },
};

// ------------------------------------------------------------ Bitácora de eventos

export const eventService = {
  async list({ direction = "", status = "", eventType = "" } = {}) {
    const params = new URLSearchParams({ direction, status, eventType });
    return request(`/api/v1/events?${params}`);
  },

  /** Reproceso manual de un evento en DLQ (el consumidor es idempotente vía eventId). */
  async retry(eventId) {
    return request(`/api/v1/events/${eventId}/retry`, { method: "POST" });
  },
};

// -------------------------------------------------------------------------- Caja

/**
 * Operaciones de la ventanilla de caja.
 *
 * El cajero cobra: busca al contribuyente o su papel, imputa el pago a la deuda y
 * entrega el comprobante. No liquida, no resuelve planes ni exenciones y no reversa
 * — eso queda en el área de trabajo de Personal de Rentas.
 */
export const cashierService = {
  /** Búsqueda unificada: documento, CUIT, nombre, N° de boleta o N° de deuda. */
  async search({ query = "" } = {}) {
    const params = new URLSearchParams({ query });
    return request(`/api/v1/cashier/search?${params}`);
  },

  /**
   * Contexto de cobro de un resultado de búsqueda: qué se cobra y a quién.
   * Por contribuyente devuelve todas sus deudas con saldo; por boleta o deuda,
   * sólo la obligación elegida.
   */
  async chargeContext({ kind, id }) {
    return request(`/api/v1/cashier/charge-context/${kind}/${id}`);
  },

  /** RegisterCounterPaymentRequest → CounterPaymentReceiptResponse */
  async registerCounterPayment({ debtId, billId, amountPaid, method, registeredBy }) {
    const debt = await request(`/api/v1/debts/${debtId}`);
    return request("/api/v1/cashier/payments", {
      method: "POST",
      body: { taxpayerId: debt.taxpayerId, debtId, billId, amountPaid, method, registeredBy },
    });
  },

  /** Reimpresión: el comprobante de un pago ya registrado. */
  async receipt(paymentId) {
    return request(`/api/v1/cashier/receipts/${paymentId}`);
  },

  /** Ficha de ventanilla: deudas, pagos y boletas del contribuyente en una consulta. */
  async taxpayerFile(taxpayerId) {
    return request(`/api/v1/cashier/taxpayers/${taxpayerId}/file`);
  },

  /** Agentes que pueden figurar como responsables de un cobro. */
  async agents() {
    return request("/api/v1/cashier/agents");
  },

  /** Resumen de la jornada del cajero: lo que muestra el panel de caja. */
  async dailySummary({ registeredBy = "", date } = {}) {
    const params = new URLSearchParams({ registeredBy, date: date ?? "" });
    return request(`/api/v1/cashier/daily-summary?${params}`);
  },
};

// ---------------------------------------------------------------------- Auditoría

/**
 * Consultas del área de Auditoría.
 *
 * Todas son de lectura: el auditor observa el circuito completo —liquidación, deuda,
 * pago, plan, exención, ticket, evento— y la traza de quién hizo cada cosa, pero no
 * puede modificar ninguna entidad. No hay una sola operación de escritura acá.
 */
export const auditService = {
  /** Panel del auditor: volumen de la jornada, desvíos y actividad reciente. */
  async dashboard() {
    return request("/api/v1/audit/dashboard");
  },

  // ------------------------------------------------------------ Contribuyentes

  async taxpayers({ query = "" } = {}) {
    const params = new URLSearchParams({ query });
    return request(`/api/v1/audit/taxpayers?${params}`);
  },

  /** Ficha 360°: lo que el auditor necesita para reconstruir la situación fiscal. */
  /**
   * Ficha 360° del contribuyente.
   *
   * El backend expone cada frente por separado; la pantalla los cruza en una sola
   * vista, así que la composición es del cliente. Sin esto la ficha llega sin
   * colecciones y la pantalla se rompe al contar las deudas.
   */
  async taxpayerFile(taxpayerId) {
    const [taxpayer, settlements, debts, payments, plans, exemptions, credits] = await Promise.all([
      request(`/api/v1/taxpayers/${taxpayerId}`),
      request(`/api/v1/liquidations?taxpayerId=${taxpayerId}&size=100`),
      request(`/api/v1/taxpayers/${taxpayerId}/debts?size=100`),
      request(`/api/v1/taxpayers/${taxpayerId}/payments?size=100`),
      request(`/api/v1/taxpayers/${taxpayerId}/payment-plans?size=100`),
      request(`/api/v1/taxpayers/${taxpayerId}/exemptions?size=100`),
      request(`/api/v1/taxpayers/${taxpayerId}/credit-balances?size=100`),
    ]);
    const suma = (filas, campo) => filas.reduce((total, fila) => total + Number(fila[campo] ?? 0), 0);
    return {
      taxpayer,
      settlements,
      debts,
      payments,
      plans,
      exemptions,
      credits,
      totals: {
        totalDebt: suma(debts, "outstandingAmount"),
        overdueDebt: suma(debts.filter((d) => d.status === "OVERDUE"), "outstandingAmount"),
        creditBalance: suma(credits, "availableAmount"),
      },
    };
  },

  // ------------------------------------------------------------------ Conceptos

  async concepts({ query = "", type = "", status = "" } = {}) {
    const params = new URLSearchParams({ query, type, status });
    return request(`/api/v1/audit/concepts?${params}`);
  },

  /**
   * Ficha del concepto con su historial de versiones.
   *
   * El backend guarda concepto y configuraciones por separado; la comparación de
   * versiones del auditor necesita las dos cosas juntas, igual que la pantalla de
   * configuración. Sin esto `concept.versions` llega indefinido.
   */
  async conceptDetail(code) {
    const concepto = (await apiTaxConfigModel()).find((item) => item.code === code);
    if (!concepto) throw new ApiError("Concepto inexistente.", 404);
    return concepto;
  },

  // -------------------------------------------------------------- Liquidaciones

  async settlements({ taxpayer = "", conceptCode = "", status = "", from = "", to = "" } = {}) {
    const params = new URLSearchParams({ taxpayer, conceptCode, status, from, to });
    return request(`/api/v1/audit/settlements?${params}`);
  },

  async settlementDetail(id) {
    return request(`/api/v1/audit/settlements/${id}`);
  },

  // --------------------------------------------------------------------- Deudas

  async debts({ taxpayer = "", conceptCode = "", status = "", from = "", to = "" } = {}) {
    const params = new URLSearchParams({ taxpayer, conceptCode, status, from, to });
    return request(`/api/v1/audit/debts?${params}`);
  },

  async debtDetail(id) {
    return request(`/api/v1/audit/debts/${id}`);
  },

  // ---------------------------------------------------------------------- Pagos

  /** `tab` refleja las solapas del listado: registrados, sin imputar, saldos a favor. */
  async payments({ tab = "REGISTERED", taxpayer = "", method = "", from = "", to = "" } = {}) {
    const params = new URLSearchParams({ tab, taxpayer, method, from, to });
    return request(`/api/v1/audit/payments?${params}`);
  },

  async paymentDetail(id) {
    return request(`/api/v1/audit/payments/${id}`);
  },

  // ---------------------------------------------------------------- Reversiones

  async reversals({ paymentId = "", username = "", from = "", to = "" } = {}) {
    const params = new URLSearchParams({ paymentId, username, from, to });
    return request(`/api/v1/audit/reversals?${params}`);
  },

  async reversalDetail(id) {
    return request(`/api/v1/audit/reversals/${id}`);
  },

  // -------------------------------------------------------------- Planes de pago

  async plans({ taxpayer = "", status = "", lifecycle = "", from = "", to = "" } = {}) {
    const params = new URLSearchParams({ taxpayer, status, lifecycle, from, to });
    return request(`/api/v1/audit/payment-plans?${params}`);
  },

  async planDetail(requestId) {
    return request(`/api/v1/audit/payment-plans/${requestId}`);
  },

  // ----------------------------------------------------------------- Exenciones

  async exemptions({ tab = "", taxpayer = "", conceptCode = "", from = "", to = "" } = {}) {
    const params = new URLSearchParams({ tab, taxpayer, conceptCode, from, to });
    return request(`/api/v1/audit/exemptions?${params}`);
  },

  async exemptionDetail(requestId) {
    return request(`/api/v1/audit/exemptions/${requestId}`);
  },

  // -------------------------------------------------------------------- Tickets

  async tickets({ taxpayer = "", subject = "", status = "", priority = "", from = "", to = "" } = {}) {
    const params = new URLSearchParams({ taxpayer, subject, status, priority, from, to });
    return request(`/api/v1/audit/tickets?${params}`);
  },

  async ticketDetail(ticketId) {
    return request(`/api/v1/audit/tickets/${ticketId}`);
  },

  // --------------------------------------------------------------- Integraciones

  async integrations({ sourceModule = "", eventType = "", status = "", from = "", to = "" } = {}) {
    const params = new URLSearchParams({ sourceModule, eventType, status, from, to });
    return request(`/api/v1/audit/integrations?${params}`);
  },

  async integrationDetail(eventId) {
    return request(`/api/v1/audit/integrations/${eventId}`);
  },

  // ------------------------------------------------------- Registro de auditoría

  async auditTrail({ username = "", role = "", action = "", entityType = "", from = "", to = "" } = {}) {
    const params = new URLSearchParams({ username, role, action, entityType, from, to });
    return request(`/api/v1/audit/trail?${params}`);
  },

  async auditDetail(id) {
    return request(`/api/v1/audit/trail/${id}`);
  },

  // ---------------------------------------------------------------- Indicadores

  /** Indicadores del período. Cada tarjeta se puede abrir para ver qué la compone. */
  async indicators({ from = "", to = "", conceptCode = "" } = {}) {
    const params = new URLSearchParams({ from, to, conceptCode });
    return request(`/api/v1/audit/indicators?${params}`);
  },

  /** Detalle de un indicador: las filas concretas que lo componen. */
  async indicatorBreakdown(key, { from = "", to = "", conceptCode = "", taxpayerId = "" } = {}) {
    throw new ApiError("El backend no expone el breakdown genérico de indicadores.", 501, null, "INDICATOR_BREAKDOWN_UNSUPPORTED");
  },
};

// ------------------------------------------------------- Portal del contribuyente

/**
 * Portal del contribuyente.
 *
 * El ciudadano consulta su propio legajo y puede iniciar dos trámites: pedir un plan
 * de pago —que es la forma de conseguir más plazo— y pedir una exención. Nada más:
 * registrar pagos, emitir boletas y resolver solicitudes son atribuciones del
 * municipio, no del contribuyente.
 *
 * Todas las consultas reciben el `taxpayerId` de la sesión: nadie ve el legajo ajeno.
 */
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
    const [debts, bills] = await Promise.all([
      request(`/api/v1/portal/${taxpayerId}/debts?${params}`),
      request(`/api/v1/portal/${taxpayerId}/bills`),
    ]);
    return debts.map((debt) => ({
      ...debt,
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

  async paymentPlans({ taxpayerId }) {
    return request(`/api/v1/portal/${taxpayerId}/payment-plans`);
  },

  async exemptions({ taxpayerId }) {
    return request(`/api/v1/portal/${taxpayerId}/exemptions`);
  },

  // ---------------------------------------------------------------- Trámites

  /** Simulación previa: el ciudadano ve la cuota antes de mandar la solicitud. */
  simulatePaymentPlan({ totalDebt, installments, downPayment = 0 }) {
    return paymentPlanService.simulate({ totalDebt, installments, downPayment });
  },

  /** Pedir financiar deudas en cuotas → publica paymentPlanRequested. */
  async requestPaymentPlan({ taxpayerId, debtIds, installments, downPayment = 0 }) {
    return paymentPlanService.request({ taxpayerId, debtIds, installments, downPayment });
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

// -------------------------------------------------------------------- Dashboard

export const dashboardService = {
  /** Métricas del panel de inicio, una por módulo funcional. */
  async metrics() {
    return request("/api/v1/dashboard/metrics");
  },
};
