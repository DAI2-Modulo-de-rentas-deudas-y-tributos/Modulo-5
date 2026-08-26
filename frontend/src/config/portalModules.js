/**
 * Módulos del portal del contribuyente.
 *
 * El ciudadano consulta su propio legajo: qué debe, qué le emitieron y qué pagó.
 * No registra pagos —eso pasa por ventanilla o por los canales de cobro— y no
 * resuelve nada: sólo puede *solicitar* un plan de pago o una exención, que es
 * lo que viaja en `paymentPlanRequested` y `exemptionRequested`.
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
];
