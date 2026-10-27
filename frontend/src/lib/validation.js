/** Restricciones de los DTO públicos de M5. Los límites tributarios siempre los calcula la API. */
export const FIELD_CONSTRAINTS = {
  period: { maxLength: 7, pattern: "[0-9]{4}-[0-9]{2}" },
  username: { maxLength: 100 },
  installments: { min: 1, max: 60, step: 1 },
  requestedInstallments: { min: 1, max: 60, step: 1 },
  baseAmount: { min: 0, step: "0.01" },
  taxableBase: { min: 0, step: "0.01" },
  amount: { min: "0.01", step: "0.01" },
  amountPaid: { min: "0.01", step: "0.01" },
  requestedPercentage: { min: 0, max: 100, step: "0.01" },
  percentage: { min: 0, max: 100, step: "0.01" },
  minimumDownPaymentPercentage: { min: 0, max: 100, step: "0.01" },
};

export function validAmount(value, { allowZero = false } = {}) {
  if (!(typeof value === "number" || typeof value === "string" && value.trim())) return false;
  const number = Number(value);
  return Number.isFinite(number) && (allowZero ? number >= 0 : number > 0);
}

export const finiteNumber = (value) => Number.isFinite(Number(value)) ? Number(value) : Number.NaN;

export const validPeriod = (value) => /^\d{4}-(0[1-9]|1[0-2])$/.test(value ?? "");
export function validDate(value) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value ?? "")) return false;
  const date = new Date(`${value}T00:00:00Z`);
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value;
}

/** Conserva los mensajes de validación del servidor y los vincula con los campos de la pantalla. */
export function serverFieldErrors(error, aliases = {}, availableFields) {
  const fields = error?.details?.details?.fieldErrors;
  if (!Array.isArray(fields)) return {};
  const candidates = { taxableBase: ["baseAmount"], taxConceptId: ["conceptCode", "conceptId"], paymentMethod: ["method"], requestedInstallments: ["installments"], requestedPercentage: ["percentage"], debtId: ["selectedDebtId"] };
  const target = (field) => (Object.hasOwn(aliases, field) ? aliases[field] : null)
    ?? (availableFields?.includes(field) ? field : (Object.hasOwn(candidates, field) ? candidates[field] : []).find((name) => availableFields?.includes(name))) ?? field;
  return Object.fromEntries(fields.filter((item) => typeof item.field === "string" && Array.isArray(item.messages))
    .map((item) => [target(item.field), item.messages.filter((message) => typeof message === "string").join(". ")])
    .filter(([, message]) => message));
}
