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
};

export const labelFor = (value) => STATUS_LABELS[value] ?? value ?? "—";
