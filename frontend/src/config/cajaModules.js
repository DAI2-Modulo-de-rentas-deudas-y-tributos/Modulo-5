/**
 * Módulos de la ventanilla de caja.
 * El orden sigue el trabajo del cajero: cobrar es lo primero; consultar el padrón,
 * los pagos del día y las boletas son apoyos de esa tarea.
 *
 * El cajero cobra e imprime comprobantes: no liquida, no resuelve planes ni
 * exenciones y no reversa pagos.
 */
export const CAJA_MODULES = [
  {
    id: "cobros",
    path: "/caja/cobros",
    label: "Cobros",
    iconName: "HandCoins",
    description:
      "Buscar por documento, boleta o deuda, cobrar en ventanilla e imprimir el comprobante.",
    countLabel: "cobros hoy",
  },
  {
    id: "contribuyentes",
    path: "/caja/contribuyentes",
    label: "Contribuyentes",
    iconName: "Users",
    description:
      "Consulta del padrón local: datos, deudas y pagos. Sólo lectura, sin edición.",
    countLabel: "en el padrón",
  },
  {
    id: "pagos",
    path: "/caja/pagos",
    label: "Pagos",
    iconName: "Banknote",
    description:
      "Pagos registrados con filtros por fecha, estado y responsable del cobro.",
    countLabel: "registrados",
  },
  {
    id: "boletas",
    path: "/caja/boletas",
    label: "Boletas",
    iconName: "FileText",
    description:
      "Buscar una boleta por número, imprimirla o cobrarla directamente en ventanilla.",
    countLabel: "vigentes",
  },
];
