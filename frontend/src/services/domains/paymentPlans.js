import { allPages } from "./shared.js";
import { request } from "../apiClient.js";
import { taxpayerService } from "./taxpayers.js";

export const paymentPlanService = {
  async list({ status = "", internalStatus = "" } = {}) {
    const params = new URLSearchParams({ status, internalStatus });
    const rows = await allPages(`/api/v1/payment-plan-requests?${params}`);
    return Promise.all(rows.map(async (row) => {
      const debts = await request(`/api/v1/payment-plan-requests/${row.requestId}/debts`).catch(() => []);
      return { ...row, debts, debtIds: debts.map((debt) => debt.id) };
    }));
  },

  /**
   * RequestPaymentPlanRequest → publica paymentPlanRequested.
   * La solicitud nace en el contribuyente; Rentas la resuelve después.
   */
  async request({ taxpayerId, debtIds, installments }) {
    return request("/api/v1/payment-plan-requests", {
      method: "POST",
      body: { taxpayerId, debtIds, installments },
    });
  },

  /** Calcula con la configuración versionada vigente del backend. */
  async simulate({ taxpayerId, debtIds, installments }) {
    return request("/api/v1/payment-plans/simulations", {
      method: "POST",
      body: { taxpayerId, debtIds, installments },
    });
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
  async resolve({ requestId, status, installments, downPayment, reason, resolvedBy, resolverRole }) {
    const exceptional = resolverRole === "SUPERVISOR";
    if (exceptional && status !== "REJECTED") {
      await request(`/api/v1/payment-plan-requests/${requestId}/approve-exception`, {
        method: "POST",
        body: { observation: reason || null },
      });
      return request(`/api/v1/payment-plan-requests/${requestId}/grant`, {
        method: "POST",
        body: downPayment == null ? undefined : { downPaymentAmount: downPayment },
      });
    }
    const action = status === "REJECTED" ? (exceptional ? "reject-exception" : "reject") : "grant";
    return request(`/api/v1/payment-plan-requests/${requestId}/${action}`, {
      method: "POST",
      body: action.includes("reject") ? { reason } : downPayment == null ? undefined : { downPaymentAmount: downPayment },
    });
  },
};

export const paymentPlanConfigurationService = {
  async list() {
    return allPages("/api/v1/payment-plan-configurations?sort=version,desc");
  },

  async create(configuration) {
    return request("/api/v1/payment-plan-configurations", {
      method: "POST",
      body: configuration,
    });
  },
};

export const planExpirationService = {
  async defaulted() {
    return allPages("/api/v1/payment-plans/defaulted?sort=expiredAt,desc");
  },

  async defaultedDetail(planId) {
    const [plan, installments] = await Promise.all([
      request(`/api/v1/payment-plans/${planId}`),
      request(`/api/v1/payment-plans/${planId}/installments`),
    ]);
    const [taxpayer, configuration] = await Promise.all([
      taxpayerService.getById(plan.taxpayerId),
      plan.configurationId
        ? request(`/api/v1/payment-plan-configurations/${plan.configurationId}`).catch(() => null)
        : null,
    ]);
    return { ...plan, installments, taxpayer, configuration };
  },

  async requests({ status = "" } = {}) {
    const params = new URLSearchParams({ status, size: "100", sort: "requestedAt,desc" });
    return request(`/api/v1/payment-plan-expirations?${params}`);
  },

  async request({ planId, reason }) {
    return request(`/api/v1/payment-plans/${planId}/expiration-requests`, {
      method: "POST",
      body: { reason },
    });
  },

  async detail(id) {
    const expiration = await request(`/api/v1/payment-plan-expirations/${id}`);
    const plan = await planExpirationService.defaultedDetail(expiration.paymentPlanId);
    return { ...expiration, plan };
  },

  async resolve({ id, status, reason = "" }) {
    const action = status === "REJECTED" ? "reject" : "approve";
    return request(`/api/v1/payment-plan-expirations/${id}/${action}`, {
      method: "POST",
      body: action === "reject" ? { reason } : { observation: reason || null },
    });
  },
};

export const refinancingService = {
  /** Planes que el operador puede refinanciar, con el motivo cuando no se puede. */
  async eligiblePlans({ taxpayerId = "", onlyEligible = false } = {}) {
    const params = new URLSearchParams({ taxpayerId, size: "100" });
    const plans = await allPages(`/api/v1/payment-plans?${params}`);
    const configurationIds = [...new Set(plans.filter((plan) => plan.status === "ACTIVE").map((plan) => plan.configurationId))];
    const configurations = new Map(await Promise.all(configurationIds.map(async (id) => [id, await request(`/api/v1/payment-plan-configurations/${id}`)])));
    return plans.map((plan) => {
      const configuration = configurations.get(plan.configurationId);
      const reasons = [];
      if (plan.status !== "ACTIVE") reasons.push("El backend sólo admite refinanciar planes activos.");
      else if (!configuration.refinancingAllowed) reasons.push("La configuración de este plan no permite refinanciar.");
      else if (plan.refinancingCount >= configuration.maxRefinancingCount) reasons.push("El plan alcanzó el máximo de refinanciaciones.");
      return { ...plan, eligible: reasons.length === 0, reasons };
    }).filter((plan) => !onlyEligible || plan.eligible);
  },

  /** El backend calcula el saldo vivo y la propuesta para el plan indicado. */
  simulate({ planId, installments }) {
    return request(`/api/v1/payment-plans/${planId}/refinancing/simulations`, {
      method: "POST",
      body: { installments },
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
    if (status === "REJECTED") {
      const action = exceptional ? "reject-exception" : "reject";
      const rejected = await request(`/api/v1/refinancing-requests/${requestId}/${action}`, {
        method: "POST",
        body: { reason },
      });
      return { ...rejected, reason };
    }
    if (exceptional) {
      await request(`/api/v1/refinancing-requests/${requestId}/approve-exception`, {
        method: "POST",
        body: { observation: reason ?? null },
      });
    }
    const granted = await request(`/api/v1/refinancing-requests/${requestId}/grant`, { method: "POST" });
    const [previousPlan, newPlan] = await Promise.all([
      request(`/api/v1/payment-plans/${granted.planId}`),
      request(`/api/v1/payment-plans/${granted.newPaymentPlanId}`),
    ]);
    return { ...granted, previousPlan, newPlan };
  },

  async list({ status = "" } = {}) {
    const params = new URLSearchParams({ status });
    const rows = await request(`/api/v1/refinancing-requests?${params}`);
    return Promise.all(rows.map(async (row) => ({
      ...row,
      originalPlan: await request(`/api/v1/payment-plans/${row.planId}`).catch(() => null),
    })));
  },
};

