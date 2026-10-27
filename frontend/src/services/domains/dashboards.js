import { request } from "../apiClient.js";

export const dashboardService = {
  /** Métricas comunes más el resumen específico autorizado para cada rol operativo. */
  async metrics(role) {
    const rolePath = role === "SUPERVISOR"
      ? "/api/v1/dashboards/supervisor"
      : role === "RENTAS" ? "/api/v1/dashboards/rentas" : null;
    const [base, specific] = await Promise.all([
      request("/api/v1/dashboard/metrics"),
      rolePath ? request(rolePath) : Promise.resolve({}),
    ]);
    const supervisor = role === "SUPERVISOR" ? {
      planes: specific.exceptionalPlans ?? 0,
      exenciones: specific.exemptions ?? 0,
      ajustes: specific.adjustments ?? 0,
      refinanciacion: specific.exceptionalRefinancings ?? 0,
      reversiones: specific.reversals ?? 0,
      corridas: specific.liquidationRuns ?? 0,
      caducidades: specific.planExpirations ?? 0,
      pendingTotal: specific.totalPending ?? 0,
    } : {};
    const operational = role === "RENTAS" ? {
      planes: specific.pendingPlanRequests ?? 0,
      exenciones: specific.pendingExemptions ?? 0,
      tickets: specific.openTickets ?? 0,
      unallocatedPayments: specific.unallocatedPaymentAmount ?? 0,
      recentActivity: specific.recentOperations ?? [],
    } : {};
    return {
      ...base,
      planes: 0,
      exenciones: 0,
      tickets: 0,
      ajustes: 0,
      refinanciacion: 0,
      reversiones: 0,
      corridas: 0,
      caducidades: 0,
      ...supervisor,
      ...operational,
    };
  },
};
