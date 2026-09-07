/**
 * Datos de prueba con la forma de los DTO del backend.
 *
 * No los conoce el código de producción: existen sólo para que las pruebas de flujo
 * tengan alguien del otro lado del `fetch`. Los enums son los del dominio
 * (`CASH`, `LIQUIDATION`, `PENDING`), no los rótulos de pantalla: si acá se
 * escribiera "EFECTIVO", la prueba pasaría contra un backend que no existe.
 */

export const CONCEPTOS = [
  { id: 1, code: "TASA_SERVICIOS", name: "Tasa de servicios generales", type: "FEE", originModule: "M5", active: true },
  { id: 2, code: "ABL", name: "Alumbrado, barrido y limpieza", type: "FEE", originModule: "M5", active: true },
  { id: 3, code: "PATENTE", name: "Patente automotor", type: "FEE", originModule: "M5", active: true },
  { id: 4, code: "TRAFFIC_INFRACTION", name: "Infracción de tránsito", type: "FINE", originModule: "M7", active: true },
];

export const CONFIGURACIONES = [
  // Tres versiones del mismo concepto: la comparación del auditor necesita historial.
  // La v1 es anterior al versionado de parámetros y por eso no los guarda.
  { id: 8, taxConceptId: 1, version: 1, status: "SUPERSEDED", calculationType: null, rate: null, fixedAmount: null, minimumAmount: null, maximumAmount: null, validFrom: "2025-01-01", validUntil: "2025-12-31", createdBy: "mrivas" },
  { id: 9, taxConceptId: 1, version: 2, status: "SUPERSEDED", calculationType: null, rate: null, fixedAmount: null, minimumAmount: null, maximumAmount: null, validFrom: "2026-01-01", validUntil: "2026-06-30", createdBy: "mrivas" },
  { id: 10, taxConceptId: 1, version: 3, status: "ACTIVE", calculationType: "PERCENTAGE", rate: 2, fixedAmount: 0, minimumAmount: 20000, maximumAmount: 150000, validFrom: "2026-07-01", validUntil: "2026-12-31", createdBy: "mrivas" },
  { id: 11, taxConceptId: 2, version: 1, status: "ACTIVE", calculationType: "FIXED", rate: 0, fixedAmount: 35000, minimumAmount: 35000, maximumAmount: 35000, validFrom: "2026-01-01", validUntil: "2026-12-31", createdBy: "mrivas" },
  { id: 12, taxConceptId: 3, version: 1, status: "ACTIVE", calculationType: "PERCENTAGE", rate: 3, fixedAmount: 0, minimumAmount: 10000, maximumAmount: 90000, validFrom: "2026-01-01", validUntil: "2026-12-31", createdBy: "mrivas" },
];

export const CONTRIBUYENTES = [
  { id: 123, taxpayerType: "CITIZEN", externalId: "M1-123", dni: "40111222", cuit: "20-40111222-3", displayName: "Juan Pérez", externalStatus: "ACTIVE", status: "ACTIVE" },
  { id: 78, taxpayerType: "ORGANIZATION", externalId: "M1-78", dni: null, cuit: "30-71234567-8", displayName: "Comercial ABC", externalStatus: "ACTIVE", status: "ACTIVE" },
];

export const LIQUIDACIONES = [
  { id: 7001, taxpayerId: 123, taxConceptId: 1, taxConfigurationId: 10, period: "2026-08", status: "ISSUED", baseAmount: 100000, finalAmount: 20000, dueDate: "2026-09-30", issuedAt: "2026-08-01T10:00:00-03:00" },
  { id: 7002, taxpayerId: 78, taxConceptId: 2, taxConfigurationId: 11, period: "2026-08", status: "ISSUED", baseAmount: 0, finalAmount: 35000, dueDate: "2026-09-15", issuedAt: "2026-08-01T10:00:00-03:00" },
];

export const DEUDAS = [
  { id: 3001, taxpayerId: 123, taxConceptId: 1, liquidationId: 7001, originType: "LIQUIDATION", status: "PENDING", originalAmount: 85000, outstandingBalance: 85000, dueDate: "2026-09-30", overdue: false, createdAt: "2026-08-01T10:00:00-03:00" },
  { id: 3002, taxpayerId: 123, taxConceptId: 3, originType: "LIQUIDATION", status: "PENDING", originalAmount: 40000, outstandingBalance: 40000, dueDate: "2026-08-10", overdue: true, createdAt: "2026-07-01T10:00:00-03:00" },
  { id: 3003, taxpayerId: 78, taxConceptId: 2, liquidationId: 7002, originType: "LIQUIDATION", status: "PENDING", originalAmount: 35000, outstandingBalance: 35000, dueDate: "2026-09-15", overdue: false, createdAt: "2026-08-01T10:00:00-03:00" },
];

export const BOLETAS = [
  { id: 12001, number: "12001", taxpayerId: 123, debtId: 3001, status: "ISSUED", amount: 85000, issueDate: "2026-08-05", dueDate: "2026-09-30" },
  { id: 12002, number: "12002", taxpayerId: 78, debtId: 3003, status: "ISSUED", amount: 35000, issueDate: "2026-08-05", dueDate: "2026-09-15" },
];

export const PAGOS = [
  { id: 9005, taxpayerId: 123, billId: 12001, receiptNumber: "REC-2026-9005", paymentMethod: "CARD", origin: "CASHIER", status: "CONFIRMED", amount: 25000, unallocatedAmount: 0, paidAt: "2026-08-25T09:40:00-03:00", registeredBy: "pcabrera" },
  { id: 9006, taxpayerId: 78, billId: 12002, receiptNumber: "REC-2026-9006", paymentMethod: "CASH", origin: "CASHIER", status: "CONFIRMED", amount: 35000, unallocatedAmount: 0, paidAt: "2026-08-25T11:05:00-03:00", registeredBy: "pcabrera" },
];

export const SALDOS = [
  { id: 7101, taxpayerId: 123, sourcePaymentId: 9005, status: "AVAILABLE", originalAmount: 30000, availableAmount: 30000, createdAt: "2026-08-25T09:45:00-03:00" },
];

export const SOLICITUDES_PLAN = [
  { id: 800, taxpayerId: 123, status: "PENDING", exceptional: false, totalDebtAtRequest: 125000, requestedInstallments: 6, estimatedDownPayment: 0, requestedAt: "2026-08-20T10:00:00-03:00" },
];

export const PLANES = [
  { id: 850, taxpayerId: 123, requestId: 800, status: "ACTIVE", totalAmount: 137500, installmentCount: 6, outstandingAmount: 137500, grantedAt: "2026-08-21T10:00:00-03:00" },
];

export const SOLICITUDES_EXENCION = [
  { id: 600, taxpayerId: 123, taxConceptId: 1, status: "PENDING", requestedPercentage: 100, reason: "Situación socioeconómica", requestedFrom: "2026-09-01", requestedUntil: "2027-08-31", requestedAt: "2026-08-18T10:00:00-03:00" },
];

export const TICKETS = [
  { id: 1001, taxpayerId: 123, externalTicketId: "M2-1001", category: "RENTAS", priority: "HIGH", status: "OPEN", description: "El pago no aparece imputado", createdAt: "2026-08-22T10:00:00-03:00" },
];

export const EVENTOS = [
  { id: 1, eventId: "11111111-1111-1111-1111-111111111111", eventType: "infractionConfirmed", sourceModule: "M7", targetModule: "M5", direction: "INBOUND", status: "PROCESSED", occurredAt: "2026-08-24T14:30:00-03:00", receivedAt: "2026-08-24T14:30:05-03:00", processedAt: "2026-08-24T14:30:06-03:00", payload: "{}" },
  { id: 2, eventId: "22222222-2222-2222-2222-222222222222", eventType: "ticketCreated", sourceModule: "M2", targetModule: "M5", direction: "INBOUND", status: "DLQ", occurredAt: "2026-08-24T15:00:00-03:00", receivedAt: "2026-08-24T15:00:05-03:00", errorMessage: "Contribuyente inexistente", retryCount: 3, payload: "{}" },
];

export const AUDITORIA = [
  { id: 1, userId: "mrivas", userRole: "ROLE_RENTAS", entityType: "Debt", entityId: "3001", action: "DEBT_CREATED", occurredAt: "2026-08-01T10:00:00-03:00" },
  { id: 2, userId: "pcabrera", userRole: "ROLE_CASHIER", entityType: "Payment", entityId: "9005", action: "PAYMENT_REGISTERED", occurredAt: "2026-08-25T09:40:00-03:00" },
];

export const INDICADORES = {
  collection: { paymentCount: 2, confirmedAmount: 60000 },
  debt: { openCount: 3, outstandingAmount: 160000 },
  delinquency: { overdueDebtCount: 1, overdueAmount: 40000, overduePercentage: 25 },
};
