const currencyFormatter = new Intl.NumberFormat("es-AR", {
  style: "currency",
  currency: "ARS",
  minimumFractionDigits: 2,
});

const dateFormatter = new Intl.DateTimeFormat("es-AR", {
  day: "2-digit",
  month: "2-digit",
  year: "numeric",
});

const timeFormatter = new Intl.DateTimeFormat("es-AR", {
  hour: "2-digit",
  minute: "2-digit",
});

const dateTimeFormatter = new Intl.DateTimeFormat("es-AR", {
  day: "2-digit",
  month: "2-digit",
  year: "numeric",
  hour: "2-digit",
  minute: "2-digit",
});

export const formatCurrency = (value) =>
  value === null || value === undefined ? "—" : currencyFormatter.format(value);

export const formatDate = (value) => (value ? dateFormatter.format(new Date(value)) : "—");

export const formatDateTime = (value) =>
  value ? dateTimeFormatter.format(new Date(value)) : "—";

export const formatTime = (value) => (value ? timeFormatter.format(new Date(value)) : "—");

/** `YYYY-MM-DD` de una fecha, para comparar jornadas sin arrastrar la hora. */
export const toDateInput = (value) => {
  const date = value ? new Date(value) : new Date();
  const offset = date.getTimezoneOffset() * 60000;
  return new Date(date.getTime() - offset).toISOString().slice(0, 10);
};

const compactFormatter = new Intl.NumberFormat("es-AR", {
  notation: "compact",
  maximumFractionDigits: 1,
});

/** Importe abreviado para ejes y etiquetas de gráficos: $12,5 M. */
export const formatCompactCurrency = (value) =>
  value === null || value === undefined ? "—" : `$${compactFormatter.format(value)}`;

export const formatPercentage = (value) =>
  value === null || value === undefined ? "—" : `${Number(value).toFixed(0)}%`;

/** Etiquetas en español de los estados que viajan en los eventos. */
export const STATUS_LABELS = {
  DRAFT: "Borrador",
  ISSUED: "Emitida",
  SETTLED: "Cancelada",
  PENDING: "Pendiente",
  OVERDUE: "Vencida",
  EXPIRED: "Vencida",
  REGISTERED: "Registrado",
  UNALLOCATED: "Sin imputar",
  REVERSED: "Reversado",
  REQUESTED: "Pendiente de resolución",
  GRANTED: "Otorgado",
  // Estados internos de M5: no viajan a ningún módulo.
  PENDING_REVIEW: "En revisión",
  PENDING_SUPERVISOR: "Derivado a Supervisor",
  UNDER_REVIEW: "En evaluación del Supervisor",
  REFINANCED: "Refinanciado",
  APPROVED: "Aprobada",
  REJECTED: "Rechazada",
  OPEN: "Abierto",
  IN_PROGRESS: "En curso",
  WAITING_FOR_INFORMATION: "Esperando información",
  COMPLETED: "Resuelto",
  ACTIVE: "Activo",
  BLOCKED: "Bloqueado",
  DECEASED: "Fallecido",
  PROCESSED: "Procesado",
  PUBLISHED: "Publicado",
  RETRYING: "Reintentando",
  DLQ: "En DLQ",
  CITIZEN: "Ciudadano",
  ORGANIZATION: "Organización",
  HIGH: "Alta",
  MEDIUM: "Media",
  LOW: "Baja",
  // Medios de pago de ventanilla.
  EFECTIVO: "Efectivo",
  TARJETA_DEBITO: "Tarjeta de débito",
  TARJETA_CREDITO: "Tarjeta de crédito",
  TRANSFERENCIA: "Transferencia",
  QR: "Billetera virtual / QR",
  VENTANILLA: "Ventanilla",
  HOMEBANKING: "Homebanking",
  DEBITO_AUTOMATICO: "Débito automático",
  // Ciclo interno del plan de pago, posterior al otorgamiento.
  CURRENT: "Vigente",
  FULFILLED: "Cumplido",
  DEFAULTED: "Incumplido",
  PARTIAL: "Parcial",
  // Conceptos: tipo, estado y forma de cálculo.
  TASA: "Tasa",
  MULTA: "Multa",
  CARGO: "Cargo",
  INACTIVE: "Inactivo",
  PORCENTAJE: "Porcentaje",
  FIJO: "Importe fijo",
  IMPORTE_EXTERNO: "Importe informado por el módulo de origen",
  // Procesamiento de eventos de integración.
  RECEIVED: "Recibido",
  PROCESSING: "Procesando",
  ERROR: "Error",
  OK: "OK",
  // Roles que figuran en el registro de auditoría.
  PERSONAL: "Personal de Rentas",
  SUPERVISOR: "Supervisor",
  CAJERO: "Cajero",
  AUDITOR: "Auditor",
  SISTEMA: "Sistema",
};

/** Acciones registradas en la auditoría, en el idioma del expediente. */
export const AUDIT_ACTION_LABELS = {
  SETTLEMENT_ISSUED: "Liquidación emitida",
  BILL_ISSUED: "Boleta emitida",
  PAYMENT_REGISTERED: "Pago registrado",
  PAYMENT_REVERSED: "Pago reversado",
  PAYMENT_PLAN_GRANTED: "Plan de pago otorgado",
  PAYMENT_PLAN_REJECTED: "Plan de pago rechazado",
  EXEMPTION_APPROVED: "Exención aprobada",
  EXEMPTION_REJECTED: "Exención rechazada",
  TICKET_STATUS_UPDATED: "Estado de ticket actualizado",
  EVENT_MOVED_TO_DLQ: "Evento enviado a DLQ",
  DEBT_REPORTED_OVERDUE: "Deuda vencida informada a M8",
};

export const actionLabelFor = (value) => AUDIT_ACTION_LABELS[value] ?? value ?? "—";

/** Entidades sobre las que puede recaer una acción auditada. */
export const ENTITY_LABELS = {
  SETTLEMENT: "Liquidación",
  DEBT: "Deuda",
  BILL: "Boleta",
  PAYMENT: "Pago",
  PAYMENT_PLAN: "Plan de pago",
  EXEMPTION: "Exención",
  TICKET: "Ticket",
  EVENT: "Evento",
};

export const entityLabelFor = (value) => ENTITY_LABELS[value] ?? value ?? "—";

export const labelFor = (value) => STATUS_LABELS[value] ?? value ?? "—";
