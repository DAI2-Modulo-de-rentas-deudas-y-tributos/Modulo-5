/**
 * Catálogos de los enums del backend.
 *
 * Los `value` son los del dominio y no se traducen: mandar un valor que el backend
 * no conoce termina en un filtro rechazado o un guardado fallido. El `label` es
 * sólo rotulación de pantalla.
 *
 * Fuente: DomainEntities.java. Si allá se agrega un valor, se agrega acá y
 * catalogosDominio.test.js avisa del desajuste.
 */

export const PAYMENT_METHODS = [
  { value: "CASH", label: "Efectivo" },
  { value: "CARD", label: "Tarjeta" },
  { value: "TRANSFER", label: "Transferencia" },
  { value: "DIGITAL_WALLET", label: "Billetera virtual / QR" },
];

export const PAYMENT_ORIGINS = [
  { value: "CASHIER", label: "Ventanilla" },
  { value: "ELECTRONIC", label: "Electrónico" },
  { value: "EXTERNAL", label: "Externo" },
];

export const DEBT_ORIGIN_TYPES = [
  { value: "LIQUIDATION", label: "Liquidación propia" },
  { value: "EXTERNAL_OBLIGATION", label: "Obligación externa" },
];

/**
 * Tipos de obligación externa. No son valores de `Debt.originType`: para acotar
 * deudas por M4 o M7 se filtra por concepto tributario, que es donde el backend
 * los representa.
 */
export const EXTERNAL_OBLIGATION_TYPES = [
  { value: "PERMIT_FEE", label: "Tasa de habilitación (M4)" },
  { value: "COMMERCIAL_FINE", label: "Multa comercial (M4)" },
  { value: "TRAFFIC_INFRACTION", label: "Infracción de tránsito (M7)" },
];

/** Etiqueta de un valor, o el valor crudo si el catálogo no lo contempla. */
export const labelOf = (catalogo, valor) =>
  catalogo.find((opcion) => opcion.value === valor)?.label ?? valor;
