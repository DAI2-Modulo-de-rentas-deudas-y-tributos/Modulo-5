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
  conceptDefinitions: db.conceptDefinitions.map((c) => ({
    ...c,
    versions: c.versions.map((v) => ({ ...v })),
  })),
  settlements: db.settlements.map((s) => ({ ...s })),
  debts: db.debts.map((d) => ({ ...d })),
  bills: db.bills.map((b) => ({ ...b })),
  payments: db.payments.map((p) => ({ ...p })),
  paymentPlans: db.paymentPlans.map((p) => ({ ...p })),
  exemptions: db.exemptions.map((e) => ({ ...e })),
  tickets: db.tickets.map((t) => ({ ...t })),
  eventLog: db.eventLog.map((e) => ({ ...e })),
  refinancings: db.refinancings.map((r) => ({ ...r })),
  debtAdjustments: db.debtAdjustments.map((a) => ({ ...a })),
  reversals: db.reversals.map((r) => ({ ...r })),
  creditBalances: db.creditBalances.map((c) => ({ ...c })),
  auditLog: db.auditLog.map((a) => ({ ...a })),
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

/**
 * Estado interno de una solicitud de plan. No viaja a ningún módulo: el contrato
 * sólo conoce REQUESTED, GRANTED y REJECTED. Las solicitudes que nunca se derivaron
 * se leen como PENDING_REVIEW sin necesidad de marcarlas en el dataset.
 */
const internalStatusOf = (plan) =>
  plan.internalStatus ?? (plan.status === "REQUESTED" ? "PENDING_REVIEW" : plan.status);

/**
 * Pasos internos del trámite de exención. Son de M5: el contrato con M8 sólo
 * contempla la resolución final (APPROVED | REJECTED).
 */
const EXEMPTION_WORKFLOW = [
  "PENDING_REVIEW",
  "DOCUMENTATION_REQUIRED",
  "DOCUMENTATION_RECEIVED",
  "PENDING_RESOLUTION",
];

const exemptionInternalStatusOf = (exemption) =>
  exemption.internalStatus ??
  (exemption.status === "REQUESTED" ? "PENDING_REVIEW" : exemption.status);

/**
 * Día calendario en la zona del municipio (UTC-3), no en la del navegador.
 *
 * Todo el dataset está fechado en -03:00. Si el día se calculara con el reloj local,
 * el mismo cobro caería en días distintos según dónde corra —en UTC un movimiento de
 * la tarde se pasa al día siguiente— y desaparecería del resumen de la jornada.
 */
const businessDayOf = (value) => {
  if (typeof value === "string" && value.length === 10) return value;
  const instante = new Date(value).getTime();
  return new Date(instante - 3 * 3600000).toISOString().slice(0, 10);
};

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


// ------------------------------------------------- Configuración de tributos

/** Estados por los que pasa una versión de configuración antes de regir. */
const CONFIG_FLOW = ["DRAFT", "PENDING_APPROVAL", "ACTIVE"];

const conceptByCode = (code) => store.conceptDefinitions.find((c) => c.code === code) ?? null;

/** Versión que rige hoy: la que quedó activa tras la última aprobación. */
const activeVersionOf = (concept) =>
  (concept.versions ?? []).find((v) => v.status === "ACTIVE") ?? null;

const nextVersionNumber = (concept) =>
  Math.max(0, ...(concept.versions ?? []).map((v) => v.version)) + 1;

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
    if (!USE_MOCKS) {
      const params = new URLSearchParams({ type, status });
      return request(`/api/v1/tax-config?${params}`);
    }
    await delay();
    return store.conceptDefinitions
      .filter((c) => (!type || c.type === type) && (!status || c.status === status))
      .map((concept) => ({
        ...concept,
        activeVersion: activeVersionOf(concept),
        pendingVersion:
          (concept.versions ?? []).find((v) => v.status === "PENDING_APPROVAL") ?? null,
        draftVersion: (concept.versions ?? []).find((v) => v.status === "DRAFT") ?? null,
      }));
  },

  /** Ficha del concepto con todo su historial de versiones. */
  async detail(code) {
    if (!USE_MOCKS) return request(`/api/v1/tax-config/${code}`);
    await delay();
    const concept = conceptByCode(code);
    if (!concept) throw new ApiError("Concepto inexistente.", 404);
    return {
      ...concept,
      activeVersion: activeVersionOf(concept),
      // Cuántas liquidaciones dependen de este concepto: no se borra, se desactiva.
      settlementCount: store.settlements.filter((s) => s.conceptCode === code).length,
      openDebtCount: store.debts.filter(
        (d) => d.conceptCode === code && d.outstandingAmount > 0,
      ).length,
    };
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
    if (!USE_MOCKS) {
      return request(`/api/v1/tax-config/${code}/versions`, {
        method: "POST",
        body: {
          calculationType,
          rate,
          minimumAmount,
          maximumAmount,
          validFrom,
          validUntil,
          conceptStatus,
          note,
        },
      });
    }
    await delay();
    const concept = conceptByCode(code);
    if (!concept) throw new ApiError("Concepto inexistente.", 404);

    if ((concept.versions ?? []).some((v) => CONFIG_FLOW.slice(0, 2).includes(v.status))) {
      throw new ApiError("El concepto ya tiene una versión en curso: resolvela primero.", 409);
    }
    if (calculationType === "PORCENTAJE" && !(Number(rate) > 0)) {
      throw new ApiError("Una configuración por porcentaje necesita una alícuota mayor a cero.", 400);
    }
    if (
      minimumAmount !== null &&
      maximumAmount !== null &&
      Number(maximumAmount) < Number(minimumAmount)
    ) {
      throw new ApiError("El máximo no puede ser menor al mínimo.", 400);
    }
    if (!validFrom || !validUntil || validUntil <= validFrom) {
      throw new ApiError("La vigencia tiene que terminar después de empezar.", 400);
    }
    // Las versiones se suceden, no conviven: si se solaparan, dos reglas distintas
    // regirían el mismo día y no se sabría con cuál se liquidó.
    const vigente = activeVersionOf(concept);
    if (vigente?.validUntil && validFrom <= vigente.validUntil) {
      throw new ApiError(
        `La v${vigente.version} rige hasta el ${vigente.validUntil}: la nueva tiene que empezar después.`,
        409,
      );
    }
    if (!note?.trim()) {
      throw new ApiError("Describí el cambio: queda en el historial de versiones.", 400);
    }

    const version = {
      version: nextVersionNumber(concept),
      date: validFrom,
      status: "DRAFT",
      user: requestedBy,
      note,
      calculationType,
      rate: calculationType === "PORCENTAJE" ? Number(rate) : null,
      minimumAmount: minimumAmount === null || minimumAmount === "" ? null : Number(minimumAmount),
      maximumAmount: maximumAmount === null || maximumAmount === "" ? null : Number(maximumAmount),
      validFrom,
      validUntil,
      // Una versión también puede proponer dar de baja el concepto.
      conceptStatus: conceptStatus ?? concept.status,
      requestedAt: nowIso(),
      requestedBy,
      resolvedAt: null,
      resolvedBy: null,
      reason: null,
    };
    concept.versions = [version, ...(concept.versions ?? [])];
    return version;
  },

  /** El borrador pasa a la bandeja del Supervisor. */
  async submitForApproval({ code, version, requestedBy }) {
    if (!USE_MOCKS) {
      return request(`/api/v1/tax-config/${code}/versions/${version}/submit`, { method: "POST" });
    }
    await delay();
    const concept = conceptByCode(code);
    if (!concept) throw new ApiError("Concepto inexistente.", 404);
    const target = (concept.versions ?? []).find((v) => v.version === Number(version));
    if (!target) throw new ApiError("Versión inexistente.", 404);
    if (target.status !== "DRAFT") {
      throw new ApiError("Sólo se envían a aprobación las versiones en borrador.", 409);
    }
    target.status = "PENDING_APPROVAL";
    target.submittedBy = requestedBy;
    target.submittedAt = nowIso();
    return target;
  },

  /** Bandeja del Supervisor: todo lo que espera aprobación, de lo más viejo a lo nuevo. */
  async pendingApprovals() {
    if (!USE_MOCKS) return request("/api/v1/tax-config/pending");
    await delay();
    return store.conceptDefinitions
      .flatMap((concept) =>
        (concept.versions ?? [])
          .filter((v) => v.status === "PENDING_APPROVAL")
          .map((v) => ({
            ...v,
            code: concept.code,
            name: concept.name,
            type: concept.type,
            currentVersion: activeVersionOf(concept),
          })),
      )
      .sort((a, b) => String(a.submittedAt).localeCompare(String(b.submittedAt)));
  },

  /**
   * ResolveTaxConfigVersionRequest. Al aprobar, la versión pasa a regir y la anterior
   * queda inactiva: se conserva el historial completo, nunca se borra una versión.
   */
  async resolveVersion({ code, version, status, resolvedBy, resolverRole, reason }) {
    if (!USE_MOCKS) {
      return request(`/api/v1/tax-config/${code}/versions/${version}/status`, {
        method: "PUT",
        body: { status, reason },
      });
    }
    await delay();
    if (resolverRole !== "SUPERVISOR") {
      throw new ApiError("Sólo el Supervisor aprueba o rechaza una configuración.", 403);
    }
    const concept = conceptByCode(code);
    if (!concept) throw new ApiError("Concepto inexistente.", 404);
    const target = (concept.versions ?? []).find((v) => v.version === Number(version));
    if (!target) throw new ApiError("Versión inexistente.", 404);
    if (target.status !== "PENDING_APPROVAL") {
      throw new ApiError("La versión no está esperando aprobación.", 409);
    }
    if (status === "REJECTED" && !reason?.trim()) {
      throw new ApiError("El motivo del rechazo es obligatorio.", 400);
    }

    target.resolvedAt = nowIso();
    target.resolvedBy = resolvedBy;

    if (status === "REJECTED") {
      target.status = "REJECTED";
      target.reason = reason;
      return target;
    }

    // La anterior deja de regir pero no se borra: queda como historia consultable.
    const anterior = activeVersionOf(concept);
    if (anterior) anterior.status = "INACTIVE";

    target.status = "ACTIVE";
    // Los parámetros de la versión aprobada pasan a ser los del concepto.
    concept.calculationType = target.calculationType;
    concept.rate = target.rate;
    concept.minimumAmount = target.minimumAmount;
    concept.maximumAmount = target.maximumAmount;
    concept.validFrom = target.validFrom;
    concept.validUntil = target.validUntil;
    concept.status = target.conceptStatus ?? concept.status;

    return { ...target, previousVersion: anterior, concept };
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
      // Sella con qué versión del concepto se calculó: un cambio posterior de la
      // configuración rige hacia adelante y no reescribe esta liquidación.
      conceptVersion: activeVersionOf(conceptByCode(conceptCode) ?? { versions: [] })?.version ?? null,
    };
    store.settlements.unshift(settlement);
    return settlement;
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
    if (!USE_MOCKS) {
      return request("/api/v1/settlements/batch/preview", {
        method: "POST",
        body: { conceptCode, period, baseAmount, dueDate, taxpayerType },
      });
    }
    await delay();

    const base = Number(baseAmount);
    const alcanzados = store.taxpayers.filter((t) => !taxpayerType || t.type === taxpayerType);

    const items = [];
    const errors = [];
    const warnings = [];

    alcanzados.forEach((taxpayer) => {
      // Una liquidación por contribuyente, concepto y período: no se duplica.
      const yaLiquidado = store.settlements.find(
        (s) =>
          s.taxpayerId === taxpayer.id &&
          s.conceptCode === conceptCode &&
          s.period === period,
      );
      if (yaLiquidado) {
        errors.push({
          taxpayerId: taxpayer.id,
          taxpayerName: taxpayer.name,
          reason: `Ya tiene la liquidación #${yaLiquidado.id} para ${conceptCode} ${period}.`,
        });
        return;
      }

      // El descuento sale del beneficio social replicado desde M8.
      const benefit = taxpayer.benefit;
      const aplica =
        benefit?.status === "ACTIVE" && benefit.applicableConceptCodes.includes(conceptCode);
      const discountPercentage = aplica ? benefit.discountPercentage : 0;
      const amount = round2(base * (1 - discountPercentage / 100));

      if (taxpayer.status === "BLOCKED") {
        warnings.push({
          taxpayerId: taxpayer.id,
          taxpayerName: taxpayer.name,
          reason: "Bloqueado en M1: se liquida, pero no emitas la boleta hasta regularizar.",
        });
      }
      if (taxpayer.status === "DECEASED") {
        warnings.push({
          taxpayerId: taxpayer.id,
          taxpayerName: taxpayer.name,
          reason: "Fallecimiento informado por M1: la deuda debe tramitarse con los herederos.",
        });
      }

      items.push({
        taxpayerId: taxpayer.id,
        taxpayerName: taxpayer.name,
        taxpayerType: taxpayer.type,
        document: `${taxpayer.documentType} ${taxpayer.document}`,
        status: taxpayer.status,
        baseAmount: base,
        discountPercentage,
        amount,
      });
    });

    return {
      conceptCode,
      period,
      dueDate,
      items,
      errors,
      warnings,
      totals: {
        toGenerate: items.length,
        skipped: errors.length,
        flagged: warnings.length,
        amount: round2(items.reduce((acc, i) => acc + i.amount, 0)),
        discounted: items.filter((i) => i.discountPercentage > 0).length,
      },
    };
  },

  /**
   * GenerateSettlementBatchRequest → genera el lote en borrador.
   *
   * Queda en `DRAFT` a propósito: emitir doscientas liquidaciones de una es
   * irreversible, así que la emisión sigue siendo un acto explícito por liquidación.
   */
  async generateBatch({ conceptCode, period, baseAmount, dueDate, taxpayerType = "" }) {
    if (!USE_MOCKS) {
      return request("/api/v1/settlements/batch", {
        method: "POST",
        body: { conceptCode, period, baseAmount, dueDate, taxpayerType },
      });
    }

    const preview = await settlementService.previewBatch({
      conceptCode,
      period,
      baseAmount,
      dueDate,
      taxpayerType,
    });
    if (preview.items.length === 0) {
      throw new ApiError("No hay contribuyentes alcanzados: revisá el período y el alcance.", 409);
    }

    const generated = preview.items.map((item) => {
      const settlement = {
        id: nextId(),
        taxpayerId: item.taxpayerId,
        taxpayerType: item.taxpayerType,
        conceptCode,
        period,
        baseAmount: item.baseAmount,
        discountPercentage: item.discountPercentage,
        amount: item.amount,
        dueDate,
        status: "DRAFT",
        createdAt: nowIso(),
        conceptVersion:
          activeVersionOf(conceptByCode(conceptCode) ?? { versions: [] })?.version ?? null,
      };
      store.settlements.unshift(settlement);
      return settlement;
    });

    return {
      generated,
      totals: preview.totals,
      errors: preview.errors,
      warnings: preview.warnings,
    };
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


// ------------------------------------------------------------- Ajuste manual

/** Lo único que un ajuste puede tocar: importe, vencimiento o ambos. */
const ADJUSTABLE_FIELDS = ["outstandingAmount", "dueDate"];

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
    if (!USE_MOCKS) {
      const params = new URLSearchParams({ status, debtId });
      return request(`/api/v1/debt-adjustments?${params}`);
    }
    await delay();
    return store.debtAdjustments
      .filter((a) => (!status || a.status === status) && (!debtId || a.debtId === Number(debtId)))
      .map((a) => {
        const debt = store.debts.find((d) => d.id === a.debtId);
        return {
          ...a,
          taxpayerId: debt?.taxpayerId ?? null,
          conceptName: debt ? conceptName(debt.conceptCode) : null,
          reportedToM8: debt?.reportedToM8 ?? false,
        };
      });
  },

  /** RequestDebtAdjustmentRequest: el analista propone el cambio con su motivo. */
  async request({ debtId, newAmount, newDueDate, reason, requestedBy }) {
    if (!USE_MOCKS) {
      return request("/api/v1/debt-adjustments", {
        method: "POST",
        body: { debtId, newAmount, newDueDate, reason },
      });
    }
    await delay();
    const debt = store.debts.find((d) => d.id === Number(debtId));
    if (!debt) throw new ApiError("Deuda inexistente.", 404);
    if (debt.status === "SETTLED") {
      throw new ApiError("No se ajusta una deuda ya cancelada.", 409);
    }
    if (store.debtAdjustments.some((a) => a.debtId === debt.id && a.status !== "REJECTED" && a.status !== "EXECUTED")) {
      throw new ApiError("La deuda ya tiene un ajuste en curso.", 409);
    }
    if (!reason?.trim()) {
      throw new ApiError("El motivo es obligatorio: queda como respaldo del ajuste.", 400);
    }

    const cambiaImporte = newAmount !== null && newAmount !== "" && Number(newAmount) !== debt.outstandingAmount;
    const cambiaVencimiento = Boolean(newDueDate) && newDueDate !== debt.dueDate;
    if (!cambiaImporte && !cambiaVencimiento) {
      throw new ApiError("Indicá al menos un cambio: importe, vencimiento o ambos.", 400);
    }
    if (cambiaImporte && !(Number(newAmount) >= 0)) {
      throw new ApiError("El importe ajustado no puede ser negativo.", 400);
    }

    const adjustment = {
      id: nextId(),
      debtId: debt.id,
      status: "PENDING_APPROVAL",
      reason,
      requestedBy,
      requestedAt: nowIso(),
      previousAmount: debt.outstandingAmount,
      newAmount: cambiaImporte ? round2(Number(newAmount)) : debt.outstandingAmount,
      previousDueDate: debt.dueDate,
      newDueDate: cambiaVencimiento ? newDueDate : debt.dueDate,
      approvedBy: null,
      approvedAt: null,
      executedBy: null,
      executedAt: null,
      rejectionReason: null,
    };
    store.debtAdjustments.unshift(adjustment);
    return adjustment;
  },

  /** El Supervisor autoriza o rechaza. Autorizar no aplica el cambio todavía. */
  async resolve({ adjustmentId, status, resolvedBy, resolverRole, reason }) {
    if (!USE_MOCKS) {
      return request(`/api/v1/debt-adjustments/${adjustmentId}/status`, {
        method: "PUT",
        body: { status, reason },
      });
    }
    await delay();
    if (resolverRole !== "SUPERVISOR") {
      throw new ApiError("Sólo el Supervisor autoriza un ajuste manual.", 403);
    }
    const adjustment = store.debtAdjustments.find((a) => a.id === Number(adjustmentId));
    if (!adjustment) throw new ApiError("Ajuste inexistente.", 404);
    if (adjustment.status !== "PENDING_APPROVAL") {
      throw new ApiError("El ajuste ya fue resuelto.", 409);
    }
    if (status === "REJECTED" && !reason?.trim()) {
      throw new ApiError("El motivo del rechazo es obligatorio.", 400);
    }

    adjustment.status = status === "REJECTED" ? "REJECTED" : "APPROVED";
    adjustment.approvedBy = resolvedBy;
    adjustment.approvedAt = nowIso();
    if (status === "REJECTED") adjustment.rejectionReason = reason;
    return adjustment;
  },

  /**
   * ExecuteDebtAdjustmentRequest: aplica el cambio autorizado.
   *
   * Si la deuda ya viajó a M8 en `overdueDebt`, se registra `debtUpdated` con los
   * valores anterior y posterior. No se republica `overdueDebt`: el consumidor lo
   * leería como una deuda distinta y la contaría dos veces.
   */
  async execute({ adjustmentId, executedBy }) {
    if (!USE_MOCKS) {
      return request(`/api/v1/debt-adjustments/${adjustmentId}/execute`, { method: "POST" });
    }
    await delay();
    const adjustment = store.debtAdjustments.find((a) => a.id === Number(adjustmentId));
    if (!adjustment) throw new ApiError("Ajuste inexistente.", 404);
    if (adjustment.status !== "APPROVED") {
      throw new ApiError("El ajuste todavía no fue autorizado por el Supervisor.", 409);
    }

    const debt = store.debts.find((d) => d.id === adjustment.debtId);
    if (!debt) throw new ApiError("Deuda inexistente.", 404);

    debt.outstandingAmount = adjustment.newAmount;
    debt.dueDate = adjustment.newDueDate;
    if (debt.outstandingAmount <= 0) {
      debt.status = "SETTLED";
    } else {
      debt.status = new Date(debt.dueDate) < new Date() ? "OVERDUE" : "PENDING";
    }
    debt.history = [
      ...(debt.history ?? []),
      {
        at: nowIso(),
        status: debt.status,
        note: `Ajuste manual #${adjustment.id} ejecutado por ${executedBy}`,
      },
    ];

    adjustment.status = "EXECUTED";
    adjustment.executedBy = executedBy;
    adjustment.executedAt = nowIso();

    // La deuda ya informada cambió: hay que comunicar la actualización, no una nueva.
    if (debt.reportedToM8) {
      recordOutboundEvent("debtUpdated", "M8", {
        debtId: debt.id,
        previousAmount: adjustment.previousAmount,
        newAmount: adjustment.newAmount,
        previousDueDate: adjustment.previousDueDate,
        newDueDate: adjustment.newDueDate,
        version: (debt.version ?? 1) + 1,
      });
      debt.version = (debt.version ?? 1) + 1;
      adjustment.notifiedTo = "M8";
    }

    return { adjustment, debt };
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

  async search({ query = "" } = {}) {
    if (!USE_MOCKS) {
      const params = new URLSearchParams({ query });
      return request(`/api/v1/bills/search?${params}`);
    }
    await delay();
    const term = String(query).trim();
    if (!term) return [];
    return store.bills.filter((b) => matches(b.id, term) || matches(b.barcode, term));
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
  async list({ taxpayerId = "", status = "", date = "", registeredBy = "" } = {}) {
    if (!USE_MOCKS) {
      const params = new URLSearchParams({ taxpayerId, status, date, registeredBy });
      return request(`/api/v1/payments?${params}`);
    }
    await delay();
    return store.payments.filter(
      (p) =>
        (!taxpayerId || p.taxpayerId === Number(taxpayerId)) &&
        (!status || p.status === status) &&
        (!date || businessDayOf(p.paidAt) === date) &&
        (!registeredBy || p.registeredBy === registeredBy),
    );
  },

  /**
   * RegisterPaymentRequest → PaymentResponse
   *
   * `channel` es por dónde entró el dinero (ventanilla, homebanking…); `method` es el
   * instrumento con el que pagó el contribuyente y `registeredBy` el agente responsable.
   */
  async register({ taxpayerId, debtId, amountPaid, channel, paidAt, method, registeredBy }) {
    if (!USE_MOCKS) {
      return request("/api/v1/payments", {
        method: "POST",
        body: { taxpayerId, debtId, amountPaid, channel, paidAt, method, registeredBy },
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
      method: method ?? null,
      registeredBy: registeredBy ?? null,
      // Se congela al cobrar: el comprobante debe decir si la obligación estaba vencida.
      wasOverdue: debt ? debt.status === "OVERDUE" : null,
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
    store.bills
      .filter((bill) => bill.debtId === debt.id && bill.status !== "SETTLED")
      .forEach((bill) => {
        bill.status = "SETTLED";
      });
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
    if (!USE_MOCKS) {
      const params = new URLSearchParams({ taxpayerId, status });
      return request(`/api/v1/credit-balances?${params}`);
    }
    await delay();
    return store.creditBalances
      .filter(
        (c) =>
          (!taxpayerId || c.taxpayerId === Number(taxpayerId)) && (!status || c.status === status),
      )
      .map((credit) => ({
        ...credit,
        remainingAmount: round2(
          credit.amount - (credit.applications ?? []).reduce((acc, a) => acc + a.amount, 0),
        ),
      }));
  },

  /** Deudas del contribuyente a las que se le puede aplicar el saldo. */
  async applicableDebts(creditId) {
    if (!USE_MOCKS) return request(`/api/v1/credit-balances/${creditId}/applicable-debts`);
    await delay();
    const credit = store.creditBalances.find((c) => c.id === Number(creditId));
    if (!credit) throw new ApiError("Saldo a favor inexistente.", 404);
    return store.debts
      .filter((d) => d.taxpayerId === credit.taxpayerId && d.outstandingAmount > 0)
      .map((d) => ({ ...d, conceptName: conceptName(d.conceptCode) }));
  },

  /**
   * ApplyCreditBalanceRequest. Total o parcial, pero nunca más que el saldo
   * disponible ni más que la deuda pendiente.
   */
  async apply({ creditId, debtId, amount, appliedBy }) {
    if (!USE_MOCKS) {
      return request(`/api/v1/credit-balances/${creditId}/applications`, {
        method: "POST",
        body: { debtId, amount },
      });
    }
    await delay();
    const credit = store.creditBalances.find((c) => c.id === Number(creditId));
    if (!credit) throw new ApiError("Saldo a favor inexistente.", 404);
    if (credit.status !== "ACTIVE") throw new ApiError("El saldo a favor ya se consumió.", 409);

    const debt = store.debts.find((d) => d.id === Number(debtId));
    if (!debt) throw new ApiError("Deuda inexistente.", 404);
    if (debt.taxpayerId !== credit.taxpayerId) {
      throw new ApiError("La deuda pertenece a otro contribuyente.", 409);
    }
    if (debt.outstandingAmount <= 0) throw new ApiError("La deuda ya está cancelada.", 409);

    const disponible = round2(
      credit.amount - (credit.applications ?? []).reduce((acc, a) => acc + a.amount, 0),
    );
    const importe = round2(Number(amount));
    if (!(importe > 0)) throw new ApiError("El importe debe ser mayor a cero.", 400);
    if (importe > disponible) {
      throw new ApiError(`El saldo disponible es ${formatMoney(disponible)}.`, 409);
    }
    if (importe > debt.outstandingAmount) {
      throw new ApiError("El importe supera el saldo de la deuda.", 409);
    }

    credit.applications = [
      ...(credit.applications ?? []),
      { debtId: debt.id, amount: importe, at: nowIso(), appliedBy },
    ];
    const restante = round2(disponible - importe);
    if (restante <= 0) {
      credit.status = "SETTLED";
      credit.appliedTo = debt.id;
    }

    debt.outstandingAmount = round2(debt.outstandingAmount - importe);
    debt.history = [
      ...(debt.history ?? []),
      {
        at: nowIso(),
        status: debt.status,
        note: `Saldo a favor #${credit.id} aplicado por ${formatMoney(importe)}`,
      },
    ];

    // Si el saldo cancela la deuda, el módulo de origen tiene que enterarse. El evento
    // no lleva importes, así que informarlo no duplica dinero: sólo cierra la obligación.
    if (debt.outstandingAmount <= 0) {
      debt.outstandingAmount = 0;
      debt.status = "SETTLED";
      store.bills
        .filter((b) => b.debtId === debt.id && b.status !== "SETTLED")
        .forEach((b) => {
          b.status = "SETTLED";
        });
      recordOutboundEvent("debtSettled", originModule(debt.originType), {
        debtId: debt.id,
        originType: debt.originType,
        originId: debt.originId,
        settledAt: nowIso(),
      });
    }

    return {
      credit: { ...credit, remainingAmount: restante },
      debt,
      appliedAmount: importe,
      debtSettled: debt.status === "SETTLED",
    };
  },
};

// -------------------------------------------------------------- Planes de pago

export const paymentPlanService = {
  async list({ status = "", internalStatus = "" } = {}) {
    if (!USE_MOCKS) {
      const params = new URLSearchParams({ status, internalStatus });
      return request(`/api/v1/payment-plans?${params}`);
    }
    await delay();
    return store.paymentPlans
      .filter((p) => !status || p.status === status)
      .map((p) => ({ ...p, internalStatus: internalStatusOf(p) }))
      .filter((p) => !internalStatus || p.internalStatus === internalStatus);
  },

  /**
   * RequestPaymentPlanRequest → publica paymentPlanRequested.
   * La solicitud nace en el contribuyente; Rentas la resuelve después.
   */
  async request({ taxpayerId, debtIds, installments, downPayment = 0 }) {
    if (!USE_MOCKS) {
      return request("/api/v1/payment-plans", {
        method: "POST",
        body: { taxpayerId, debtIds, installments, downPayment },
      });
    }
    await delay();
    const taxpayer = store.taxpayers.find((t) => t.id === Number(taxpayerId));
    if (!taxpayer) throw new ApiError("El contribuyente no existe en el padrón local.", 404);

    const ids = (debtIds ?? []).map(Number);
    if (ids.length === 0) throw new ApiError("Elegí al menos una deuda para financiar.", 400);

    const debts = store.debts.filter((d) => ids.includes(d.id) && d.taxpayerId === taxpayer.id);
    if (debts.length !== ids.length) {
      throw new ApiError("Alguna de las deudas no te pertenece o no existe.", 409);
    }
    if (debts.some((d) => d.outstandingAmount <= 0)) {
      throw new ApiError("No se puede financiar una deuda ya cancelada.", 409);
    }
    // Una deuda no puede entrar en dos planes a la vez.
    const yaEnPlan = store.paymentPlans.find(
      (p) => p.status !== "REJECTED" && p.debtIds.some((id) => ids.includes(id)),
    );
    if (yaEnPlan) {
      throw new ApiError(
        `La deuda #${yaEnPlan.debtIds.find((id) => ids.includes(id))} ya está incluida en la solicitud #${yaEnPlan.requestId}.`,
        409,
      );
    }

    const totalDebt = round2(debts.reduce((acc, d) => acc + d.outstandingAmount, 0));
    const anticipo = Math.min(Math.max(Number(downPayment) || 0, 0), totalDebt);
    const plan = {
      requestId: nextId(),
      planId: null,
      taxpayerId: taxpayer.id,
      taxpayerType: taxpayer.type,
      debtIds: ids,
      totalDebt,
      installments: Number(installments),
      status: "REQUESTED",
      lifecycle: null,
      requestedAt: nowIso(),
      resolvedAt: null,
      resolvedBy: null,
      reason: null,
      // El anticipo lo ofrece el contribuyente; el resto lo fija Rentas al resolver.
      downPayment: anticipo,
      financedAmount: null,
      interestAmount: null,
      schedule: [],
      history: [{ at: nowIso(), status: "REQUESTED", actor: taxpayer.name }],
    };
    store.paymentPlans.unshift(plan);

    recordOutboundEvent("paymentPlanRequested", "M5", {
      requestId: plan.requestId,
      taxpayerType: plan.taxpayerType,
      taxpayerId: plan.taxpayerId,
      debtIds: plan.debtIds,
      totalDebt: plan.totalDebt,
      installments: plan.installments,
    });
    return plan;
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
    if (!USE_MOCKS) {
      return request(`/api/v1/payment-plans/${requestId}/escalate`, {
        method: "POST",
        body: { note },
      });
    }
    await delay();
    const plan = store.paymentPlans.find((p) => p.requestId === Number(requestId));
    if (!plan) throw new ApiError("Solicitud inexistente.", 404);
    if (plan.status !== "REQUESTED") {
      throw new ApiError("La solicitud ya fue resuelta.", 409);
    }
    if (internalStatusOf(plan) === "PENDING_SUPERVISOR") {
      throw new ApiError("La solicitud ya está derivada al Supervisor.", 409);
    }
    if (!note?.trim()) {
      throw new ApiError("Indicá por qué la derivás: el Supervisor necesita el contexto.", 400);
    }

    plan.internalStatus = "PENDING_SUPERVISOR";
    plan.escalatedBy = escalatedBy;
    plan.escalatedAt = nowIso();
    plan.escalationNote = note;
    return plan;
  },

  /** ResolvePaymentPlanRequest → publica updatePaymentPlanStatus (GRANTED | REJECTED). */
  async resolve({ requestId, status, installments, reason, resolvedBy, resolverRole }) {
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
    if (internalStatusOf(plan) === "PENDING_SUPERVISOR" && resolverRole !== "SUPERVISOR") {
      throw new ApiError("La solicitud está derivada: sólo el Supervisor puede resolverla.", 403);
    }

    plan.status = status;
    plan.internalStatus = status === "GRANTED" ? "APPROVED" : "REJECTED";
    plan.resolvedAt = nowIso();
    plan.resolvedBy = resolvedBy;

    if (status === "GRANTED") {
      const simulation = paymentPlanService.simulate({
        totalDebt: plan.totalDebt,
        installments: installments ?? plan.installments,
        downPayment: plan.downPayment,
      });
      plan.planId = nextId();
      plan.installments = simulation.installments;
      plan.financedAmount = simulation.financedAmount;
      plan.interestAmount = simulation.interestAmount;
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


// -------------------------------------------------------------- Refinanciación

/** Saldo vivo de un plan: lo que queda por pagar entre cuotas pendientes y vencidas. */
const planOutstanding = (plan) =>
  round2(
    (plan.schedule ?? [])
      .filter((c) => c.status !== "SETTLED")
      .reduce((acc, c) => acc + c.amount, 0),
  );

const overdueInstallments = (plan) =>
  (plan.schedule ?? []).filter((c) => c.status === "OVERDUE").length;

/**
 * Evalúa si un plan puede refinanciarse y, si no, por qué.
 *
 * Devuelve los motivos en vez de un booleano suelto: el operador tiene que poder
 * explicarle al contribuyente qué le falta. El umbral de cuotas impagas sale de
 * `REFINANCING_RULES`, no está escrito en el código.
 */
function refinancingEligibility(plan) {
  const reasons = [];

  if (plan.status !== "GRANTED") {
    reasons.push("El plan todavía no fue otorgado.");
  }
  if (plan.lifecycle === "FULFILLED") {
    reasons.push("El plan ya está cumplido: no queda saldo que refinanciar.");
  }
  if (plan.lifecycle === "REFINANCED") {
    reasons.push("El plan ya fue refinanciado por otro posterior.");
  }

  const vencidas = overdueInstallments(plan);
  const minimo = db.REFINANCING_RULES.minimumOverdueInstallments;
  if (vencidas < minimo) {
    reasons.push(
      `Requiere al menos ${minimo} cuota${minimo === 1 ? "" : "s"} vencida${minimo === 1 ? "" : "s"} y tiene ${vencidas}.`,
    );
  }

  if (store.refinancings.some((r) => r.planId === plan.planId && r.status !== "REJECTED")) {
    reasons.push("Ya tiene una solicitud de refinanciación en curso.");
  }

  return {
    eligible: reasons.length === 0,
    reasons,
    overdueInstallments: vencidas,
    outstandingAmount: planOutstanding(plan),
  };
}

export const refinancingService = {
  /** Planes que el operador puede refinanciar, con el motivo cuando no se puede. */
  async eligiblePlans({ taxpayerId = "", onlyEligible = false } = {}) {
    if (!USE_MOCKS) {
      const params = new URLSearchParams({ taxpayerId, onlyEligible: String(onlyEligible) });
      return request(`/api/v1/payment-plans/refinancing/eligible?${params}`);
    }
    await delay();
    return store.paymentPlans
      .filter((p) => p.planId && (!taxpayerId || p.taxpayerId === Number(taxpayerId)))
      .map((plan) => ({ ...plan, ...refinancingEligibility(plan) }))
      .filter((plan) => !onlyEligible || plan.eligible);
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
    if (!USE_MOCKS) {
      return request("/api/v1/payment-plans/refinancing", {
        method: "POST",
        body: { planId, installments, downPayment, note },
      });
    }
    await delay();
    const plan = store.paymentPlans.find((p) => p.planId === Number(planId));
    if (!plan) throw new ApiError("Plan inexistente.", 404);

    const evaluacion = refinancingEligibility(plan);
    if (!evaluacion.eligible) throw new ApiError(evaluacion.reasons[0], 409);

    const simulacion = refinancingService.simulate({
      outstandingAmount: evaluacion.outstandingAmount,
      installments,
      downPayment,
    });

    const solicitud = {
      requestId: nextId(),
      planId: plan.planId,
      taxpayerId: plan.taxpayerId,
      outstandingAmount: evaluacion.outstandingAmount,
      overdueInstallments: evaluacion.overdueInstallments,
      installments: simulacion.installments,
      downPayment: simulacion.downPayment,
      totalAmount: simulacion.totalAmount,
      installmentAmount: simulacion.installmentAmount,
      status: "REQUESTED",
      requestedAt: nowIso(),
      requestedBy,
      note: note ?? null,
      resolvedAt: null,
      resolvedBy: null,
      reason: null,
      newPlanId: null,
    };
    store.refinancings.unshift(solicitud);
    return solicitud;
  },

  /** Deriva la evaluación al Supervisor. Igual que en los planes, es estado interno. */
  async escalate({ requestId, escalatedBy, note }) {
    if (!USE_MOCKS) {
      return request(`/api/v1/payment-plans/refinancing/${requestId}/escalate`, {
        method: "POST",
        body: { note },
      });
    }
    await delay();
    const solicitud = store.refinancings.find((r) => r.requestId === Number(requestId));
    if (!solicitud) throw new ApiError("Solicitud inexistente.", 404);
    if (solicitud.status !== "REQUESTED") {
      throw new ApiError("La solicitud ya fue evaluada.", 409);
    }
    if (!note?.trim()) {
      throw new ApiError("Indicá por qué la derivás: el Supervisor necesita el contexto.", 400);
    }
    solicitud.status = "UNDER_REVIEW";
    solicitud.escalatedBy = escalatedBy;
    solicitud.escalatedAt = nowIso();
    solicitud.escalationNote = note;
    return solicitud;
  },

  /**
   * ResolveRefinancingRequest. La refinanciación se hace efectiva recién al aprobarse.
   *
   * El plan original **no se elimina**: pasa a `REFINANCED` y conserva sus cuotas, sus
   * pagos y su resolución como antecedente, enlazado con el plan que lo reemplaza.
   */
  async resolve({ requestId, status, resolvedBy, resolverRole, reason }) {
    if (!USE_MOCKS) {
      return request(`/api/v1/payment-plans/refinancing/${requestId}/status`, {
        method: "PUT",
        body: { status, reason },
      });
    }
    await delay();
    const solicitud = store.refinancings.find((r) => r.requestId === Number(requestId));
    if (!solicitud) throw new ApiError("Solicitud inexistente.", 404);
    if (solicitud.status === "APPROVED" || solicitud.status === "REJECTED") {
      throw new ApiError("La solicitud ya fue resuelta.", 409);
    }
    if (status === "REJECTED" && !reason?.trim()) {
      throw new ApiError("El motivo del rechazo es obligatorio.", 400);
    }
    if (solicitud.status === "UNDER_REVIEW" && resolverRole !== "SUPERVISOR") {
      throw new ApiError("La solicitud está derivada: sólo el Supervisor puede resolverla.", 403);
    }

    solicitud.status = status;
    solicitud.resolvedAt = nowIso();
    solicitud.resolvedBy = resolvedBy;

    if (status === "REJECTED") {
      solicitud.reason = reason;
      return solicitud;
    }

    const original = store.paymentPlans.find((p) => p.planId === solicitud.planId);
    const simulacion = refinancingService.simulate({
      outstandingAmount: solicitud.outstandingAmount,
      installments: solicitud.installments,
      downPayment: solicitud.downPayment,
    });

    const nuevo = {
      requestId: nextId(),
      planId: nextId(),
      taxpayerId: original.taxpayerId,
      taxpayerType: original.taxpayerType,
      debtIds: [...original.debtIds],
      totalDebt: solicitud.outstandingAmount,
      totalAmount: simulacion.totalAmount,
      installments: simulacion.installments,
      status: "GRANTED",
      lifecycle: "CURRENT",
      requestedAt: solicitud.requestedAt,
      resolvedAt: nowIso(),
      resolvedBy,
      reason: null,
      downPayment: simulacion.downPayment,
      financedAmount: simulacion.financedAmount,
      interestAmount: simulacion.interestAmount,
      outstandingAmount: simulacion.totalAmount,
      // Enlace hacia atrás: de dónde vino este plan.
      refinancedFrom: original.planId,
      schedule: Array.from({ length: simulacion.installments }, (_, i) => ({
        number: i + 1,
        dueDate: addMonths(nowIso(), i + 1),
        amount: simulacion.installmentAmount,
        status: "PENDING",
      })),
      history: [
        { at: nowIso(), status: "GRANTED", actor: resolvedBy, note: `Refinancia el plan #${original.planId}` },
        { at: nowIso(), status: "CURRENT", actor: "sistema" },
      ],
    };
    store.paymentPlans.unshift(nuevo);

    // El original queda como antecedente, con todo su historial intacto.
    original.lifecycle = "REFINANCED";
    original.refinancedInto = nuevo.planId;
    original.history = [
      ...(original.history ?? []),
      {
        at: nowIso(),
        status: "REFINANCED",
        actor: resolvedBy,
        note: `Refinanciado por el plan #${nuevo.planId}`,
      },
    ];

    solicitud.newPlanId = nuevo.planId;
    return { ...solicitud, newPlan: nuevo, previousPlan: original };
  },

  async list({ status = "" } = {}) {
    if (!USE_MOCKS) {
      const params = new URLSearchParams({ status });
      return request(`/api/v1/payment-plans/refinancing?${params}`);
    }
    await delay();
    return store.refinancings.filter((r) => !status || r.status === status);
  },
};

/** Suma meses a una fecha, para armar el cronograma del plan nuevo. */
function addMonths(iso, months) {
  const d = new Date(iso);
  d.setMonth(d.getMonth() + months);
  return d.toISOString().slice(0, 10);
}

// ------------------------------------------------------------------ Exenciones

export const exemptionService = {
  async list({ status = "", internalStatus = "" } = {}) {
    if (!USE_MOCKS) {
      const params = new URLSearchParams({ status, internalStatus });
      return request(`/api/v1/exemptions?${params}`);
    }
    await delay();
    return store.exemptions
      .filter((e) => !status || e.status === status)
      .map((e) => ({ ...e, internalStatus: exemptionInternalStatusOf(e) }))
      .filter((e) => !internalStatus || e.internalStatus === internalStatus);
  },

  /**
   * Estado interno del trámite. M8 sólo conoce APPROVED y REJECTED: los pasos de
   * documentación y revisión son de M5 y no se publican.
   */
  async advanceWorkflow({ requestId, internalStatus, note, actor }) {
    if (!USE_MOCKS) {
      return request(`/api/v1/exemptions/${requestId}/workflow`, {
        method: "PUT",
        body: { internalStatus, note },
      });
    }
    await delay();
    const exemption = store.exemptions.find((e) => e.requestId === Number(requestId));
    if (!exemption) throw new ApiError("Solicitud inexistente.", 404);
    if (exemption.status !== "REQUESTED") {
      throw new ApiError("La solicitud ya fue resuelta.", 409);
    }
    if (!EXEMPTION_WORKFLOW.includes(internalStatus)) {
      throw new ApiError("Estado de trámite desconocido.", 400);
    }
    if (internalStatus === "DOCUMENTATION_REQUIRED" && !note?.trim()) {
      throw new ApiError("Indicá qué documentación falta: el ciudadano tiene que saberlo.", 400);
    }
    if (internalStatus === "PENDING_RESOLUTION" && exemption.attachments.length === 0) {
      throw new ApiError(
        "No se puede enviar a resolución sin la documentación respaldatoria.",
        409,
      );
    }

    exemption.internalStatus = internalStatus;
    exemption.workflow = [
      ...(exemption.workflow ?? []),
      { at: nowIso(), internalStatus, actor, note: note ?? null },
    ];
    return exemption;
  },

  /** Registra la documentación que el ciudadano presentó por mesa de entradas. */
  async attachDocumentation({ requestId, attachments, actor }) {
    if (!USE_MOCKS) {
      return request(`/api/v1/exemptions/${requestId}/attachments`, {
        method: "POST",
        body: { attachments },
      });
    }
    await delay();
    const exemption = store.exemptions.find((e) => e.requestId === Number(requestId));
    if (!exemption) throw new ApiError("Solicitud inexistente.", 404);
    if (!attachments?.length) throw new ApiError("Adjuntá al menos un archivo.", 400);

    exemption.attachments = [
      ...exemption.attachments,
      ...attachments.map(
        (file) => `s3://rentas-documents/exenciones/${exemption.requestId}/${file.name ?? file}`,
      ),
    ];
    exemption.internalStatus = "DOCUMENTATION_RECEIVED";
    exemption.workflow = [
      ...(exemption.workflow ?? []),
      {
        at: nowIso(),
        internalStatus: "DOCUMENTATION_RECEIVED",
        actor,
        note: `${attachments.length} archivo(s) presentados`,
      },
    ];
    return exemption;
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
    if (!USE_MOCKS) {
      // Los archivos van aparte, como multipart: acá viaja sólo la referencia.
      return request("/api/v1/exemptions", {
        method: "POST",
        body: {
          citizenId,
          conceptCode,
          reason,
          requestedPercentage,
          requestedFrom,
          requestedUntil,
          attachments,
        },
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
      // El binario va a S3; la base guarda la referencia, nunca el archivo.
      attachments: attachments.map(
        (file) => `s3://rentas-documents/exenciones/${nextId()}/${file.name ?? file}`,
      ),
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
    if (exemptionInternalStatusOf(exemption) === "DOCUMENTATION_REQUIRED") {
      throw new ApiError(
        "Falta la documentación pedida: no se puede resolver hasta que la presente.",
        409,
      );
    }

    exemption.status = status;
    exemption.internalStatus = status;
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

// -------------------------------------------------------------------------- Caja

/**
 * En la demo el reloj corre pero la jornada es siempre la del dataset: así un cobro
 * recién registrado aparece en el resumen del día junto a los movimientos de ejemplo.
 */
const businessDate = () => (USE_MOCKS ? db.BUSINESS_DATE : businessDayOf(new Date()));

function counterTimestamp() {
  if (!USE_MOCKS) return nowIso();
  // Mediodía de la jornada, no la hora del reloj: así el cobro cae en el mismo día
  // calendario corra donde corra. Con la hora local, en UTC un cobro de la tarde
  // quedaba fechado al día siguiente y desaparecía del resumen.
  const base = new Date(`${db.BUSINESS_DATE}T12:00:00-03:00`).getTime();

  // Y va después del último movimiento del día, para encabezar "Últimos pagos".
  const ultimoDelDia = store.payments
    .filter((p) => businessDayOf(p.paidAt) === db.BUSINESS_DATE)
    .reduce((max, p) => Math.max(max, new Date(p.paidAt).getTime()), 0);

  return new Date(Math.max(base, ultimoDelDia + 60000)).toISOString();
}

const outstandingOf = (taxpayerId) =>
  store.debts
    .filter((d) => d.taxpayerId === taxpayerId)
    .reduce((acc, d) => acc + d.outstandingAmount, 0);

const overdueOf = (taxpayerId) =>
  store.debts
    .filter((d) => d.taxpayerId === taxpayerId && d.status === "OVERDUE")
    .reduce((acc, d) => acc + d.outstandingAmount, 0);

const taxpayerOf = (taxpayerId) => store.taxpayers.find((t) => t.id === taxpayerId) ?? null;

/** Un resultado de búsqueda por contribuyente: nombre, documento y deuda consolidada. */
function taxpayerResult(taxpayer) {
  const pending = store.debts.filter(
    (d) => d.taxpayerId === taxpayer.id && d.outstandingAmount > 0,
  );
  return {
    kind: "TAXPAYER",
    id: taxpayer.id,
    taxpayerId: taxpayer.id,
    title: taxpayer.name,
    subtitle: `${taxpayer.documentType} ${taxpayer.document} · CUIT ${taxpayer.cuit}`,
    amount: round2(outstandingOf(taxpayer.id)),
    status: taxpayer.status,
    detail: pending.length
      ? `${pending.length} ${pending.length === 1 ? "deuda pendiente" : "deudas pendientes"}`
      : "Sin deuda pendiente",
    dueDate: null,
  };
}

/** Un resultado por boleta: el papel que el contribuyente trae a la ventanilla. */
function billResult(bill) {
  const taxpayer = taxpayerOf(bill.taxpayerId);
  return {
    kind: "BILL",
    id: bill.id,
    taxpayerId: bill.taxpayerId,
    debtId: bill.debtId,
    title: `Boleta #${bill.id}`,
    subtitle: taxpayer ? `${taxpayer.name} · ${bill.conceptCode}` : bill.conceptCode,
    amount: bill.amount,
    status: bill.status,
    detail: `Deuda #${bill.debtId}`,
    dueDate: bill.dueDate,
  };
}

/** Un resultado por deuda: la obligación puntual y a quién está vinculada. */
function debtResult(debt) {
  const taxpayer = taxpayerOf(debt.taxpayerId);
  return {
    kind: "DEBT",
    id: debt.id,
    taxpayerId: debt.taxpayerId,
    debtId: debt.id,
    title: `Deuda #${debt.id}`,
    subtitle: taxpayer ? `${taxpayer.name} · ${debt.conceptCode}` : debt.conceptCode,
    amount: debt.outstandingAmount,
    status: debt.status,
    detail: `Origen ${debt.originType}${debt.originId ? ` #${debt.originId}` : ""}`,
    dueDate: debt.dueDate,
  };
}

/** Comprobante de caja: lo que se imprime y lo que queda como evidencia del cobro. */
function buildReceipt(payment, billId) {
  const debt = store.debts.find((d) => d.id === payment.debtId) ?? null;
  const taxpayer = taxpayerOf(payment.taxpayerId);
  const cashier = db.USERS.find((u) => u.username === payment.registeredBy) ?? null;
  const bill = billId
    ? store.bills.find((b) => b.id === Number(billId))
    : store.bills.find((b) => b.debtId === payment.debtId);

  return {
    receiptNumber: payment.receiptNumber,
    paymentId: payment.id,
    issuedAt: payment.paidAt,
    taxpayer: taxpayer && {
      id: taxpayer.id,
      name: taxpayer.name,
      documentType: taxpayer.documentType,
      document: taxpayer.document,
      cuit: taxpayer.cuit,
    },
    conceptCode: debt?.conceptCode ?? null,
    debtId: payment.debtId,
    billId: bill?.id ?? null,
    originType: payment.originType,
    originId: payment.originId,
    amountPaid: payment.amountPaid,
    remainingBalance: payment.remainingBalance,
    settled: debt ? debt.status === "SETTLED" : false,
    wasOverdue: payment.wasOverdue,
    method: payment.method,
    channel: payment.channel,
    status: payment.status,
    cashier: cashier && {
      username: cashier.username,
      fullName: cashier.fullName,
      counter: cashier.counter ?? null,
    },
  };
}

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
    if (!USE_MOCKS) {
      const params = new URLSearchParams({ query });
      return request(`/api/v1/cashier/search?${params}`);
    }
    await delay();
    const term = String(query).trim();
    if (!term) return [];

    const bills = store.bills
      .filter((b) => matches(b.id, term) || matches(b.barcode, term))
      .map(billResult);

    const debts = store.debts.filter((d) => matches(d.id, term)).map(debtResult);

    const taxpayers = store.taxpayers
      .filter(
        (t) =>
          matches(t.name, term) ||
          matches(t.document, term) ||
          matches(t.cuit, term) ||
          matches(t.id, term),
      )
      .map(taxpayerResult);

    // El papel manda: si el término identifica una boleta o una deuda, va primero.
    return [...bills, ...debts, ...taxpayers];
  },

  /**
   * Contexto de cobro de un resultado de búsqueda: qué se cobra y a quién.
   * Por contribuyente devuelve todas sus deudas con saldo; por boleta o deuda,
   * sólo la obligación elegida.
   */
  async chargeContext({ kind, id }) {
    if (!USE_MOCKS) return request(`/api/v1/cashier/charge-context/${kind}/${id}`);
    await delay();

    let bill = null;
    let debts = [];
    let taxpayerId = null;

    if (kind === "BILL") {
      bill = store.bills.find((b) => b.id === Number(id)) ?? null;
      if (!bill) throw new ApiError("Boleta inexistente.", 404);
      taxpayerId = bill.taxpayerId;
      debts = store.debts.filter((d) => d.id === bill.debtId);
    } else if (kind === "DEBT") {
      const debt = store.debts.find((d) => d.id === Number(id));
      if (!debt) throw new ApiError("Deuda inexistente.", 404);
      taxpayerId = debt.taxpayerId;
      debts = [debt];
      bill = store.bills.find((b) => b.debtId === debt.id) ?? null;
    } else {
      taxpayerId = Number(id);
      debts = store.debts.filter((d) => d.taxpayerId === taxpayerId && d.outstandingAmount > 0);
    }

    const taxpayer = taxpayerOf(taxpayerId);
    if (!taxpayer) throw new ApiError("El contribuyente no existe en el padrón local.", 404);

    const chargeable = debts.filter((d) => d.outstandingAmount > 0);

    return {
      kind,
      taxpayer,
      bill,
      debts: chargeable,
      selectedDebtId: kind === "TAXPAYER" ? null : (chargeable[0]?.id ?? null),
      totals: {
        outstanding: round2(outstandingOf(taxpayer.id)),
        overdue: round2(overdueOf(taxpayer.id)),
        pendingCount: store.debts.filter(
          (d) => d.taxpayerId === taxpayer.id && d.outstandingAmount > 0,
        ).length,
      },
    };
  },

  /** RegisterCounterPaymentRequest → CounterPaymentReceiptResponse */
  async registerCounterPayment({ debtId, billId, amountPaid, method, registeredBy }) {
    if (!USE_MOCKS) {
      return request("/api/v1/cashier/payments", {
        method: "POST",
        body: { debtId, billId, amountPaid, method, registeredBy },
      });
    }
    if (!debtId) throw new ApiError("Seleccioná la deuda o boleta que se está cobrando.", 400);
    if (!method) throw new ApiError("Indicá el medio de pago.", 400);

    const debt = store.debts.find((d) => d.id === Number(debtId));
    if (!debt) throw new ApiError("Deuda inexistente.", 404);
    if (debt.outstandingAmount <= 0) throw new ApiError("La deuda ya está cancelada.", 409);

    // El cobro reutiliza el registro de pagos: imputa, descuenta y publica los eventos.
    const payment = await paymentService.register({
      taxpayerId: debt.taxpayerId,
      debtId: debt.id,
      amountPaid,
      channel: "VENTANILLA",
      method,
      registeredBy,
      paidAt: counterTimestamp(),
    });

    return buildReceipt(payment, billId);
  },

  /** Reimpresión: el comprobante de un pago ya registrado. */
  async receipt(paymentId) {
    if (!USE_MOCKS) return request(`/api/v1/cashier/receipts/${paymentId}`);
    await delay(200);
    const payment = store.payments.find((p) => p.id === Number(paymentId));
    if (!payment) throw new ApiError("Pago inexistente.", 404);
    return buildReceipt(payment, null);
  },

  /** Ficha de ventanilla: deudas, pagos y boletas del contribuyente en una consulta. */
  async taxpayerFile(taxpayerId) {
    if (!USE_MOCKS) return request(`/api/v1/cashier/taxpayers/${taxpayerId}/file`);
    await delay();
    const id = Number(taxpayerId);
    const taxpayer = taxpayerOf(id);
    if (!taxpayer) throw new ApiError("El contribuyente no existe en el padrón local.", 404);

    const debts = store.debts.filter((d) => d.taxpayerId === id);
    const payments = store.payments
      .filter((p) => p.taxpayerId === id)
      .sort((a, b) => new Date(b.paidAt) - new Date(a.paidAt));

    return {
      taxpayer,
      debts,
      payments,
      bills: store.bills.filter((b) => b.taxpayerId === id),
      totals: {
        outstanding: round2(outstandingOf(id)),
        overdue: round2(overdueOf(id)),
        paid: round2(
          payments
            .filter((p) => p.status === "REGISTERED")
            .reduce((acc, p) => acc + p.amountPaid, 0),
        ),
      },
    };
  },

  /** Agentes que pueden figurar como responsables de un cobro. */
  async agents() {
    if (!USE_MOCKS) return request("/api/v1/cashier/agents");
    await delay(200);
    return db.USERS.map((u) => ({ value: u.username, label: u.fullName }));
  },

  /** Resumen de la jornada del cajero: lo que muestra el panel de caja. */
  async dailySummary({ registeredBy = "", date } = {}) {
    if (!USE_MOCKS) {
      const params = new URLSearchParams({ registeredBy, date: date ?? "" });
      return request(`/api/v1/cashier/daily-summary?${params}`);
    }
    await delay();
    const day = date || businessDate();

    const ofTheDay = store.payments
      .filter(
        (p) =>
          businessDayOf(p.paidAt) === day && (!registeredBy || p.registeredBy === registeredBy),
      )
      .sort((a, b) => new Date(b.paidAt) - new Date(a.paidAt));

    const collected = ofTheDay.filter((p) => p.status === "REGISTERED");

    const byMethod = Object.values(
      collected.reduce((acc, p) => {
        const key = p.method ?? "SIN_MEDIO";
        acc[key] ??= { method: key, count: 0, total: 0 };
        acc[key].count += 1;
        acc[key].total = round2(acc[key].total + p.amountPaid);
        return acc;
      }, {}),
    );

    return {
      date: day,
      registeredCount: collected.length,
      totalCollected: round2(collected.reduce((acc, p) => acc + p.amountPaid, 0)),
      // Pagos que entraron por otro canal y todavía nadie imputó a una deuda.
      pendingCount: store.payments.filter((p) => p.status === "UNALLOCATED").length,
      reversedCount: ofTheDay.filter((p) => p.status === "REVERSED").length,
      byMethod,
      latest: ofTheDay.slice(0, 6).map((p) => ({
        id: p.id,
        receiptNumber: p.receiptNumber,
        taxpayerId: p.taxpayerId,
        taxpayerName: taxpayerOf(p.taxpayerId)?.name ?? `Contribuyente #${p.taxpayerId}`,
        amountPaid: p.amountPaid,
        paidAt: p.paidAt,
        status: p.status,
        method: p.method,
      })),
      activity: ofTheDay.map((p) => ({
        id: p.id,
        description:
          p.status === "REVERSED"
            ? `Reversión de ${p.receiptNumber} — ${taxpayerOf(p.taxpayerId)?.name ?? "contribuyente"}`
            : p.debtId
              ? `Cobro imputado a la deuda #${p.debtId} — ${taxpayerOf(p.taxpayerId)?.name ?? "contribuyente"}`
              : `Pago sin imputar — ${taxpayerOf(p.taxpayerId)?.name ?? "contribuyente"}`,
        status: p.status,
        at: p.paidAt,
        amount: p.amountPaid,
      })),
    };
  },
};

// ---------------------------------------------------------------------- Auditoría

/**
 * Día calendario de un valor. Las fechas sin hora (`YYYY-MM-DD`) ya vienen en ese
 * formato: convertirlas correría un día según la zona horaria.
 */
const dayOf = (value) =>
  typeof value === "string" && value.length === 10 ? value : businessDayOf(value);

const inRange = (value, from, to) => {
  if (!from && !to) return true;
  if (!value) return false;
  const day = dayOf(value);
  return (!from || day >= from) && (!to || day <= to);
};

const sum = (rows, pick) => round2(rows.reduce((acc, row) => acc + (pick(row) ?? 0), 0));

/**
 * Acciones que representan una intervención humana sobre algo ya emitido.
 * Es la definición de "ajuste manual" del panel: el resto son altas del circuito normal.
 */
const MANUAL_ADJUSTMENTS = [
  "PAYMENT_REVERSED",
  "EXEMPTION_APPROVED",
  "EXEMPTION_REJECTED",
  "PAYMENT_PLAN_GRANTED",
  "PAYMENT_PLAN_REJECTED",
];

/** Filtro de contribuyente por texto libre: acepta id, nombre o documento. */
const matchesTaxpayerTerm = (taxpayerId, term) => {
  if (!term) return true;
  const taxpayer = store.taxpayers.find((t) => t.id === taxpayerId);
  return (
    matches(taxpayerId, term) ||
    matches(taxpayer?.name, term) ||
    matches(taxpayer?.document, term) ||
    matches(taxpayer?.cuit, term)
  );
};

/** Importe en pesos para los textos que arma el servicio (avisos del portal). */
const formatMoney = (value) =>
  new Intl.NumberFormat("es-AR", { style: "currency", currency: "ARS" }).format(value ?? 0);

const conceptOf = (code) => store.conceptDefinitions.find((c) => c.code === code) ?? null;
const conceptName = (code) => conceptOf(code)?.name ?? code ?? "—";
const nameOfTaxpayer = (id) =>
  store.taxpayers.find((t) => t.id === id)?.name ?? `Contribuyente #${id}`;

/** Enriquece una fila con el nombre del contribuyente y del concepto. */
const decorate = (row) => ({
  ...row,
  taxpayerName: row.taxpayerId ? nameOfTaxpayer(row.taxpayerId) : null,
  conceptName: row.conceptCode ? conceptName(row.conceptCode) : null,
});

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
    if (!USE_MOCKS) return request("/api/v1/audit/dashboard");
    await delay();
    const today = businessDate();

    const paymentsToday = store.payments.filter((p) => dayOf(p.paidAt) === today);
    const auditToday = store.auditLog.filter((a) => dayOf(a.at) === today);

    return {
      date: today,
      dailyOperations: auditToday.length,
      paymentsRegistered: paymentsToday.filter((p) => p.status === "REGISTERED").length,
      paymentsReversed: store.reversals.length,
      manualAdjustments: store.auditLog.filter((a) => MANUAL_ADJUSTMENTS.includes(a.action))
        .length,
      defaultedPlans: store.paymentPlans.filter((p) => p.lifecycle === "DEFAULTED").length,
      exemptionsApproved: store.exemptions.filter((e) => e.status === "APPROVED").length,
      exemptionsRejected: store.exemptions.filter((e) => e.status === "REJECTED").length,
      integrationErrors: store.eventLog.filter(
        (e) => e.status === "DLQ" || e.status === "RETRYING",
      ).length,
      recentActivity: [...store.auditLog]
        .sort((a, b) => new Date(b.at) - new Date(a.at))
        .slice(0, 8)
        .map((entry) => ({
          id: entry.id,
          at: entry.at,
          username: entry.username,
          role: entry.role,
          action: entry.action,
          entity: entry.entity,
          result: entry.result,
        })),
    };
  },

  // ------------------------------------------------------------ Contribuyentes

  async taxpayers({ query = "" } = {}) {
    if (!USE_MOCKS) {
      const params = new URLSearchParams({ query });
      return request(`/api/v1/audit/taxpayers?${params}`);
    }
    await delay();
    return store.taxpayers
      .filter(
        (t) =>
          !query ||
          matches(t.name, query) ||
          matches(t.document, query) ||
          matches(t.cuit, query) ||
          matches(t.id, query),
      )
      .map((taxpayer) => {
        const debts = store.debts.filter((d) => d.taxpayerId === taxpayer.id);
        return {
          ...taxpayer,
          totalDebt: sum(debts, (d) => d.outstandingAmount),
          overdueDebt: sum(
            debts.filter((d) => d.status === "OVERDUE"),
            (d) => d.outstandingAmount,
          ),
        };
      });
  },

  /** Ficha 360°: lo que el auditor necesita para reconstruir la situación fiscal. */
  async taxpayerFile(taxpayerId) {
    if (!USE_MOCKS) return request(`/api/v1/audit/taxpayers/${taxpayerId}`);
    await delay();
    const id = Number(taxpayerId);
    const taxpayer = store.taxpayers.find((t) => t.id === id);
    if (!taxpayer) throw new ApiError("El contribuyente no existe en el padrón local.", 404);

    const debts = store.debts.filter((d) => d.taxpayerId === id).map(decorate);
    const settlements = store.settlements
      .filter((s) => s.taxpayerId === id)
      .sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt))
      .map(decorate);
    const credits = store.creditBalances.filter(
      (c) => c.taxpayerId === id && c.status === "ACTIVE",
    );

    return {
      taxpayer,
      debts,
      // El circuito empieza acá: la liquidación es la que genera la deuda.
      settlements,
      payments: store.payments
        .filter((p) => p.taxpayerId === id)
        .sort((a, b) => new Date(b.paidAt) - new Date(a.paidAt))
        .map(decorate),
      plans: store.paymentPlans.filter((p) => p.taxpayerId === id).map(decorate),
      exemptions: store.exemptions
        .filter((e) => e.citizenId === id)
        .map((e) => ({ ...e, conceptName: conceptName(e.conceptCode) })),
      totals: {
        totalDebt: sum(debts, (d) => d.outstandingAmount),
        overdueDebt: sum(
          debts.filter((d) => d.status === "OVERDUE"),
          (d) => d.outstandingAmount,
        ),
        creditBalance: sum(credits, (c) => c.amount),
      },
    };
  },

  // ------------------------------------------------------------------ Conceptos

  async concepts({ query = "", type = "", status = "" } = {}) {
    if (!USE_MOCKS) {
      const params = new URLSearchParams({ query, type, status });
      return request(`/api/v1/audit/concepts?${params}`);
    }
    await delay();
    return store.conceptDefinitions.filter(
      (c) =>
        (!query || matches(c.code, query) || matches(c.name, query)) &&
        (!type || c.type === type) &&
        (!status || c.status === status),
    );
  },

  async conceptDetail(code) {
    if (!USE_MOCKS) return request(`/api/v1/audit/concepts/${code}`);
    await delay();
    const concept = conceptOf(code);
    if (!concept) throw new ApiError("Concepto inexistente.", 404);
    return concept;
  },

  // -------------------------------------------------------------- Liquidaciones

  async settlements({ taxpayer = "", conceptCode = "", status = "", from = "", to = "" } = {}) {
    if (!USE_MOCKS) {
      const params = new URLSearchParams({ taxpayer, conceptCode, status, from, to });
      return request(`/api/v1/audit/settlements?${params}`);
    }
    await delay();
    return store.settlements
      .filter(
        (s) =>
          matchesTaxpayerTerm(s.taxpayerId, taxpayer) &&
          (!conceptCode || s.conceptCode === conceptCode) &&
          (!status || s.status === status) &&
          inRange(s.createdAt, from, to),
      )
      .map(decorate);
  },

  async settlementDetail(id) {
    if (!USE_MOCKS) return request(`/api/v1/audit/settlements/${id}`);
    await delay();
    const settlement = store.settlements.find((s) => s.id === Number(id));
    if (!settlement) throw new ApiError("Liquidación inexistente.", 404);
    return {
      ...decorate(settlement),
      debt: settlement.debtId
        ? (store.debts.find((d) => d.id === settlement.debtId) ?? null)
        : null,
    };
  },

  // --------------------------------------------------------------------- Deudas

  async debts({ taxpayer = "", conceptCode = "", status = "", from = "", to = "" } = {}) {
    if (!USE_MOCKS) {
      const params = new URLSearchParams({ taxpayer, conceptCode, status, from, to });
      return request(`/api/v1/audit/debts?${params}`);
    }
    await delay();
    return store.debts
      .filter(
        (d) =>
          matchesTaxpayerTerm(d.taxpayerId, taxpayer) &&
          (!conceptCode || d.conceptCode === conceptCode) &&
          (!status || d.status === status) &&
          inRange(d.dueDate, from, to),
      )
      .map(decorate);
  },

  async debtDetail(id) {
    if (!USE_MOCKS) return request(`/api/v1/audit/debts/${id}`);
    await delay();
    const debt = store.debts.find((d) => d.id === Number(id));
    if (!debt) throw new ApiError("Deuda inexistente.", 404);

    return {
      ...decorate(debt),
      paidAmount: round2(debt.originalAmount - debt.outstandingAmount),
      settlement: store.settlements.find((s) => s.id === debt.settlementId) ?? null,
      payments: store.payments
        .filter((p) => p.allocations?.some((a) => a.debtId === debt.id))
        .sort((a, b) => new Date(b.paidAt) - new Date(a.paidAt)),
      plan: store.paymentPlans.find((p) => p.debtIds.includes(debt.id)) ?? null,
      bills: store.bills.filter((b) => b.debtId === debt.id),
    };
  },

  // ---------------------------------------------------------------------- Pagos

  /** `tab` refleja las solapas del listado: registrados, sin imputar, saldos a favor. */
  async payments({ tab = "REGISTERED", taxpayer = "", method = "", from = "", to = "" } = {}) {
    if (!USE_MOCKS) {
      const params = new URLSearchParams({ tab, taxpayer, method, from, to });
      return request(`/api/v1/audit/payments?${params}`);
    }
    await delay();
    const byTab = (payment) => {
      if (tab === "UNALLOCATED") return payment.status === "UNALLOCATED";
      if (tab === "CREDIT") return Boolean(payment.creditBalanceId);
      if (tab === "REVERSED") return payment.status === "REVERSED";
      return payment.status === "REGISTERED";
    };
    return store.payments
      .filter(
        (p) =>
          byTab(p) &&
          matchesTaxpayerTerm(p.taxpayerId, taxpayer) &&
          (!method || p.method === method) &&
          inRange(p.paidAt, from, to),
      )
      .sort((a, b) => new Date(b.paidAt) - new Date(a.paidAt))
      .map(decorate);
  },

  async paymentDetail(id) {
    if (!USE_MOCKS) return request(`/api/v1/audit/payments/${id}`);
    await delay();
    const payment = store.payments.find((p) => p.id === Number(id));
    if (!payment) throw new ApiError("Pago inexistente.", 404);

    return {
      ...decorate(payment),
      allocations: (payment.allocations ?? []).map((allocation) => {
        const debt = store.debts.find((d) => d.id === allocation.debtId);
        return {
          ...allocation,
          conceptCode: debt?.conceptCode ?? null,
          conceptName: debt ? conceptName(debt.conceptCode) : null,
        };
      }),
      creditBalance: payment.creditBalanceId
        ? (store.creditBalances.find((c) => c.id === payment.creditBalanceId) ?? null)
        : null,
      reversal: payment.reversalId
        ? (store.reversals.find((r) => r.id === payment.reversalId) ?? null)
        : null,
    };
  },

  // ---------------------------------------------------------------- Reversiones

  async reversals({ paymentId = "", username = "", from = "", to = "" } = {}) {
    if (!USE_MOCKS) {
      const params = new URLSearchParams({ paymentId, username, from, to });
      return request(`/api/v1/audit/reversals?${params}`);
    }
    await delay();
    return store.reversals
      .filter(
        (r) =>
          (!paymentId || matches(r.paymentId, paymentId)) &&
          (!username || r.requestedBy === username || r.approvedBy === username) &&
          inRange(r.reversedAt, from, to),
      )
      .map(decorate);
  },

  async reversalDetail(id) {
    if (!USE_MOCKS) return request(`/api/v1/audit/reversals/${id}`);
    await delay();
    const reversal = store.reversals.find((r) => r.id === Number(id));
    if (!reversal) throw new ApiError("Reversión inexistente.", 404);
    return {
      ...decorate(reversal),
      payment: store.payments.find((p) => p.id === reversal.paymentId) ?? null,
      debt: store.debts.find((d) => d.id === reversal.debtId) ?? null,
    };
  },

  // -------------------------------------------------------------- Planes de pago

  async plans({ taxpayer = "", status = "", lifecycle = "", from = "", to = "" } = {}) {
    if (!USE_MOCKS) {
      const params = new URLSearchParams({ taxpayer, status, lifecycle, from, to });
      return request(`/api/v1/audit/payment-plans?${params}`);
    }
    await delay();
    return store.paymentPlans
      .filter(
        (p) =>
          matchesTaxpayerTerm(p.taxpayerId, taxpayer) &&
          (!status || p.status === status) &&
          (!lifecycle || p.lifecycle === lifecycle) &&
          inRange(p.requestedAt, from, to),
      )
      .map(decorate);
  },

  async planDetail(requestId) {
    if (!USE_MOCKS) return request(`/api/v1/audit/payment-plans/${requestId}`);
    await delay();
    const plan = store.paymentPlans.find((p) => p.requestId === Number(requestId));
    if (!plan) throw new ApiError("Plan de pago inexistente.", 404);
    return {
      ...decorate(plan),
      debts: store.debts.filter((d) => plan.debtIds.includes(d.id)).map(decorate),
    };
  },

  // ----------------------------------------------------------------- Exenciones

  async exemptions({ tab = "", taxpayer = "", conceptCode = "", from = "", to = "" } = {}) {
    if (!USE_MOCKS) {
      const params = new URLSearchParams({ tab, taxpayer, conceptCode, from, to });
      return request(`/api/v1/audit/exemptions?${params}`);
    }
    await delay();
    return store.exemptions
      .filter(
        (e) =>
          (!tab || e.status === tab) &&
          matchesTaxpayerTerm(e.citizenId, taxpayer) &&
          (!conceptCode || e.conceptCode === conceptCode) &&
          inRange(e.requestedAt, from, to),
      )
      .map((e) => ({
        ...e,
        taxpayerName: nameOfTaxpayer(e.citizenId),
        conceptName: conceptName(e.conceptCode),
      }));
  },

  async exemptionDetail(requestId) {
    if (!USE_MOCKS) return request(`/api/v1/audit/exemptions/${requestId}`);
    await delay();
    const exemption = store.exemptions.find((e) => e.requestId === Number(requestId));
    if (!exemption) throw new ApiError("Solicitud de exención inexistente.", 404);
    return {
      ...exemption,
      taxpayerName: nameOfTaxpayer(exemption.citizenId),
      conceptName: conceptName(exemption.conceptCode),
    };
  },

  // -------------------------------------------------------------------- Tickets

  async tickets({ taxpayer = "", subject = "", status = "", priority = "", from = "", to = "" } = {}) {
    if (!USE_MOCKS) {
      const params = new URLSearchParams({ taxpayer, subject, status, priority, from, to });
      return request(`/api/v1/audit/tickets?${params}`);
    }
    await delay();
    return store.tickets
      .filter(
        (t) =>
          matchesTaxpayerTerm(t.citizenId, taxpayer) &&
          (!subject || t.subject === subject) &&
          (!status || t.status === status) &&
          (!priority || t.priority === priority) &&
          inRange(t.createdAt, from, to),
      )
      .map((t) => ({ ...t, taxpayerName: nameOfTaxpayer(t.citizenId) }));
  },

  async ticketDetail(ticketId) {
    if (!USE_MOCKS) return request(`/api/v1/audit/tickets/${ticketId}`);
    await delay();
    const ticket = store.tickets.find((t) => t.ticketId === Number(ticketId));
    if (!ticket) throw new ApiError("Ticket inexistente.", 404);
    return {
      ...ticket,
      taxpayerName: nameOfTaxpayer(ticket.citizenId),
      payment: ticket.reference?.paymentId
        ? (store.payments.find((p) => p.id === ticket.reference.paymentId) ?? null)
        : null,
      debt: ticket.reference?.debtId
        ? (store.debts.find((d) => d.id === ticket.reference.debtId) ?? null)
        : null,
    };
  },

  // --------------------------------------------------------------- Integraciones

  async integrations({ sourceModule = "", eventType = "", status = "", from = "", to = "" } = {}) {
    if (!USE_MOCKS) {
      const params = new URLSearchParams({ sourceModule, eventType, status, from, to });
      return request(`/api/v1/audit/integrations?${params}`);
    }
    await delay();
    return store.eventLog
      .filter(
        (e) =>
          (!sourceModule || e.sourceModule === sourceModule) &&
          (!eventType || e.eventType === eventType) &&
          (!status || e.status === status) &&
          inRange(e.occurredAt, from, to),
      )
      .sort((a, b) => new Date(b.occurredAt) - new Date(a.occurredAt));
  },

  async integrationDetail(eventId) {
    if (!USE_MOCKS) return request(`/api/v1/audit/integrations/${eventId}`);
    await delay();
    const event = store.eventLog.find((e) => e.eventId === eventId);
    if (!event) throw new ApiError("Evento inexistente.", 404);
    return event;
  },

  // ------------------------------------------------------- Registro de auditoría

  async auditTrail({ username = "", role = "", action = "", entityType = "", from = "", to = "" } = {}) {
    if (!USE_MOCKS) {
      const params = new URLSearchParams({ username, role, action, entityType, from, to });
      return request(`/api/v1/audit/trail?${params}`);
    }
    await delay();
    return store.auditLog
      .filter(
        (a) =>
          (!username || a.username === username) &&
          (!role || a.role === role) &&
          (!action || a.action === action) &&
          (!entityType || a.entity.type === entityType) &&
          inRange(a.at, from, to),
      )
      .sort((a, b) => new Date(b.at) - new Date(a.at));
  },

  async auditDetail(id) {
    if (!USE_MOCKS) return request(`/api/v1/audit/trail/${id}`);
    await delay();
    const entry = store.auditLog.find((a) => a.id === Number(id));
    if (!entry) throw new ApiError("Registro de auditoría inexistente.", 404);
    return entry;
  },

  // ---------------------------------------------------------------- Indicadores

  /** Indicadores del período. Cada tarjeta se puede abrir para ver qué la compone. */
  async indicators({ from = "", to = "", conceptCode = "" } = {}) {
    if (!USE_MOCKS) {
      const params = new URLSearchParams({ from, to, conceptCode });
      return request(`/api/v1/audit/indicators?${params}`);
    }
    await delay();

    const settlements = store.settlements.filter(
      (s) =>
        s.status !== "DRAFT" &&
        (!conceptCode || s.conceptCode === conceptCode) &&
        inRange(s.createdAt, from, to),
    );
    const payments = store.payments.filter(
      (p) => p.status === "REGISTERED" && inRange(p.paidAt, from, to),
    );
    const debts = store.debts.filter((d) => !conceptCode || d.conceptCode === conceptCode);

    const pending = debts.filter((d) => d.status === "PENDING");
    const overdue = debts.filter((d) => d.status === "OVERDUE");

    const pendingAmount = sum(pending, (d) => d.outstandingAmount);
    const overdueAmount = sum(overdue, (d) => d.outstandingAmount);
    const totalOutstanding = round2(pendingAmount + overdueAmount);

    // Recaudación por mes, ordenada cronológicamente para el gráfico.
    const byPeriod = Object.values(
      payments.reduce((acc, payment) => {
        const period = dayOf(payment.paidAt).slice(0, 7);
        acc[period] ??= { period, amount: 0, count: 0 };
        acc[period].amount = round2(acc[period].amount + payment.amountPaid);
        acc[period].count += 1;
        return acc;
      }, {}),
    ).sort((a, b) => a.period.localeCompare(b.period));

    const byConcept = Object.values(
      debts
        .filter((d) => d.outstandingAmount > 0)
        .reduce((acc, debt) => {
          acc[debt.conceptCode] ??= {
            conceptCode: debt.conceptCode,
            conceptName: conceptName(debt.conceptCode),
            amount: 0,
            count: 0,
          };
          acc[debt.conceptCode].amount = round2(
            acc[debt.conceptCode].amount + debt.outstandingAmount,
          );
          acc[debt.conceptCode].count += 1;
          return acc;
        }, {}),
    ).sort((a, b) => b.amount - a.amount);

    return {
      range: { from, to, conceptCode },
      totalSettled: sum(settlements, (s) => s.amount),
      totalCollected: sum(payments, (p) => p.amountPaid),
      pendingDebt: pendingAmount,
      overdueDebt: overdueAmount,
      // Morosidad: qué porción de la deuda viva ya está vencida.
      delinquencyRate: totalOutstanding > 0 ? round2((overdueAmount / totalOutstanding) * 100) : 0,
      defaultedPlans: store.paymentPlans.filter((p) => p.lifecycle === "DEFAULTED").length,
      counts: {
        totalSettled: settlements.length,
        totalCollected: payments.length,
        pendingDebt: pending.length,
        overdueDebt: overdue.length,
      },
      byPeriod,
      byConcept,
    };
  },

  /** Detalle de un indicador: las filas concretas que lo componen. */
  async indicatorBreakdown(key, { from = "", to = "", conceptCode = "", taxpayerId = "" } = {}) {
    if (!USE_MOCKS) {
      const params = new URLSearchParams({ from, to, conceptCode, taxpayerId });
      return request(`/api/v1/audit/indicators/${key}?${params}`);
    }
    await delay();

    const matchesTaxpayer = (row) => matchesTaxpayerTerm(row.taxpayerId, taxpayerId);

    if (key === "totalSettled" || key === "totalCollected") {
      const rows =
        key === "totalSettled"
          ? store.settlements.filter(
              (s) =>
                s.status !== "DRAFT" &&
                (!conceptCode || s.conceptCode === conceptCode) &&
                inRange(s.createdAt, from, to) &&
                matchesTaxpayer(s),
            )
          : store.payments.filter(
              (p) => p.status === "REGISTERED" && inRange(p.paidAt, from, to) && matchesTaxpayer(p),
            );
      return {
        key,
        total: sum(rows, (r) => r.amount ?? r.amountPaid),
        count: rows.length,
        rows: rows.map(decorate),
      };
    }

    if (key === "defaultedPlans") {
      const rows = store.paymentPlans.filter((p) => p.lifecycle === "DEFAULTED");
      return {
        key,
        total: sum(rows, (p) => p.outstandingAmount),
        count: rows.length,
        rows: rows.map(decorate),
      };
    }

    const status = key === "overdueDebt" ? "OVERDUE" : "PENDING";
    const rows = store.debts.filter(
      (d) =>
        d.status === status &&
        (!conceptCode || d.conceptCode === conceptCode) &&
        matchesTaxpayer(d),
    );
    return {
      key,
      total: sum(rows, (d) => d.outstandingAmount),
      count: rows.length,
      rows: rows.map(decorate),
    };
  },
};

// ------------------------------------------------------- Portal del contribuyente

/** Días que faltan (o pasaron, en negativo) hasta una fecha, contra la jornada del dataset. */
const daysUntil = (date) => {
  const from = new Date(`${businessDate()}T00:00:00-03:00`);
  const to = new Date(`${dayOf(date)}T00:00:00-03:00`);
  return Math.round((to - from) / 86400000);
};

/** Un vencimiento entra en la ventana de aviso cuando faltan 15 días o menos. */
const DUE_SOON_DAYS = 15;

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
    if (!USE_MOCKS) return request(`/api/v1/portal/${taxpayerId}/account-summary`);
    await delay();
    const id = Number(taxpayerId);
    const taxpayer = store.taxpayers.find((t) => t.id === id);
    if (!taxpayer) throw new ApiError("El contribuyente no existe en el padrón local.", 404);

    const debts = store.debts.filter((d) => d.taxpayerId === id);
    const pending = debts.filter((d) => d.outstandingAmount > 0);

    // El próximo vencimiento es el más cercano entre las obligaciones con saldo.
    const next = [...pending].sort((a, b) => dayOf(a.dueDate).localeCompare(dayOf(b.dueDate)))[0];

    const credits = store.creditBalances.filter(
      (c) => c.taxpayerId === id && c.status === "ACTIVE",
    );

    return {
      taxpayer,
      totalDebt: round2(outstandingOf(id)),
      overdueDebt: round2(overdueOf(id)),
      creditBalance: sum(credits, (c) => c.amount),
      nextDueDate: next
        ? {
            debtId: next.id,
            conceptCode: next.conceptCode,
            conceptName: conceptName(next.conceptCode),
            amount: next.outstandingAmount,
            dueDate: next.dueDate,
            daysLeft: daysUntil(next.dueDate),
            overdue: next.status === "OVERDUE",
          }
        : null,
      obligations: pending
        .sort((a, b) => dayOf(a.dueDate).localeCompare(dayOf(b.dueDate)))
        .map((debt) => ({
          ...debt,
          conceptName: conceptName(debt.conceptCode),
          daysLeft: daysUntil(debt.dueDate),
          // La boleta ya emitida es lo que el ciudadano lleva a pagar.
          billId: store.bills.find((b) => b.debtId === debt.id && b.status === "ISSUED")?.id ?? null,
        })),
      counts: {
        debts: pending.length,
        overdue: debts.filter((d) => d.status === "OVERDUE").length,
        bills: store.bills.filter((b) => b.taxpayerId === id && b.status === "ISSUED").length,
        openRequests:
          store.paymentPlans.filter((p) => p.taxpayerId === id && p.status === "REQUESTED").length +
          store.exemptions.filter((e) => e.citizenId === id && e.status === "REQUESTED").length,
      },
    };
  },

  /** Avisos de la portada: lo que exige atención, de lo más urgente a lo informativo. */
  async notices(taxpayerId) {
    if (!USE_MOCKS) return request(`/api/v1/portal/${taxpayerId}/notices`);
    await delay();
    const id = Number(taxpayerId);
    const avisos = [];

    const overdue = store.debts.filter((d) => d.taxpayerId === id && d.status === "OVERDUE");
    if (overdue.length > 0) {
      avisos.push({
        id: "deuda-vencida",
        severity: "error",
        title: overdue.length === 1 ? "Tenés una deuda vencida" : `Tenés ${overdue.length} deudas vencidas`,
        detail: `Suman ${formatMoney(sum(overdue, (d) => d.outstandingAmount))}. Podés pedir un plan de pago para financiarlas.`,
        path: "/portal/deudas",
      });
    }

    const soon = store.debts.filter(
      (d) =>
        d.taxpayerId === id &&
        d.outstandingAmount > 0 &&
        d.status !== "OVERDUE" &&
        daysUntil(d.dueDate) >= 0 &&
        daysUntil(d.dueDate) <= DUE_SOON_DAYS,
    );
    soon.forEach((debt) => {
      const dias = daysUntil(debt.dueDate);
      avisos.push({
        id: `vence-${debt.id}`,
        severity: "info",
        title: dias === 0 ? "Una obligación vence hoy" : `Una obligación vence en ${dias} días`,
        detail: `${conceptName(debt.conceptCode)} — ${formatMoney(debt.outstandingAmount)}.`,
        path: "/portal/deudas",
      });
    });

    store.bills
      .filter((b) => b.taxpayerId === id && b.status === "ISSUED")
      .forEach((bill) => {
        avisos.push({
          id: `boleta-${bill.id}`,
          severity: "info",
          title: `Boleta #${bill.id} disponible`,
          detail: `${conceptName(bill.conceptCode)} — ${formatMoney(bill.amount)}. Ya podés descargarla.`,
          path: "/portal/boletas",
        });
      });

    store.paymentPlans
      .filter((p) => p.taxpayerId === id && p.status !== "REQUESTED")
      .forEach((plan) => {
        avisos.push({
          id: `plan-${plan.requestId}`,
          severity: plan.status === "GRANTED" ? "success" : "info",
          title:
            plan.status === "GRANTED"
              ? `Tu plan de pago #${plan.planId} fue otorgado`
              : `Tu solicitud de plan #${plan.requestId} fue rechazada`,
          detail: plan.status === "GRANTED" ? `${plan.installments} cuotas.` : plan.reason,
          path: "/portal/planes",
        });
      });

    store.exemptions
      .filter((e) => e.citizenId === id && e.status !== "REQUESTED")
      .forEach((exemption) => {
        avisos.push({
          id: `exencion-${exemption.requestId}`,
          severity: exemption.status === "APPROVED" ? "success" : "info",
          title:
            exemption.status === "APPROVED"
              ? `Tu exención de ${conceptName(exemption.conceptCode)} fue aprobada`
              : `Tu solicitud de exención de ${conceptName(exemption.conceptCode)} fue rechazada`,
          detail:
            exemption.status === "APPROVED"
              ? `${exemption.percentage}% desde el ${dayOf(exemption.validFrom)}.`
              : exemption.reason_rejected,
          path: "/portal/exenciones",
        });
      });

    const credit = store.creditBalances.filter(
      (c) => c.taxpayerId === id && c.status === "ACTIVE",
    );
    if (credit.length > 0) {
      avisos.push({
        id: "saldo-a-favor",
        severity: "success",
        title: "Tenés saldo a favor",
        detail: `${formatMoney(sum(credit, (c) => c.amount))} disponibles para aplicar a una deuda. Consultá en la oficina de Rentas.`,
        path: null,
      });
    }

    const orden = { error: 0, info: 1, success: 2 };
    return avisos.sort((a, b) => orden[a.severity] - orden[b.severity]);
  },

  // --------------------------------------------------------------- Consultas

  async debts({ taxpayerId, status = "" }) {
    if (!USE_MOCKS) {
      const params = new URLSearchParams({ status });
      return request(`/api/v1/portal/${taxpayerId}/debts?${params}`);
    }
    await delay();
    const id = Number(taxpayerId);
    return store.debts
      .filter((d) => d.taxpayerId === id && (!status || d.status === status))
      .sort((a, b) => dayOf(a.dueDate).localeCompare(dayOf(b.dueDate)))
      .map((debt) => ({
        ...debt,
        conceptName: conceptName(debt.conceptCode),
        daysLeft: daysUntil(debt.dueDate),
        billId: store.bills.find((b) => b.debtId === debt.id && b.status === "ISSUED")?.id ?? null,
        // Una deuda ya incluida en una solicitud viva no se puede volver a financiar.
        planRequestId:
          store.paymentPlans.find(
            (p) => p.status !== "REJECTED" && p.debtIds.includes(debt.id),
          )?.requestId ?? null,
      }));
  },

  async bills({ taxpayerId, status = "" }) {
    if (!USE_MOCKS) {
      const params = new URLSearchParams({ status });
      return request(`/api/v1/portal/${taxpayerId}/bills?${params}`);
    }
    await delay();
    const id = Number(taxpayerId);
    return store.bills
      .filter((b) => b.taxpayerId === id && (!status || b.status === status))
      .sort((a, b) => new Date(b.issuedAt) - new Date(a.issuedAt))
      .map((bill) => ({
        ...bill,
        conceptName: conceptName(bill.conceptCode),
        daysLeft: daysUntil(bill.dueDate),
      }));
  },

  async payments({ taxpayerId }) {
    if (!USE_MOCKS) return request(`/api/v1/portal/${taxpayerId}/payments`);
    await delay();
    const id = Number(taxpayerId);
    return store.payments
      .filter((p) => p.taxpayerId === id)
      .sort((a, b) => new Date(b.paidAt) - new Date(a.paidAt))
      .map((payment) => {
        const debt = store.debts.find((d) => d.id === payment.debtId);
        return {
          ...payment,
          conceptName: debt ? conceptName(debt.conceptCode) : null,
        };
      });
  },

  async paymentPlans({ taxpayerId }) {
    if (!USE_MOCKS) return request(`/api/v1/portal/${taxpayerId}/payment-plans`);
    await delay();
    const id = Number(taxpayerId);
    return store.paymentPlans
      .filter((p) => p.taxpayerId === id)
      .sort((a, b) => new Date(b.requestedAt) - new Date(a.requestedAt))
      .map((plan) => ({
        ...plan,
        debts: store.debts
          .filter((d) => plan.debtIds.includes(d.id))
          .map((d) => ({ ...d, conceptName: conceptName(d.conceptCode) })),
      }));
  },

  async exemptions({ taxpayerId }) {
    if (!USE_MOCKS) return request(`/api/v1/portal/${taxpayerId}/exemptions`);
    await delay();
    const id = Number(taxpayerId);
    return store.exemptions
      .filter((e) => e.citizenId === id)
      .sort((a, b) => new Date(b.requestedAt) - new Date(a.requestedAt))
      .map((exemption) => ({ ...exemption, conceptName: conceptName(exemption.conceptCode) }));
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
