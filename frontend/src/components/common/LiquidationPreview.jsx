import { formatCurrency } from "../../lib/format.js";

/** Presenta la estrategia y los importes devueltos por la API; no recalcula tributos. */
export default function LiquidationPreview({ preview, taxableBase }) {
  const configuration = preview.configuration;
  const type = configuration.calculationType;
  const formula = type === "PERCENTAGE" || type === "PORCENTAJE"
    ? `${formatCurrency(taxableBase)} × ${configuration.rate} / 100`
    : type === "FIXED" || type === "FIJO" ? `Importe fijo: ${formatCurrency(configuration.fixedAmount)}`
      : type === "EXTERNAL" || type === "IMPORTE_EXTERNO" ? "Importe informado por el módulo de origen" : "El sistema no informó una fórmula reconocida.";
  return <section aria-label="Previsualización de la liquidación" className="rounded-lg border border-blue-200 bg-blue-50 p-4 text-sm">
    <h3 className="font-semibold text-blue-900">Fórmula aplicada · configuración v{preview.configurationVersion}</h3>
    <p className="mt-2 text-blue-900">{formula}</p>
    {configuration.minimumAmount != null && <p>Mínimo: {formatCurrency(configuration.minimumAmount)}</p>}
    {configuration.maximumAmount != null && <p>Máximo: {formatCurrency(configuration.maximumAmount)}</p>}
    <dl className="mt-3 grid grid-cols-2 gap-2">
      <dt>Importe base</dt><dd>{formatCurrency(preview.baseAmount)}</dd>
      <dt>Descuento</dt><dd>{formatCurrency(preview.discountAmount)}</dd>
      <dt>Exención</dt><dd>{formatCurrency(preview.exemptionAmount)}</dd>
      <dt>Recargo</dt><dd>{formatCurrency(preview.surchargeAmount)}</dd>
      <dt>Interés</dt><dd>{formatCurrency(preview.interestAmount)}</dd>
      <dt className="font-semibold">Total a liquidar</dt><dd className="font-semibold">{formatCurrency(preview.finalAmount)}</dd>
    </dl>
  </section>;
}
