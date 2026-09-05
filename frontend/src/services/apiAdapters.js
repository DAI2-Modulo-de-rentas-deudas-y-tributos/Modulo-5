/** Adapter between legacy screen shapes and the real M5 REST contract. */
const SIZE = "100";
const CALC_TO_API = { PORCENTAJE: "PERCENTAGE", FIJO: "FIXED", IMPORTE_EXTERNO: "EXTERNAL" };
const CALC_FROM_API = { PERCENTAGE: "PORCENTAJE", FIXED: "FIJO", EXTERNAL: "IMPORTE_EXTERNO" };
const METHOD_TO_API = { EFECTIVO: "CASH", TARJETA: "CARD", TARJETA_DEBITO: "CARD", TARJETA_CREDITO: "CARD", TRANSFERENCIA: "TRANSFER", QR: "DIGITAL_WALLET" };
const METHOD_FROM_API = { CASH: "EFECTIVO", CARD: "TARJETA", TRANSFER: "TRANSFERENCIA", DIGITAL_WALLET: "QR" };
const ORIGIN_FROM_API = { LIQUIDATION: "SETTLEMENT" };
const PAYMENT_ORIGIN_FROM_API = { CASHIER: "VENTANILLA", ELECTRONIC: "ELECTRONICO", EXTERNAL: "EXTERNO" };
const PLAN_STATUS_FROM_API = { ACTIVE: "CURRENT", COMPLETED: "FULFILLED", EXPIRED: "DEFAULTED", REFINANCED: "REFINANCED", CANCELLED: "CANCELLED" };
const INSTALLMENT_STATUS_FROM_API = { PENDING: "PENDING", PARTIALLY_PAID: "PARTIAL", OVERDUE: "OVERDUE", PAID: "SETTLED", CANCELLED: "CANCELLED" };

export function pageItems(value) {
  if (!value || Array.isArray(value) || !Array.isArray(value.content)) return value ?? [];
  const rows = [...value.content];
  const metadata = value.page ?? value;
  Object.defineProperty(rows, "page", { enumerable: false, value: {
    number: metadata.number,
    size: metadata.size,
    totalElements: metadata.totalElements,
    totalPages: metadata.totalPages,
    last: metadata.last ?? metadata.number >= metadata.totalPages - 1,
  } });
  return rows;
}

const urlOf = (path) => new URL(path, "http://adapter.local");
const daysUntil = (date) => {
  if (!date) return null;
  const today = new Date();
  const start = Date.UTC(today.getFullYear(), today.getMonth(), today.getDate());
  const [year, month, day] = String(date).slice(0, 10).split("-").map(Number);
  return Math.round((Date.UTC(year, month - 1, day) - start) / 86400000);
};
function qs(path, fields = {}) {
  const source = urlOf(path).searchParams;
  const target = new URLSearchParams({ size: SIZE });
  Object.entries(fields).forEach(([from, to]) => { const value = source.get(from); if (value) target.set(to, value); });
  return target.toString();
}
const method = (value) => METHOD_TO_API[value] ?? value ?? "CASH";
const adjustmentType = (value) => ({ BONIFICACION: "DISCOUNT", RECARGO: "SURCHARGE", INTERES: "INTEREST", CORRECCION: "CORRECTION" })[value] ?? value;

export function adaptApiRequest(originalPath, options = {}) {
  let path = originalPath;
  let body = options.body;
  const verb = options.method ?? "GET";
  let requestMethod = verb;

  if (path.startsWith("/api/v1/taxpayers?")) path = `/api/v1/taxpayers?${qs(path, { query: "q", type: "type" })}`;
  else if (path.startsWith("/api/v1/tax-config/pending")) path = `/api/v1/tax-configurations?status=PENDING_APPROVAL&size=${SIZE}`;
  else if (/^\/api\/v1\/tax-config\?/.test(path)) path = `/api/v1/tax-configurations?${qs(path, { status: "status" })}`;
  else if (/^\/api\/v1\/tax-config\/[^/?]+$/.test(path)) path = `/api/v1/tax-concepts?q=${encodeURIComponent(path.split("/").pop())}&size=${SIZE}`;
  else if (/\/tax-config\/[^/]+\/versions$/.test(path)) {
    path = "/api/v1/tax-configurations";
    body = taxConfigurationBody(body);
  } else if (path === "/api/v1/tax-configurations" && verb === "POST") body = taxConfigurationBody(body);
  else if (/\/tax-config\/versions\/\d+\/(submit|approve|reject)$/.test(path)) {
    const [, id, action] = path.match(/versions\/(\d+)\/(submit|approve|reject)/);
    path = `/api/v1/tax-configurations/${id}/${action}`;
    body = action === "submit" ? undefined : action === "reject" ? { reason: body?.reason ?? "Rechazada" } : { observation: body?.reason ?? null };
  } else if (path.startsWith("/api/v1/settlements")) {
    const single = () => ({ taxpayerId: body.taxpayerId, taxConceptId: body.taxConceptId ?? body.conceptId, period: body.period, taxableBase: body.taxableBase ?? body.baseAmount ?? body.amount ?? 0, dueDate: body.dueDate });
    if (path === "/api/v1/settlements" && verb === "POST") { path = "/api/v1/liquidations"; body = single(); }
    else if (path === "/api/v1/settlements/preview") { path = "/api/v1/liquidations/preview"; body = single(); }
    else if (path.includes("/batch")) { path = "/api/v1/liquidation-runs"; body = { taxConceptId: body.taxConceptId ?? body.conceptId, period: body.period, dueDate: body.dueDate, items: (body.items ?? body.taxpayers ?? []).map((x) => ({ taxpayerId: x.taxpayerId ?? x.id, taxableBase: x.taxableBase ?? x.baseAmount ?? 0 })) }; }
    else if (path.endsWith("/issue")) { path = `/api/v1/liquidations/${path.match(/\d+/)?.[0]}`; requestMethod = "GET"; }
    else path = path.includes("?") ? `/api/v1/liquidations?${qs(path, { taxpayerId: "taxpayerId", conceptId: "conceptId", period: "period", status: "status", from: "from", to: "to" })}` : path.replace("/settlements", "/liquidations");
  } else if (path.endsWith("/account-statement")) path = path.replace(/\/account-statement$/, "/summary");
  else if (path.startsWith("/api/v1/debts/report-overdue")) path = `/api/v1/debts?status=OVERDUE&size=${SIZE}`;
  else if (path.startsWith("/api/v1/debts?")) {
    path = `/api/v1/debts?${qs(path, { taxpayerId: "taxpayerId", status: "status", originType: "originType", from: "from", to: "to" })}`;
    path = path.replace("originType=SETTLEMENT", "originType=LIQUIDATION");
  }
  else if (path.startsWith("/api/v1/debt-adjustments")) {
    if (path.endsWith("/execute")) requestMethod = "GET";
    path = path.replace("/debt-adjustments", "/adjustments").replace(/\/execute$/, "");
    if (verb === "POST" && path === "/api/v1/adjustments") body = { debtId: body.debtId, type: adjustmentType(body.type), amount: body.amount, reason: body.reason };
    else if (path.endsWith("/reject")) body = { reason: body?.reason ?? "Rechazado" };
    else if (path.endsWith("/approve")) body = { observation: body?.reason ?? null };
  } else if (path.startsWith("/api/v1/bills/search")) path = `/api/v1/bills?${qs(path, { query: "q" })}`;
  else if (path === "/api/v1/bills" && verb === "POST") body = { taxpayerId: body.taxpayerId, debtIds: body.debtIds ?? [body.debtId].filter(Boolean), dueDate: body.dueDate };
  else if (path === "/api/v1/payments" && verb === "POST") body = paymentBody(body);
  else if (path.startsWith("/api/v1/payments?")) {
    const source = urlOf(path).searchParams;
    const status = source.get("status");
    if (status === "UNALLOCATED") path = `/api/v1/payments/unallocated?size=${SIZE}`;
    else {
      const target = new URLSearchParams({ size: SIZE });
      if (source.get("taxpayerId")) target.set("taxpayerId", source.get("taxpayerId"));
      if (status) target.set("status", status === "REGISTERED" ? "CONFIRMED" : status);
      if (source.get("date")) { target.set("from", source.get("date")); target.set("to", source.get("date")); }
      path = `/api/v1/payments?${target}`;
    }
  }
  else if (/\/payments\/\d+\/allocate$/.test(path)) { path = path.replace(/\/allocate$/, "/allocations"); body = { debtId: body.debtId ?? null, installmentId: body.installmentId ?? null, amount: body.amount ?? body.amountApplied }; }
  else if (/\/payments\/\d+\/(reverse|reversal)$/.test(path)) { path = path.replace(/\/(reverse|reversal)$/, "/reversal-requests"); body = { reason: body?.reason }; }
  else if (/\/credit-balances\/\d+\/applicable-debts/.test(path)) { const id = urlOf(path).searchParams.get("taxpayerId"); path = id ? `/api/v1/taxpayers/${id}/debts?size=${SIZE}` : `/api/v1/debts?size=${SIZE}`; }
  else if (/\/credit-balances\/\d+\/applications$/.test(path)) { path = path.replace(/\/applications$/, "/apply"); body = { debtId: body?.debtId, amount: body?.amount ?? body?.amountApplied }; }
  else if (path === "/api/v1/payment-plans/simulate") { path = "/api/v1/payment-plans/simulations"; body = { taxpayerId: body.taxpayerId, debtIds: body.debtIds, installments: body.installments }; }
  else if (path === "/api/v1/payment-plans" && verb === "POST") { path = "/api/v1/payment-plan-requests"; body = { taxpayerId: body.taxpayerId, debtIds: body.debtIds, installments: body.installments }; }
  else if (path.startsWith("/api/v1/payment-plans?")) path = `/api/v1/payment-plan-requests?${qs(path, { taxpayerId: "taxpayerId", status: "status", from: "from", to: "to" })}`;
  else if (/\/payment-plans\/\d+\/escalate$/.test(path)) { path = path.replace(/\/payment-plans\/(\d+)\/escalate$/, "/payment-plan-requests/$1/submit-exception"); body = { reason: body.reason ?? body.note }; }
  else if (/\/payment-plans\/\d+\/(approve|reject)$/.test(path)) { const [, id, action] = path.match(/payment-plans\/(\d+)\/(approve|reject)/); path = `/api/v1/payment-plan-requests/${id}/${action === "approve" ? "grant" : "reject"}`; body = action === "reject" ? { reason: body?.reason ?? "Rechazado" } : { downPaymentAmount: body?.downPayment ?? 0 }; }
  else if (/\/payment-plans\/\d+\/refinancing\/simulate$/.test(path)) { path = path.replace(/\/refinancing\/simulate$/, "/refinancing/simulation"); body = { installments: body.installments }; }
  else if (/\/payment-plans\/\d+\/refinancing$/.test(path)) { path += "-requests"; body = { installments: body.installments }; }
  else if (path.startsWith("/api/v1/refinancings")) { path = path.replace("/refinancings", "/refinancing-requests").replace(/\/escalate$/, "/submit-exception").replace(/\/approve$/, "/grant"); if (path.endsWith("submit-exception")) body = { reason: body.reason }; if (path.endsWith("reject")) body = { reason: body?.reason ?? "Rechazado" }; }
  else if (path.startsWith("/api/v1/exemptions/requests")) path = path.replace("/exemptions/requests", "/exemption-requests");
  else if (/\/exemptions\/\d+\/(start-review|request-documentation|documentation|submit-resolution|approve|reject)$/.test(path)) path = path.replace("/exemptions/", "/exemption-requests/");
  else if (path === "/api/v1/exemptions" && verb === "POST") { path = "/api/v1/exemption-requests"; body = { taxpayerId: body.taxpayerId ?? body.citizenId, taxConceptId: body.taxConceptId ?? body.conceptId, reason: body.reason, percentage: body.percentage ?? body.requestedPercentage, validFrom: body.validFrom ?? body.requestedFrom, validUntil: body.validUntil ?? body.requestedUntil ?? null }; }
  else if (/\/tickets\/\d+\/status$/.test(path)) { const id = path.match(/tickets\/(\d+)/)[1]; const action = ({ ASSIGNED: "assign", IN_PROGRESS: "updates", INFORMATION_REQUIRED: "request-information", COMPLETED: "complete", REJECTED: "reject" })[body.status] ?? "updates"; path = `/api/v1/tickets/${id}/${action}`; body = action === "assign" ? undefined : action === "complete" ? { resolution: body.reason ?? "Completado" } : action === "reject" ? { reason: body.reason ?? "Rechazado" } : { message: body.reason ?? body.status }; }
  else if (path.startsWith("/api/v1/events")) { path = path.replace("/api/v1/events", "/api/v1/integrations/events").replace(/\/retry$/, "/reprocess"); if (path.includes("?")) path = `/api/v1/integrations/events?${qs(originalPath, { direction: "direction", status: "status", eventType: "eventType" })}`; }
  else if (path.startsWith("/api/v1/cashier/")) ({ path, body } = cashier(path, body));
  else if (path.startsWith("/api/v1/audit/")) path = audit(path);
  else if (path.startsWith("/api/v1/portal/")) path = portal(path);
  else if (path === "/api/v1/dashboard/metrics") path = "/api/v1/indicators/summary";

  return { path, options: { ...options, method: requestMethod, body } };
}

/** Número que viene del formulario (texto, y a veces vacío) o el valor por defecto. */
const numberOr = (value, fallback) =>
  value === "" || value === null || value === undefined || Number.isNaN(Number(value))
    ? fallback
    : Number(value);

/**
 * Cuerpo de POST /api/v1/tax-configurations.
 *
 * Única traducción del alta de versiones: el backend espera el enum CalculationType
 * en inglés y BigDecimal, así que ni los códigos internos en español ni los campos
 * vacíos del formulario pueden viajar tal cual. Lo usan las dos entradas —el path
 * legado y el definitivo— para que manden exactamente el mismo cuerpo.
 */
function taxConfigurationBody(body = {}) {
  return {
    taxConceptId: body.taxConceptId ?? body.conceptId,
    calculationType: CALC_TO_API[body.calculationType] ?? body.calculationType,
    rate: numberOr(body.rate, 0),
    fixedAmount: numberOr(body.fixedAmount ?? body.value, 0),
    minimumAmount: numberOr(body.minimumAmount, 0),
    maximumAmount: numberOr(body.maximumAmount, null),
    partialPaymentAllowed: body.partialPaymentAllowed ?? true,
    paymentPlanAllowed: body.paymentPlanAllowed ?? true,
    validFrom: body.validFrom,
    validUntil: body.validUntil || null,
  };
}

function paymentBody(body) { const amount = body.amount ?? body.amountPaid; return { taxpayerId: body.taxpayerId, billId: body.billId ?? null, paymentMethod: method(body.paymentMethod ?? body.method), amount, allocations: body.allocations ?? (body.debtId ? [{ debtId: body.debtId, installmentId: null, amount }] : []) }; }
function cashier(path, body) {
  if (path.startsWith("/api/v1/cashier/search")) return { path: `/api/v1/taxpayers?${qs(path, { query: "q" })}`, body };
  if (path.includes("/charge-context/")) { const [, kind, id] = path.match(/charge-context\/(\w+)\/(\d+)/); return { path: kind === "DEBT" ? `/api/v1/debts/${id}` : kind === "BILL" ? `/api/v1/bills/${id}` : `/api/v1/taxpayers/${id}`, body }; }
  if (path === "/api/v1/cashier/payments") return { path: "/api/v1/payments", body: paymentBody(body) };
  if (path.includes("/receipts/")) return { path: path.replace("/cashier/receipts/", "/payments/") + "/receipt", body };
  if (path.includes("/taxpayers/")) return { path: path.replace("/cashier", "").replace(/\/file$/, "/summary"), body };
  if (path.endsWith("/agents")) return { path: "/api/v1/health", body };
  return { path: `/api/v1/payments?${qs(path, { date: "from" })}`, body };
}
function audit(path) {
  const mappings = [["dashboard", "indicators/summary"], ["taxpayers", "taxpayers"], ["concepts", "tax-concepts"], ["settlements", "liquidations"], ["debts", "debts"], ["payments", "payments"], ["reversals", "payment-reversals"], ["payment-plans", "payment-plan-requests"], ["exemptions", "exemption-requests"], ["tickets", "tickets"], ["integrations", "integrations/events"], ["trail", "audit"], ["indicators", "indicators/summary"]];
  const item = mappings.find(([old]) => path === `/api/v1/audit/${old}` || path.startsWith(`/api/v1/audit/${old}/`) || path.startsWith(`/api/v1/audit/${old}?`));
  if (!item) return path;
  const base = `/api/v1/${item[1]}`;
  if (!path.includes("?")) return path.replace(`/api/v1/audit/${item[0]}`, base);
  const allowed = item[0] === "taxpayers" || item[0] === "concepts" ? { query: "q", type: "type" } : item[0] === "integrations" ? { sourceModule: "sourceModule", eventType: "eventType", status: "status", from: "from", to: "to" } : item[0] === "trail" ? { username: "userId", role: "userRole", action: "action", entityType: "entityType", from: "from", to: "to" } : { status: "status", method: "method", priority: "priority", from: "from", to: "to" };
  return `${base}?${qs(path, allowed)}`;
}
function portal(path) { const match = path.match(/^\/api\/v1\/portal\/(\d+)\/([^?]+)/); if (!match) return path; const [, id, resource] = match; if (resource === "account-summary") return `/api/v1/taxpayers/${id}/summary`; if (resource === "notices" || resource === "debts") return `/api/v1/taxpayers/${id}/debts?${qs(path, { status: "status" })}`; if (resource === "bills") return `/api/v1/taxpayers/${id}/bills?size=${SIZE}`; if (resource === "payments") return `/api/v1/taxpayers/${id}/payments?size=${SIZE}`; if (resource === "payment-plans") return `/api/v1/taxpayers/${id}/payment-plan-requests?size=${SIZE}`; if (resource === "exemptions") return `/api/v1/taxpayers/${id}/exemption-requests?size=${SIZE}`; return path; }

export function adaptApiResponse(original, actual, payload) {
  const data = pageItems(payload);
  if (original.includes("/notices") && Array.isArray(data)) return data.filter((d) => d.overdue).map((d) => ({ id: `deuda-${d.id}`, severity: "error", title: "Tenés una deuda vencida", detail: `Saldo pendiente: ${d.outstandingBalance}`, path: "/portal/deudas" }));
  if (Array.isArray(data)) {
    const rows = data.map((row) => adaptRow(actual, row));
    if (original.startsWith("/api/v1/cashier/daily-summary")) return cashierSummary(original, rows);
    if (data.page) Object.defineProperty(rows, "page", { enumerable: false, value: data.page });
    return rows;
  }
  if (original === "/api/v1/dashboard/metrics") return indicators(data);
  if (original === "/api/v1/audit/dashboard") return auditDashboard(data);
  if (original.startsWith("/api/v1/audit/indicators")) return auditIndicators(data);
  if (original.endsWith("/account-summary")) return { ...data, totalDebt: data.outstandingDebt, overdueDebt: 0, creditBalance: 0, nextDueDate: null, obligations: [], counts: { debts: data.openDebts, overdue: 0, bills: 0, openRequests: data.activePlans } };
  if (original === "/api/v1/cashier/agents") return [];
  return adaptRow(actual, data);
}
function adaptRow(path, row) {
  if (!row || typeof row !== "object") return row;
  const pathname = urlOf(path).pathname;
  if (path.includes("/tax-concepts")) return { ...row, type: ({ FEE: "TASA", FINE: "MULTA", CHARGE: "CARGO" })[row.type] ?? row.type, status: row.active ? "ACTIVE" : "INACTIVE", versions: row.versions ?? [] };
  if (path.includes("/tax-configurations")) return { ...row, conceptId: row.taxConceptId, calculationType: CALC_FROM_API[row.calculationType] ?? row.calculationType };
  if (path.includes("/liquidations")) return { ...row, conceptId: row.taxConceptId, conceptName: row.conceptName ?? `Concepto #${row.taxConceptId}`, taxpayerName: row.taxpayerName ?? `Contribuyente #${row.taxpayerId}`, amount: row.finalAmount, createdAt: row.issuedAt, origin: row.origin ?? { module: "M5" } };
  if (path.includes("/debts")) return { ...row, conceptId: row.taxConceptId, conceptCode: row.conceptCode ?? `#${row.taxConceptId}`, conceptName: row.conceptName ?? `Concepto #${row.taxConceptId}`, taxpayerName: row.taxpayerName ?? `Contribuyente #${row.taxpayerId}`, outstandingAmount: row.outstandingBalance, settlementId: row.liquidationId, originId: row.liquidationId ?? row.externalObligationId, originType: ORIGIN_FROM_API[row.originType] ?? row.originType, daysLeft: row.daysLeft ?? daysUntil(row.dueDate), status: row.status === "PAID" ? "SETTLED" : row.overdue && row.status !== "CANCELLED" ? "OVERDUE" : row.status };
  if (path.includes("/bills")) return { ...row, conceptName: row.conceptName ?? (row.debts?.[0]?.debtId ? `Deuda #${row.debts[0].debtId}` : "Boleta municipal"), amount: row.totalAmount, issuedAt: row.createdAt ?? row.issueDate, debtId: row.debts?.[0]?.debtId ?? null, daysLeft: row.daysLeft ?? daysUntil(row.dueDate), barcode: row.number, documentUrl: `/api/v1/bills/${row.id}/document` };
  if (path.includes("/allocations")) return row;
  if (path.includes("/payments") && !path.includes("payment-plans")) return { ...row, taxpayerName: row.taxpayerName ?? `Contribuyente #${row.taxpayerId}`, amountPaid: row.amount, method: METHOD_FROM_API[row.paymentMethod] ?? row.paymentMethod, remainingBalance: row.unallocatedAmount, status: row.status === "REVERSED" ? "REVERSED" : Number(row.unallocatedAmount) > 0 ? "UNALLOCATED" : "REGISTERED", channel: PAYMENT_ORIGIN_FROM_API[row.origin] ?? row.origin };
  if (path.includes("/credit-balances")) return { ...row, amount: row.availableAmount };
  if (path.includes("/payment-plan-requests")) return { ...row, requestId: row.id, debtIds: row.debtIds ?? [], debts: row.debts ?? [], installments: row.requestedInstallments, totalDebt: row.totalDebt ?? row.totalDebtAtRequest, downPayment: row.downPayment ?? row.estimatedDownPayment ?? 0, totalAmount: row.estimatedTotalAmount, planId: row.paymentPlanId };
  if (path.includes("/payment-plans") && path.includes("/installments")) return { ...row, status: INSTALLMENT_STATUS_FROM_API[row.status] ?? row.status };
  if (path.includes("/payment-plans")) return { ...row, planId: row.id, installments: row.installmentCount, totalAmount: row.totalPlanAmount, outstandingAmount: row.outstandingPlanAmount, lifecycle: PLAN_STATUS_FROM_API[row.status] ?? row.status };
  if (path.includes("/adjustments")) return { ...row, requestId: row.id, status: row.status === "APPROVED" ? "EXECUTED" : row.status };
  if (path.includes("/exemption")) {
    const pending = ["PENDING", "UNDER_REVIEW", "DOCUMENTATION_REQUIRED", "PENDING_RESOLUTION"].includes(row.status);
    const internalStatus = ({ PENDING: "PENDING_REVIEW", UNDER_REVIEW: "PENDING_REVIEW" })[row.status] ?? row.status;
    return { ...row, requestId: row.requestId ?? row.id, citizenId: row.taxpayerId, conceptId: row.taxConceptId, conceptCode: row.conceptCode ?? `#${row.taxConceptId}`, conceptName: row.conceptName ?? `Concepto #${row.taxConceptId}`, requestedPercentage: row.requestedPercentage ?? row.percentage, requestedFrom: row.requestedFrom ?? row.validFrom, requestedUntil: row.requestedUntil ?? row.validUntil, status: pending ? "REQUESTED" : row.status, internalStatus, attachments: row.attachments ?? [], hasSocialBenefit: row.hasSocialBenefit ?? false };
  }
  if (path.includes("/tickets")) return { ...row, ticketId: row.id, citizenId: row.taxpayerId, subject: row.category };
  if (path.includes("/integrations/events")) return { ...row, destinationModule: row.targetModule, attempts: row.retryCount };
  if (pathname === "/api/v1/audit") return { ...row, username: row.userId, role: row.userRole, at: row.occurredAt, entity: { type: row.entityType, id: row.entityId }, result: "SUCCESS" };
  if (/^\/api\/v1\/taxpayers(?:\/\d+)?$/.test(pathname)) return { ...row, type: row.taxpayerType, documentType: row.dni ? "DNI" : "CUIT", document: row.dni ?? row.cuit, name: row.displayName };
  return row;
}
function indicators(v = {}) { return { date: new Date().toISOString().slice(0, 10), contribuyentes: 0, liquidaciones: 0, deudas: v.debt?.openCount ?? 0, boletas: 0, pagos: v.collection?.paymentCount ?? 0, planes: 0, exenciones: 0, tickets: 0, eventos: 0, totalOverdueAmount: v.delinquency?.overdueAmount ?? 0, unallocatedPayments: v.collection?.unallocatedAmount ?? 0, recentActivity: [] }; }

function auditDashboard(v = {}) {
  return {
    date: new Date().toISOString().slice(0, 10),
    dailyOperations: 0,
    paymentsRegistered: v.collection?.paymentCount ?? 0,
    paymentsReversed: 0,
    manualAdjustments: 0,
    defaultedPlans: 0,
    exemptionsApproved: 0,
    exemptionsRejected: 0,
    integrationErrors: 0,
    recentActivity: [],
  };
}

function auditIndicators(v = {}) {
  const overdueAmount = v.delinquency?.overdueAmount ?? 0;
  const outstandingAmount = v.debt?.outstandingAmount ?? 0;
  return {
    range: {},
    totalSettled: 0,
    totalCollected: v.collection?.confirmedAmount ?? 0,
    pendingDebt: Math.max(0, outstandingAmount - overdueAmount),
    overdueDebt: overdueAmount,
    delinquencyRate: v.delinquency?.overduePercentage ?? 0,
    defaultedPlans: 0,
    counts: {
      totalSettled: 0,
      totalCollected: v.collection?.paymentCount ?? 0,
      pendingDebt: Math.max(0, (v.debt?.openCount ?? 0) - (v.delinquency?.overdueDebtCount ?? 0)),
      overdueDebt: v.delinquency?.overdueDebtCount ?? 0,
    },
    byPeriod: [],
    byConcept: [],
  };
}

function cashierSummary(original, rows) {
  const params = urlOf(original).searchParams;
  const registeredBy = params.get("registeredBy");
  const date = params.get("date") || new Date().toISOString().slice(0, 10);
  const filtered = rows.filter((row) => !registeredBy || row.registeredBy === registeredBy);
  const collected = filtered.filter((row) => row.status !== "REVERSED");
  const byMethod = Object.values(collected.reduce((result, row) => {
    const key = row.method ?? "SIN_MEDIO";
    result[key] ??= { method: key, count: 0, total: 0 };
    result[key].count += 1;
    result[key].total += Number(row.amountPaid ?? 0);
    return result;
  }, {}));
  return {
    date,
    registeredCount: collected.length,
    totalCollected: collected.reduce((total, row) => total + Number(row.amountPaid ?? 0), 0),
    pendingCount: filtered.filter((row) => row.status === "UNALLOCATED").length,
    reversedCount: filtered.filter((row) => row.status === "REVERSED").length,
    byMethod,
    latest: filtered.slice(0, 6),
    activity: filtered.map((row) => ({ id: row.id, description: row.status === "REVERSED" ? `Reversión de ${row.receiptNumber}` : `Cobro ${row.receiptNumber}`, status: row.status, at: row.paidAt, amount: row.amountPaid })),
  };
}

export const enumMappings = { CALC_TO_API, CALC_FROM_API, METHOD_TO_API, METHOD_FROM_API, ORIGIN_FROM_API, PAYMENT_ORIGIN_FROM_API, PLAN_STATUS_FROM_API, INSTALLMENT_STATUS_FROM_API };
