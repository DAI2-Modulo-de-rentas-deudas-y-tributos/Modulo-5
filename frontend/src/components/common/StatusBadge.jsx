import { labelFor } from "../../lib/format.js";

/** Paleta por familia de estado: verde resuelto, ámbar pendiente, rojo problema. */
const TONES = {
  neutral: "bg-neutral-100 text-neutral-600 border-neutral-200",
  success: "bg-emerald-50 text-emerald-700 border-emerald-200",
  warning: "bg-amber-50 text-amber-700 border-amber-200",
  danger: "bg-red-50 text-red-700 border-red-200",
  info: "bg-blue-50 text-blue-700 border-blue-200",
  navy: "bg-[#0F2C59]/5 text-[#0F2C59] border-[#0F2C59]/15",
};

const STATUS_TONES = {
  SETTLED: "success",
  APPROVED: "success",
  GRANTED: "success",
  COMPLETED: "success",
  REGISTERED: "success",
  PROCESSED: "success",
  PUBLISHED: "success",
  ACTIVE: "success",
  ISSUED: "info",
  IN_PROGRESS: "info",
  OPEN: "info",
  DRAFT: "neutral",
  CITIZEN: "neutral",
  ORGANIZATION: "neutral",
  PENDING: "warning",
  REQUESTED: "warning",
  PENDING_REVIEW: "info",
  PENDING_SUPERVISOR: "warning",
  UNDER_REVIEW: "warning",
  REFINANCED: "navy",
  UNALLOCATED: "warning",
  WAITING_FOR_INFORMATION: "warning",
  RETRYING: "warning",
  MEDIUM: "warning",
  LOW: "neutral",
  HIGH: "danger",
  OVERDUE: "danger",
  EXPIRED: "danger",
  REJECTED: "danger",
  REVERSED: "danger",
  BLOCKED: "danger",
  DECEASED: "danger",
  DLQ: "danger",
  // Ciclo del plan de pago.
  CURRENT: "info",
  FULFILLED: "success",
  DEFAULTED: "danger",
  PARTIAL: "warning",
  // Conceptos y procesamiento de eventos.
  INACTIVE: "neutral",
  TASA: "neutral",
  MULTA: "neutral",
  CARGO: "neutral",
  RECEIVED: "neutral",
  PROCESSING: "info",
  OK: "success",
  ERROR: "danger",
};

export default function StatusBadge({ status, tone, label }) {
  const resolvedTone = tone ?? STATUS_TONES[status] ?? "neutral";
  return (
    <span
      className={`inline-flex items-center rounded-full border px-2 py-0.5 text-[11px] font-semibold ${TONES[resolvedTone]}`}
    >
      {label ?? labelFor(status)}
    </span>
  );
}
