import { request } from "./apiClient.js";

export const debtDetailsService = {
  async detail(id, { includeExternalOrigin = false } = {}) {
    const debt = await request(`/api/v1/debts/${encodeURIComponent(id)}`);
    if (!includeExternalOrigin || debt.originType !== "EXTERNAL_OBLIGATION" || !debt.externalObligationId) return debt;
    try {
      const source = await request(`/api/v1/external-obligations/${encodeURIComponent(debt.externalObligationId)}`);
      return { ...debt, source };
    } catch (error) {
      // La deuda sigue siendo consultable aunque su origen no esté disponible.
      return { ...debt, sourceError: error.message };
    }
  },
};
