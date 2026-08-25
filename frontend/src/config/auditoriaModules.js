/**
 * Módulos del área de Auditoría.
 *
 * El orden sigue el circuito que audita: primero el padrón y las reglas (contribuyentes
 * y conceptos), después la cadena económica (liquidación → deuda → pago → plan →
 * exención), y al final la trazabilidad (tickets, integraciones, auditoría e indicadores).
 *
 * Todos los módulos son de consulta: el auditor observa y no modifica ninguna entidad.
 */
export const AUDITORIA_MODULES = [
  {
    id: "contribuyentes",
    path: "/auditor/contribuyentes",
    label: "Contribuyentes",
    iconName: "Users",
    description:
      "Ficha completa del contribuyente: deudas, pagos, planes y exenciones en un solo lugar.",
  },
  {
    id: "conceptos",
    path: "/auditor/conceptos",
    label: "Conceptos",
    iconName: "Tags",
    description:
      "Reglas de cálculo vigentes de cada tributo y el historial de versiones que las cambió.",
  },
  {
    id: "liquidaciones",
    path: "/auditor/liquidaciones",
    label: "Liquidaciones",
    iconName: "Calculator",
    description:
      "Cómo se compuso cada importe y qué evento externo la originó.",
  },
  {
    id: "deudas",
    path: "/auditor/deudas",
    label: "Deudas",
    iconName: "FileWarning",
    description:
      "Saldo, pagos aplicados, plan asociado e historial de estados de cada obligación.",
  },
  {
    id: "pagos",
    path: "/auditor/pagos",
    label: "Pagos",
    iconName: "Banknote",
    description:
      "Registrados, sin imputar, saldos a favor y reversiones, con su imputación al detalle.",
  },
  {
    id: "planes",
    path: "/auditor/planes",
    label: "Planes de Pago",
    iconName: "CalendarClock",
    description:
      "Financiación otorgada, cuotas, cumplimiento y deudas incluidas en cada plan.",
  },
  {
    id: "exenciones",
    path: "/auditor/exenciones",
    label: "Exenciones",
    iconName: "ShieldCheck",
    description:
      "Solicitudes, porcentaje aprobado frente al solicitado y quién resolvió cada una.",
  },
  {
    id: "tickets",
    path: "/auditor/tickets",
    label: "Tickets",
    iconName: "MessageSquare",
    description:
      "Reclamos derivados por M2, su estado en Rentas y la entidad a la que refieren.",
  },
  {
    id: "integraciones",
    path: "/auditor/integraciones",
    label: "Integraciones",
    iconName: "Webhook",
    description:
      "Eventos intercambiados con los demás módulos, su payload y qué generaron en Rentas.",
  },
  {
    id: "auditoria",
    path: "/auditor/auditoria",
    label: "Auditoría",
    iconName: "ScrollText",
    description:
      "Quién hizo qué, sobre qué entidad, con qué motivo y qué valores cambió.",
  },
  {
    id: "indicadores",
    path: "/auditor/indicadores",
    label: "Indicadores",
    iconName: "ChartColumn",
    description:
      "Liquidado, recaudado, deuda, morosidad e incumplimientos del período.",
  },
];
