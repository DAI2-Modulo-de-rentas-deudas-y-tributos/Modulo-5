/**
 * Módulos del portal del contribuyente.
 *
 * El ciudadano consulta su propio legajo: qué debe, qué le emitieron y qué pagó.
 * Puede registrar pagos electrónicos y consultar sus resultados; las demás
 * operaciones sensibles siguen siendo solicitudes que Rentas debe resolver.
 */
export const PORTAL_MODULES = [
  {
    id: "deudas",
    path: "/portal/deudas",
    label: "Mis deudas",
    iconName: "FileWarning",
    description:
      "Obligaciones a tu nombre, su vencimiento y el saldo que queda por pagar.",
  },
  {
    id: "boletas",
    path: "/portal/boletas",
    label: "Mis boletas",
    iconName: "FileText",
    description: "Boletas emitidas para que puedas pagarlas o descargarlas.",
  },
  {
    id: "pagos",
    path: "/portal/pagos",
    label: "Mis pagos",
    iconName: "Banknote",
    description: "Pagos registrados a tu nombre y a qué deuda se aplicó cada uno.",
  },
  {
    id: "pago-electronico",
    path: "/portal/pago-electronico",
    label: "Pago electrónico",
    iconName: "CreditCard",
    description: "Pagá una deuda con tarjeta o billetera y obtené tu comprobante.",
  },
  {
    id: "planes",
    path: "/portal/planes",
    label: "Planes de pago",
    iconName: "CalendarClock",
    description:
      "Pedí financiar tu deuda en cuotas y seguí el estado de tus solicitudes.",
  },
  {
    id: "exenciones",
    path: "/portal/exenciones",
    label: "Exenciones",
    iconName: "ShieldCheck",
    description:
      "Solicitá una exención total o parcial y seguí cómo se resuelve.",
  },
  {
    id: "refinanciacion",
    path: "/portal/refinanciacion",
    label: "Refinanciación",
    iconName: "RefreshCw",
    description: "Solicitá nuevas condiciones para el saldo de un plan activo.",
  },
  {
    id: "beneficios",
    path: "/portal/beneficios",
    label: "Beneficios tributarios",
    iconName: "BadgePercent",
    description: "Consultá tus beneficios vigentes, porcentaje y alcance.",
  },
];
