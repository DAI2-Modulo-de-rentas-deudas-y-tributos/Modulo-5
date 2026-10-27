import { ApiError, request } from "../apiClient.js";
import { debtService } from "./debts.js";
import { apiTaxConfigModel } from "./taxConfigurations.js";
import { exemptionService } from "./exemptions.js";
import { externalObligationService } from "./integrations.js";

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

  /**
   * Detalle de la deuda con lo que la tocó.
   *
   * El backend expone la deuda, sus imputaciones y su rastro de auditoría por
   * separado; la pantalla los muestra juntos. Sin componerlos, `debt.payments` y
   * `debt.history` llegan indefinidos y la vista se rompe al dibujarlos.
   */
  async debtDetail(id) {
    const [debt, allocations, history] = await Promise.all([
      debtService.detail(id),
      request(`/api/v1/payment-allocations?debtId=${id}&size=100`).catch(() => []),
      request(`/api/v1/audit/entities/Debt/${id}`).catch(() => []),
    ]);
    return { ...debt, payments: allocations ?? [], history: history ?? [] };
  },

  // ---------------------------------------------------------------------- Pagos

  /** `tab` refleja las solapas del listado: registrados, sin imputar, saldos a favor. */
  async payments({ tab = "REGISTERED", taxpayer = "", method = "", from = "", to = "" } = {}) {
    const params = new URLSearchParams({ tab, taxpayer, method, from, to });
    return request(`/api/v1/audit/payments?${params}`);
  },

  async paymentDetail(id) {
    const [payment, allocations, history, reversals] = await Promise.all([
      request(`/api/v1/payments/${id}`),
      request(`/api/v1/payments/${id}/allocations`),
      request(`/api/v1/audit/entities/Payment/${id}`).catch(() => []),
      request(`/api/v1/payment-reversals?paymentId=${id}&size=100`).catch(() => []),
    ]);
    const [debts, credits] = await Promise.all([
      Promise.all(
        [...new Set((allocations ?? []).map((item) => item.debtId).filter(Boolean))]
          .map((debtId) => request(`/api/v1/debts/${debtId}`).catch(() => null)),
      ),
      request(`/api/v1/credit-balances?taxpayerId=${payment.taxpayerId}&size=100`).catch(() => []),
    ]);
    const debtById = new Map(debts.filter(Boolean).map((debt) => [debt.id, debt]));
    const creditBalance = credits.find((credit) => credit.sourcePaymentId === Number(id)) ?? null;
    const reversal = reversals[0] ?? null;
    const timeline = [
      ...(history ?? []),
      ...(allocations ?? []).flatMap((item) => [
        ...(item.allocatedAt ? [{ at: item.allocatedAt, action: `Imputación #${item.id} registrada`, actor: item.allocatedBy, note: item.debtId ? `Deuda #${item.debtId}` : `Cuota #${item.installmentId}` }] : []),
        ...(item.reversedAt ? [{ at: item.reversedAt, action: `Imputación #${item.id} revertida`, actor: reversal?.executedBy }] : []),
      ]),
      ...(reversal?.requestedAt ? [{ at: reversal.requestedAt, action: "Reversión solicitada", actor: reversal.requestedBy, note: reversal.reason }] : []),
      ...(reversal?.resolvedAt ? [{ at: reversal.resolvedAt, action: `Reversión ${reversal.status === "REJECTED" ? "rechazada" : "autorizada"}`, actor: reversal.resolvedBy }] : []),
      ...(reversal?.executedAt ? [{ at: reversal.executedAt, action: "Reversión ejecutada", actor: reversal.executedBy }] : []),
    ].sort((a, b) => String(a.at ?? "").localeCompare(String(b.at ?? "")));
    return {
      ...payment,
      allocations: (allocations ?? []).map((item) => ({
        ...item,
        debt: item.debtId ? debtById.get(item.debtId) ?? null : null,
        conceptName: item.debtId ? debtById.get(item.debtId)?.conceptName : "Cuota de plan",
      })),
      history: timeline,
      reversal,
      creditBalance,
    };
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
    return exemptionService.detail(requestId);
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

  async externalObligations({ externalReferenceId = "", status = "", sourceModule = "" } = {}) {
    return externalObligationService.list({ externalReferenceId, status, sourceModule });
  },

  async externalObligationDetail(id) {
    const obligation = await externalObligationService.detail(id);
    const [history, debts] = await Promise.all([
      request(`/api/v1/audit/entities/ExternalObligation/${id}`).catch(() => []),
      request(`/api/v1/debts?externalObligationId=${id}&size=100`).catch(() => []),
    ]);
    return { ...obligation, history: history ?? [], debt: debts[0] ?? null };
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
