/**
 * Módulos funcionales del área de trabajo de Personal de Rentas.
 * El orden sigue el flujo operativo acordado con el PO:
 * liquidación → deuda → boleta → pago → plan de pago → exenciones → tickets.
 *
 * `roles` limita la visibilidad: "PERSONAL" ve la operación diaria,
 * "SUPERVISOR" suma las resoluciones (planes, exenciones) y la bitácora de eventos.
 */
export const MODULES = [
  {
    id: "contribuyentes",
    path: "/rentas/contribuyentes",
    label: "Contribuyentes",
    iconName: "Users",
    description:
      "Padrón local de ciudadanos y organizaciones replicado desde M1. Sólo lectura.",
    roles: ["PERSONAL", "SUPERVISOR"],
    countLabel: "registrados",
  },
  {
    id: "tributos",
    path: "/rentas/tributos",
    label: "Configuración de tributos",
    iconName: "Tags",
    description:
      "Reglas de cálculo de cada concepto, versionadas y con aprobación del Supervisor.",
    roles: ["PERSONAL", "SUPERVISOR"],
    countLabel: "conceptos",
  },
  {
    id: "liquidaciones",
    path: "/rentas/liquidaciones",
    label: "Liquidaciones",
    iconName: "Calculator",
    description:
      "Generar y consultar liquidaciones por concepto y período fiscal.",
    roles: ["PERSONAL", "SUPERVISOR"],
    countLabel: "en el período",
  },
  {
    id: "deudas",
    path: "/rentas/deudas",
    label: "Deudas",
    iconName: "FileWarning",
    description:
      "Estado de cuenta, deuda vigente y vencida por contribuyente y origen.",
    roles: ["PERSONAL", "SUPERVISOR"],
    countLabel: "vencidas",
  },
  {
    id: "boletas",
    path: "/rentas/boletas",
    label: "Boletas",
    iconName: "FileText",
    description:
      "Emisión y descarga de boletas y comprobantes almacenados en S3.",
    roles: ["PERSONAL", "SUPERVISOR"],
    countLabel: "emitidas",
  },
  {
    id: "pagos",
    path: "/rentas/pagos",
    label: "Pagos",
    iconName: "Banknote",
    description:
      "Registrar, imputar y reversar pagos. Publica paymentRegistered / paymentReversed.",
    roles: ["PERSONAL", "SUPERVISOR"],
    countLabel: "del día",
  },
  {
    id: "ajustes",
    path: "/rentas/ajustes",
    label: "Ajustes y saldos",
    iconName: "SlidersHorizontal",
    description:
      "Corregir una deuda con autorización del Supervisor, o aplicarle un saldo a favor.",
    roles: ["PERSONAL", "SUPERVISOR"],
    countLabel: "pendientes",
  },
  {
    id: "planes",
    path: "/rentas/planes",
    label: "Planes de pago",
    iconName: "CalendarClock",
    description:
      "Solicitudes de plan, simulación de cuotas y resolución (GRANTED / REJECTED).",
    roles: ["PERSONAL", "SUPERVISOR"],
    countLabel: "pendientes",
  },
  {
    id: "refinanciacion",
    path: "/rentas/refinanciacion",
    label: "Refinanciación",
    iconName: "RefreshCw",
    description:
      "Rearmar planes incumplidos sobre su saldo vivo, conservando el original como antecedente.",
    roles: ["PERSONAL", "SUPERVISOR"],
    countLabel: "refinanciables",
  },
  {
    id: "exenciones",
    path: "/rentas/exenciones",
    label: "Exenciones",
    iconName: "ShieldCheck",
    description:
      "Solicitudes de exención, documentación respaldatoria y resolución hacia M8.",
    roles: ["PERSONAL", "SUPERVISOR"],
    countLabel: "pendientes",
  },
  {
    id: "tickets",
    path: "/rentas/tickets",
    label: "Tickets",
    iconName: "MessageSquare",
    description:
      "Reclamos derivados por M2. Cambio de estado vía updateTicketStatus.",
    roles: ["PERSONAL", "SUPERVISOR"],
    countLabel: "abiertos",
  },
  {
    id: "eventos",
    path: "/rentas/eventos",
    label: "Bitácora de eventos",
    iconName: "Activity",
    description:
      "Auditoría de eventos publicados y procesados, reintentos y DLQ.",
    roles: ["SUPERVISOR"],
    countLabel: "en DLQ",
  },
];

export function modulesForRole(role) {
  return MODULES.filter((m) => m.roles.includes(role));
}
