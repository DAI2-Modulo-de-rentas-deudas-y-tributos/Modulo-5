import { ApiError, request } from "../apiClient.js";

export async function apiTaxConfigModel() {
  const [concepts, configurations] = await Promise.all([
    request("/api/v1/tax-concepts?size=100"),
    request("/api/v1/tax-configurations?size=100"),
  ]);
  return concepts.map((concept) => {
    const versions = configurations.filter((item) => item.taxConceptId === concept.id);
    return {
      ...concept,
      versions,
      activeVersion: versions.find((item) => item.status === "ACTIVE") ?? null,
      pendingVersion: versions.find((item) => item.status === "PENDING_APPROVAL") ?? null,
      draftVersion: versions.find((item) => item.status === "DRAFT") ?? null,
    };
  });
}

export async function apiConceptByCode(code) {
  const concepts = await request(`/api/v1/tax-concepts?q=${encodeURIComponent(code)}&size=100`);
  const concept = concepts.find((item) => item.code === code);
  if (!concept) throw new ApiError("Concepto inexistente.", 404);
  return concept;
}

export const taxConfigService = {
  /** Conceptos con su versión vigente y si tienen algo esperando aprobación. */
  async list({ type = "", status = "" } = {}) {
    const concepts = await apiTaxConfigModel();
    return concepts.filter((item) => (!type || item.type === type) && (!status || item.status === status));
  },

  /**
   * Catálogo de conceptos para los combos de las pantallas operativas.
   *
   * Los formularios no pueden ofrecer un código que el módulo no tenga dado de alta:
   * la operación fallaría recién al registrarla. Por defecto sólo devuelve los
   * conceptos activos, que son los únicos sobre los que se puede operar hoy.
   */
  async concepts({ onlyActive = true } = {}) {
    const concepts = await request("/api/v1/tax-concepts?size=100");
    return onlyActive ? concepts.filter((c) => c.status === "ACTIVE") : concepts;
  },

  /** Ficha del concepto con todo su historial de versiones. */
  async detail(code) {
    const concept = (await apiTaxConfigModel()).find((item) => item.code === code);
    if (!concept) throw new ApiError("Concepto inexistente.", 404);
    return { ...concept, settlementCount: null, openDebtCount: null };
  },

  /**
   * ProposeTaxConfigVersionRequest: crea una versión en borrador con las reglas de
   * cálculo propuestas. No rige hasta que el Supervisor la apruebe.
   */
  async proposeVersion({
    code,
    calculationType,
    rate,
    minimumAmount,
    maximumAmount,
    validFrom,
    validUntil,
    conceptStatus,
    note,
    requestedBy,
  }) {
    const concept = await apiConceptByCode(code);
    // El código de cálculo viaja en español: `adaptApiRequest` lo traduce al enum
    // del backend (CALC_TO_API) y normaliza los importes vacíos del formulario.
    return request("/api/v1/tax-configurations", {
      method: "POST",
      body: {
        taxConceptId: concept.id,
        calculationType,
        rate,
        // El formulario no pide alícuota cuando el cálculo es fijo: el importe de
        // esa forma de cálculo es el mínimo cargado, no cero.
        fixedAmount: calculationType === "FIJO" ? rate || minimumAmount : 0,
        minimumAmount,
        maximumAmount,
        partialPaymentAllowed: true,
        paymentPlanAllowed: true,
        validFrom,
        validUntil,
      },
    });
  },

  /** El borrador pasa a la bandeja del Supervisor. */
  async submitForApproval({ code, version, requestedBy }) {
    const concept = await apiConceptByCode(code);
    const versions = await request(`/api/v1/tax-configurations?conceptId=${concept.id}&size=100`);
    const target = versions.find((item) => item.version === Number(version));
    if (!target) throw new ApiError("Versión inexistente.", 404);
    return request(`/api/v1/tax-configurations/${target.id}/submit`, { method: "POST" });
  },

  /** Bandeja del Supervisor: todo lo que espera aprobación, de lo más viejo a lo nuevo. */
  async pendingApprovals() {
    return (await apiTaxConfigModel()).flatMap((concept) => concept.versions
      .filter((item) => item.status === "PENDING_APPROVAL")
      .map((item) => ({ ...item, code: concept.code, name: concept.name, type: concept.type, currentVersion: concept.activeVersion })));
  },

  /**
   * ResolveTaxConfigVersionRequest. Al aprobar, la versión pasa a regir y la anterior
   * queda inactiva: se conserva el historial completo, nunca se borra una versión.
   */
  async resolveVersion({ code, version, status, resolvedBy, resolverRole, reason }) {
    const concept = await apiConceptByCode(code);
    const versions = await request(`/api/v1/tax-configurations?conceptId=${concept.id}&size=100`);
    const target = versions.find((item) => item.version === Number(version));
    if (!target) throw new ApiError("Versión inexistente.", 404);
    const action = status === "REJECTED" ? "reject" : "approve";
    return request(`/api/v1/tax-configurations/${target.id}/${action}`, { method: "POST", body: action === "reject" ? { reason } : { observation: reason ?? null } });
  },
};

